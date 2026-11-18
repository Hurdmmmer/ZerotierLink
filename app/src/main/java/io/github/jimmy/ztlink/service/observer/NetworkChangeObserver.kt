package io.github.jimmy.ztlink.service.observer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络传输类型。
 */
enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    UNKNOWN,
}

/**
 * 网络变化事件。
 *
 * @property reason 触发原因。
 * @property from 切换前传输类型。
 * @property to 切换后传输类型。
 */
data class NetworkChangeEvent(
    val reason: String,
    val from: NetworkTransport,
    val to: NetworkTransport,
    val wifiIpv4: String? = null,
    val wifiPrefixLength: Int? = null,
)

/**
 * Wi-Fi IPv4 信息。
 */
data class WifiIpv4Info(
    val address: String,
    val prefixLength: Int,
)

/**
 * 一次网络观察会话的所有权凭据。
 *
 * 单例观察器可能先后被多个 VpnService 实例使用。停止时必须带上启动返回的凭据，
 * 避免旧 Service 的 onDestroy 注销新 Service 已经注册的回调。
 */
class NetworkObservationLease internal constructor(
    internal val id: Long,
)

/**
 * 网络变化监听器。
 */
fun interface NetworkChangeListener {

    /**
     * 网络变化回调。
     *
     * @param event 网络变化事件。
     */
    fun onNetworkChanged(
        event: NetworkChangeEvent,
    )
}

/**
 * 纯逻辑所有权状态机。
 *
 * 把 Android 回调注册之外的 generation 判断单独建模，确保旧 owner 无法释放或派发
 * 新 owner 的观察会话，并允许纯 JVM 单元测试覆盖竞态约束。
 */
internal class ObservationOwnership<T> {

    private var nextId: Long = 0L
    private var activeLease: NetworkObservationLease? = null
    private var activeResource: T? = null

    /**
     * 获取新所有权，并使旧所有权立即失效。
     *
     * @return 新 lease 与需要由调用方释放的旧资源。
     */
    @Synchronized
    fun acquire(resource: T): Pair<NetworkObservationLease, T?> {
        val previousResource = activeResource
        val lease = NetworkObservationLease(id = ++nextId)
        activeLease = lease
        activeResource = resource
        return lease to previousResource
    }

    /**
     * 仅允许当前 owner 释放资源。
     */
    @Synchronized
    fun release(lease: NetworkObservationLease): T? {
        if (activeLease != lease) {
            return null
        }
        val resource = activeResource
        activeLease = null
        activeResource = null
        return resource
    }

    /**
     * 判断回调是否仍属于当前观察会话。
     */
    @Synchronized
    fun isActive(lease: NetworkObservationLease): Boolean = activeLease == lease
}

/**
 * 底层网络身份。
 *
 * Android [Network] 的 handle 表示具体网络实例。传输类型相同但 handle 改变时，
 * 原 UDP Socket 仍可能绑定在旧路径上，因此也必须视为网络切换。
 */
internal data class UnderlyingNetworkIdentity(
    val handle: Long,
    val transport: NetworkTransport,
)

/**
 * 底层网络切换判定器。
 */
internal class UnderlyingNetworkTransitionTracker {

    private var lastIdentity: UnderlyingNetworkIdentity? = null

    /**
     * 初始化当前网络，不产生启动事件。
     */
    fun reset(identity: UnderlyingNetworkIdentity?) {
        lastIdentity = identity
    }

    /**
     * 更新网络身份；无网络过渡只更新状态，不向上层制造恢复噪声。
     */
    fun update(identity: UnderlyingNetworkIdentity?): NetworkChangeEvent? {
        val previous = lastIdentity
        if (previous == identity) {
            return null
        }
        lastIdentity = identity
        identity ?: return null
        val reason = if (previous?.transport == identity.transport) {
            "${identity.transport.name.lowercase()}_network_changed"
        } else {
            "${(previous?.transport ?: NetworkTransport.NONE).name.lowercase()}_to_" +
                identity.transport.name.lowercase()
        }
        return NetworkChangeEvent(
            reason = reason,
            from = previous?.transport ?: NetworkTransport.NONE,
            to = identity.transport,
        )
    }
}

/**
 * Android 网络变化观察器。
 *
 * 约束：
 * 1. 只监听具备 INTERNET、NOT_RESTRICTED、NOT_VPN 的真实底层网络；
 * 2. Android Network identity 变化即派发，不能只比较 Wi-Fi/蜂窝传输类型；
 * 3. NONE/UNKNOWN/VPN 过渡不派发，避免内核被短暂断网噪声反复启停；
 * 4. 观察会话使用 lease 隔离，旧 Service 不得停止或派发新 Service 的回调。
 */
