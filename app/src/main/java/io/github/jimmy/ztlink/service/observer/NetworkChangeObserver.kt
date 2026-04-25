package io.github.jimmy.ztlink.service.observer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
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
 * @property reason 触发原因（例如 network_available / network_lost）。
 * @property from 切换前传输类型。
 * @property to 切换后传输类型。
 */
data class NetworkChangeEvent(
    val reason: String,
    val from: NetworkTransport,
    val to: NetworkTransport,
    val wifiSsid: String? = null,
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
 * Android 网络变化观察器。
 *
 * 说明：
 * 1. 监听系统默认网络变化；
 * 2. 将系统事件映射为统一事件模型；
 * 3. 只负责观察，不包含策略判断。
 */
@Singleton
class NetworkChangeObserver @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /** 系统连接管理器。 */
    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager: WifiManager =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    /** 当前回调实例。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 最近一次传输类型。 */
    private var lastTransport: NetworkTransport = NetworkTransport.UNKNOWN

    /** 当前可用的非 VPN 网络能力缓存。 */
    private val observedNetworks: MutableMap<Network, NetworkCapabilities> = ConcurrentHashMap()

    /**
     * 启动监听。
     *
     * @param listener 上层监听器。
     */
    fun start(
        listener: NetworkChangeListener,
    ) {
        if (networkCallback != null) {
            return
        }

        val callback = buildNetworkCallback(listener)
        networkCallback = callback
        lastTransport = resolveTransport()
        connectivityManager.registerNetworkCallback(buildUnderlyingNetworkRequest(), callback)
    }

    /**
     * 构建系统网络回调。
     *
     * Android 12+ 默认会对 `NetworkCapabilities.transportInfo` 里的 Wi-Fi SSID 脱敏。
     * 策略判断明确依赖用户配置的 SSID，因此这里需要请求包含位置信息的回调。
     */
    private fun buildNetworkCallback(listener: NetworkChangeListener): ConnectivityManager.NetworkCallback {
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    updateObservedNetwork(network)
                    dispatch(listener, "network_available")
                }

                override fun onLost(network: Network) {
                    observedNetworks.remove(network)
                    dispatch(listener, "network_lost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    updateObservedCapabilities(network, networkCapabilities)
                    dispatch(listener, "network_capabilities_changed")
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateObservedNetwork(network)
                    dispatch(listener, "network_available")
                }

                override fun onLost(network: Network) {
                    observedNetworks.remove(network)
                    dispatch(listener, "network_lost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    updateObservedCapabilities(network, networkCapabilities)
                    dispatch(listener, "network_capabilities_changed")
                }
            }
        }
        return callback
    }

    private fun updateObservedCapabilities(network: Network, networkCapabilities: NetworkCapabilities) {
        if (networkCapabilities.isUsableUnderlyingNetwork()) {
            observedNetworks[network] = networkCapabilities
        } else {
            observedNetworks.remove(network)
        }
    }

    /**
     * 停止监听。
     */
    fun stop() {
        val callback = networkCallback ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
        networkCallback = null
        lastTransport = NetworkTransport.UNKNOWN
        observedNetworks.clear()
    }

    /**
     * 派发网络变化事件。
     *
     * @param listener 上层监听器。
     * @param fallbackReason 默认原因。
     */
    private fun dispatch(
        listener: NetworkChangeListener,
        fallbackReason: String,
    ) {
        val current = resolveTransport()
        val previous = lastTransport
        lastTransport = current
        val reason = if (previous != NetworkTransport.UNKNOWN && previous != current) {
            "${previous.name.lowercase()}_to_${current.name.lowercase()}"
        } else {
            fallbackReason
        }
        listener.onNetworkChanged(
            NetworkChangeEvent(
                reason = reason,
                from = previous,
                to = current,
                wifiSsid = currentWifiSsid(),
            ),
        )
    }

    /**
     * 运行时实时读取当前 Wi-Fi SSID。
     *
     * 读取顺序：
     * 1. 实时查询当前底层网络能力并读取 transportInfo；
     * 2. 最后回退到 WifiManager.connectionInfo。
     *
     * 注意：
     * 不使用观察器缓存兜底，保证每次策略判断都基于实时系统状态。
     */
    fun currentWifiSsid(): String? {
        val liveCapabilities = resolveCurrentUnderlyingWifiCapabilitiesLive()
        val live = liveCapabilities?.wifiSsid()
        if (live != null) {
            return live
        }
        return sanitizeSsid(wifiManager.connectionInfo?.ssid)
    }

    /**
     * 返回当前观察到的底层网络传输类型快照。
     */
    fun currentTransport(): NetworkTransport = resolveTransport()

    /**
     * 解析当前网络传输类型。
     *
     * @return 当前传输类型。
     */
    private fun resolveTransport(): NetworkTransport {
        val capabilities = observedNetworks.values
            .minByOrNull { it.transportPriority() }
            ?: resolveActiveUnderlyingNetworkCapabilities()
            ?: return NetworkTransport.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.UNKNOWN
        }
    }

    /**
     * 构建非 VPN 底层网络请求。
     *
     * 说明：
     * - VPN 建立后默认网络可能变成 VPN 本身；
     * - 这里主动过滤 VPN，只让路由策略看到真实 Wi-Fi/蜂窝/以太网变化。
     */
    private fun buildUnderlyingNetworkRequest(): NetworkRequest {
        return NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
                }
            }
            .build()
    }

    /** 刷新指定网络的缓存能力。 */
    private fun updateObservedNetwork(network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities?.isUsableUnderlyingNetwork() == true) {
            observedNetworks[network] = capabilities
        } else {
            observedNetworks.remove(network)
        }
    }

    /** 回退读取当前默认网络，但排除 VPN。 */
    private fun resolveActiveUnderlyingNetworkCapabilities(): NetworkCapabilities? {
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else {
            null
        } ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        return capabilities.takeIf { it.isUsableUnderlyingNetwork() }
    }

    /** 实时查询当前底层 Wi-Fi 能力。 */
    private fun resolveCurrentUnderlyingWifiCapabilitiesLive(): NetworkCapabilities? {
        val active = resolveActiveUnderlyingNetworkCapabilities()
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        if (active != null) {
            return active
        }
        return connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
            .filter { it.isUsableUnderlyingNetwork() && it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            .minByOrNull { it.transportPriority() }
    }
}

/** 判断该网络是否适合作为 VPN 底层网络。 */
private fun NetworkCapabilities.isUsableUnderlyingNetwork(): Boolean {
    return hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/** 网络优先级：Wi-Fi/以太网优先，其次蜂窝，其他网络靠后。 */
private fun NetworkCapabilities.transportPriority(): Int {
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 10
    }
}

/** 从网络能力中读取未脱敏的 Wi-Fi SSID。 */
private fun NetworkCapabilities.wifiSsid(): String? {
    val wifiInfo = transportInfo as? WifiInfo ?: return null
    return sanitizeSsid(wifiInfo.ssid)
}

private fun sanitizeSsid(value: String?): String? {
    return value
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
}