@Singleton
class NetworkChangeObserver @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {

    private data class ObservedNetwork(
        val network: Network,
        val capabilities: NetworkCapabilities,
    ) {
        val identity: UnderlyingNetworkIdentity
            get() = UnderlyingNetworkIdentity(
                handle = network.networkHandle,
                transport = capabilities.toNetworkTransport(),
            )
    }

    /** 系统连接管理器。 */
    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)

    /** 系统 Wi-Fi 管理器，仅作为旧设备 IPv4 信息的保守回退。 */
    private val wifiManager: WifiManager =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    /** 回调与候选网络状态共同使用的锁，防止 Binder 回调和 Service 生命周期并发修改。 */
    private val stateLock = Any()

    /** 观察会话所有权。 */
    private val ownership = ObservationOwnership<ConnectivityManager.NetworkCallback>()

    /** 当前满足底层网络约束的候选网络。 */
    private val observedNetworks = LinkedHashMap<Long, ObservedNetwork>()

    /** 当前底层网络身份切换状态。 */
    private val transitionTracker = UnderlyingNetworkTransitionTracker()

    /**
     * 最近一次向上层派发的 Wi-Fi IPv4 地址，用于 DHCP 完成补偿。
     */
    @Volatile
    private var lastDispatchedWifiIpv4: String? = null

    /**
     * 只匹配真实底层可联网网络。
     *
     * NOT_VPN 防止 VpnService 建立后把自身 TUN 当成默认网络；
     * NOT_RESTRICTED 与 Android 常规默认网络选择语义一致，避免受限后台网络参与切换。
     */
    private val underlyingNetworkRequest: NetworkRequest
        get() = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    /**
     * 启动监听并返回本次观察会话凭据。
     *
     * 新调用会接管单例观察器；旧回调先失效再注销，注销期间到达的迟发回调会被 lease
     * 校验丢弃。调用方销毁时必须使用返回值调用 [stop]。
     */
    fun start(
        listener: NetworkChangeListener,
    ): NetworkObservationLease {
        return synchronized(stateLock) {
            lateinit var lease: NetworkObservationLease
            val callback = buildNetworkCallback(
                listener = listener,
                isActive = { ownership.isActive(lease) },
            )
            val acquired = ownership.acquire(callback)
            lease = acquired.first
            unregisterCallback(acquired.second)
            refreshObservedNetworksLocked()
            transitionTracker.reset(selectUnderlyingNetworkLocked()?.identity)
            lastDispatchedWifiIpv4 = currentWifiIpv4InfoLocked()?.address
            try {
                connectivityManager.registerNetworkCallback(underlyingNetworkRequest, callback)
            } catch (error: RuntimeException) {
                ownership.release(lease)
                observedNetworks.clear()
                transitionTracker.reset(null)
                lastDispatchedWifiIpv4 = null
                throw error
            }
            lease
        }
    }

    /**
     * 停止指定观察会话。
     *
     * 非当前 lease 表示调用来自旧 Service，必须忽略，不能影响新观察会话。
     */
    fun stop(lease: NetworkObservationLease) {
        val callback = synchronized(stateLock) {
            val released = ownership.release(lease) ?: return
            observedNetworks.clear()
            transitionTracker.reset(null)
            lastDispatchedWifiIpv4 = null
            released
        }
        unregisterCallback(callback)
    }

    /**
     * 构建系统网络回调。
     */
    private fun buildNetworkCallback(
        listener: NetworkChangeListener,
        isActive: () -> Boolean,
    ): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isActive()) {
                    return
                }
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                updateObservedNetwork(network, capabilities, listener, isActive)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                updateObservedNetwork(network, networkCapabilities, listener, isActive)
            }

            override fun onLost(network: Network) {
                if (!isActive()) {
                    return
                }
                val event = synchronized(stateLock) {
                    if (!isActive()) {
                        return@synchronized null
                    }
                    observedNetworks.remove(network.networkHandle)
                    buildTransitionEventLocked()
                }
                event?.let(listener::onNetworkChanged)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                if (!isActive()) {
                    return
                }
                val event = synchronized(stateLock) {
                    if (!isActive() || selectUnderlyingNetworkLocked()?.network != network) {
                        return@synchronized null
                    }
                    buildWifiIpEventLocked(linkProperties)
                }
                event?.let(listener::onNetworkChanged)
            }
        }
    }

    /**
     * 更新候选网络并根据 Android Network identity 判定是否切换。
     */
    private fun updateObservedNetwork(
        network: Network,
        capabilities: NetworkCapabilities,
        listener: NetworkChangeListener,
        isActive: () -> Boolean,
    ) {
        if (!isActive()) {
            return
        }
        val event = synchronized(stateLock) {
            if (!isActive()) {
                return@synchronized null
            }
            if (capabilities.isEligibleUnderlyingNetwork()) {
                observedNetworks[network.networkHandle] = ObservedNetwork(network, capabilities)
            } else {
                observedNetworks.remove(network.networkHandle)
            }
            buildTransitionEventLocked()
        }
        event?.let(listener::onNetworkChanged)
    }

    /**
     * 构建切换事件并补充 Wi-Fi 地址。
     */
    private fun buildTransitionEventLocked(): NetworkChangeEvent? {
        val selected = selectUnderlyingNetworkLocked()
        val event = transitionTracker.update(selected?.identity) ?: return null
        val wifiInfo = if (event.to == NetworkTransport.WIFI) currentWifiIpv4InfoLocked() else null
        lastDispatchedWifiIpv4 = wifiInfo?.address
        android.util.Log.d(
            LOG_TAG,
            "Underlying network changed: reason=${event.reason}, from=${event.from}, " +
                "to=${event.to}, handle=${selected?.network?.networkHandle}, ip=${wifiInfo?.address}",
        )
        return event.copy(
            wifiIpv4 = wifiInfo?.address,
            wifiPrefixLength = wifiInfo?.prefixLength,
        )
    }

    /**
     * DHCP 完成补偿：当前 Wi-Fi 地址新增或改变时派发策略复检。
     */
    private fun buildWifiIpEventLocked(linkProperties: LinkProperties): NetworkChangeEvent? {
        val wifiInfo = linkProperties.toWifiIpv4Info() ?: currentWifiIpv4InfoLocked() ?: return null
        val previousIp = lastDispatchedWifiIpv4
        if (wifiInfo.address == previousIp) {
            return null
        }
        lastDispatchedWifiIpv4 = wifiInfo.address
        android.util.Log.d(
            LOG_TAG,
            "WiFi IP assigned/changed: prev=$previousIp new=${wifiInfo.address}/${wifiInfo.prefixLength}",
        )
        return NetworkChangeEvent(
            reason = "wifi_ip_assigned",
            from = NetworkTransport.WIFI,
            to = NetworkTransport.WIFI,
            wifiIpv4 = wifiInfo.address,
            wifiPrefixLength = wifiInfo.prefixLength,
        )
    }

    /**
     * 刷新系统当前真实底层网络，供启动快照和同步查询使用。
     */
    @Suppress("DEPRECATION")
    private fun refreshObservedNetworksLocked() {
        observedNetworks.clear()
        connectivityManager.allNetworks.forEach { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            if (capabilities.isEligibleUnderlyingNetwork()) {
                observedNetworks[network.networkHandle] = ObservedNetwork(network, capabilities)
            }
        }
    }

    /**
     * 选择当前底层网络。
     *
     * 按 Wi-Fi、Ethernet、Cellular 的稳定优先级选择。不能无条件保留旧 identity：
     * 蜂窝与新 Wi-Fi 常会短暂并存，若继续保留蜂窝，会漏掉系统恢复 Wi-Fi 的默认路径切换。
     * 同类型以稳定 handle 排序，使候选集合未变化时选择结果保持一致。
     */
    private fun selectUnderlyingNetworkLocked(): ObservedNetwork? {
        return selectPreferredUnderlyingNetwork(
            candidates = observedNetworks.values,
            identityOf = ObservedNetwork::identity,
        )
    }

    /**
     * 返回当前观察到的真实底层传输类型。
     */
    fun currentTransport(): NetworkTransport = synchronized(stateLock) {
        refreshObservedNetworksLocked()
        val selected = selectUnderlyingNetworkLocked()
        selected?.identity?.transport ?: NetworkTransport.NONE
    }

    /**
     * 返回当前观察到的真实底层 [Network] 实例（具备 INTERNET 且非 VPN）。
     */
    fun currentUnderlyingNetwork(): Network? = synchronized(stateLock) {
        refreshObservedNetworksLocked()
        selectUnderlyingNetworkLocked()?.network
    }

    /**
     * 读取当前 Wi-Fi IPv4 文本。
     */
    fun currentWifiIpv4Address(): String? = currentWifiIpv4Info()?.address

    /**
     * 读取当前 Wi-Fi IPv4 信息。
     *
     * 优先读取所选真实 Wi-Fi Network 的 LinkProperties，避免 VPN/TUN 地址混入；
     * WifiManager 仅用于系统暂未提供 LinkProperties 时的兼容回退。
     */
    @Suppress("DEPRECATION")
    fun currentWifiIpv4Info(
        observedNetwork: Network? = null,
        observedCapabilities: NetworkCapabilities? = null,
        observedLinkProperties: LinkProperties? = null,
        preferObservedNetwork: Boolean = true,
    ): WifiIpv4Info? = synchronized(stateLock) {
        val observedIsWifi = observedCapabilities
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ?: false
        if (preferObservedNetwork && observedNetwork != null && observedIsWifi) {
            observedLinkProperties?.toWifiIpv4Info()?.let { return@synchronized it }
            connectivityManager.getLinkProperties(observedNetwork)
                ?.toWifiIpv4Info()
                ?.let { return@synchronized it }
        }
        refreshObservedNetworksLocked()
        currentWifiIpv4InfoLocked()
    }

    /**
     * 在持锁状态下读取当前真实 Wi-Fi 地址。
     */
    @Suppress("DEPRECATION")
    private fun currentWifiIpv4InfoLocked(): WifiIpv4Info? {
        val selected = selectUnderlyingNetworkLocked() ?: return null
        if (selected.identity.transport != NetworkTransport.WIFI) {
            return null
        }
        connectivityManager.getLinkProperties(selected.network)
            ?.toWifiIpv4Info()
            ?.let { return it }
        val rawIp = wifiManager.connectionInfo?.ipAddress ?: return null
        if (rawIp == 0) {
            return null
        }
        return WifiIpv4Info(
            address = formatLittleEndianIpv4(rawIp),
            prefixLength = resolveWifiPrefixLength(),
        )
    }

    /**
     * 注销回调。重复注销和系统生命周期竞态不应破坏后续新会话。
     */
    private fun unregisterCallback(callback: ConnectivityManager.NetworkCallback?) {
        callback ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }.onFailure { error ->
            android.util.Log.w(LOG_TAG, "Failed to unregister network callback", error)
        }
    }

    /**
     * 解析 Wi-Fi 前缀长度。
     */
    @Suppress("DEPRECATION")
    private fun resolveWifiPrefixLength(): Int {
        val netmask = wifiManager.dhcpInfo?.netmask ?: return 24
        if (netmask == 0) {
            return 24
        }
        return Integer.bitCount(netmask).coerceIn(0, 32)
    }

    /**
     * 把 Android little-endian Int IPv4 转成点分十进制。
     */
    private fun formatLittleEndianIpv4(raw: Int): String {
        val b1 = raw and 0xFF
        val b2 = raw shr 8 and 0xFF
        val b3 = raw shr 16 and 0xFF
        val b4 = raw shr 24 and 0xFF
        return "$b1.$b2.$b3.$b4"
    }

    private companion object {
        const val LOG_TAG: String = "NetworkChangeObserver"
    }
}

/**
 * 判断能力是否可作为真实底层网络。
 */
private fun NetworkCapabilities.isEligibleUnderlyingNetwork(): Boolean {
    val hasSupportedTransport =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return hasSupportedTransport &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
}

/**
 * 转换传输类型。
 */
private fun NetworkCapabilities.toNetworkTransport(): NetworkTransport {
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        else -> NetworkTransport.UNKNOWN
    }
}

/**
 * 真实底层网络稳定选择优先级。
 */
private fun NetworkTransport.selectionPriority(): Int {
    return when (this) {
        NetworkTransport.WIFI -> 3
        NetworkTransport.ETHERNET -> 2
        NetworkTransport.CELLULAR -> 1
        else -> 0
    }
}

/**
 * 从候选集合选择稳定的首选底层网络。
 *
 * 泛型形式让网络优先级无需构造 Android Framework 对象即可通过纯 JVM 测试。
 */
internal fun <T> selectPreferredUnderlyingNetwork(
    candidates: Collection<T>,
    identityOf: (T) -> UnderlyingNetworkIdentity,
): T? {
    return candidates.maxWithOrNull(
        compareBy<T> { identityOf(it).transport.selectionPriority() }
            .thenBy { identityOf(it).handle },
    )
}

/**
 * 从真实网络链路属性提取 IPv4 地址。
 */
private fun LinkProperties.toWifiIpv4Info(): WifiIpv4Info? {
    val linkAddress = linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    return WifiIpv4Info(
        address = linkAddress.address.hostAddress ?: return null,
        prefixLength = linkAddress.prefixLength,
    )
}
