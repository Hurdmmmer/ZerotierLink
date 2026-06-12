package io.github.jimmy.ztlink.service.observer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * @property reason 触发原因（仅用于 Wi-Fi/蜂窝切换）。
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
 * 约束：
 * 1. 只产出 Wi-Fi <-> 蜂窝 两类切换事件；
 * 2. 忽略 capabilities/linkProperties 的同传输抖动；
 * 3. 忽略 NONE/UNKNOWN/VPN 过渡，避免内核被噪声事件反复启停。
 */
@Singleton
class NetworkChangeObserver @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /** 系统连接管理器。 */
    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)

    /** 系统 Wi-Fi 管理器。 */
    private val wifiManager: WifiManager =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    /** 当前回调实例。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 最近一次观察到的传输类型。 */
    @Volatile
    private var lastTransport: NetworkTransport = NetworkTransport.UNKNOWN

    /**
     * 最近一次向上层派发的 WiFi IPv4 地址（用于检测 DHCP 完成补偿）。
     *
     * 说明：onAvailable 先于 DHCP 触发，IP 可能为 null；DHCP 完成后系统
     * 通过 onLinkPropertiesChanged 通知，此时需要补偿一次策略检查。
     */
    @Volatile
    private var lastDispatchedWifiIpv4: String? = null

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
        lastTransport = currentTransport()
        val callback = buildNetworkCallback(listener)
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
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
        lastDispatchedWifiIpv4 = null
    }

    /**
     * 构建系统网络回调。
     *
     * 说明：
     * - 只要默认网络有变化就触发一次”重新判定是否发生 Wi-Fi/蜂窝切换”；
     * - 不直接使用回调参数中的 IP，避免把 VPN/TUN 的地址误判为 Wi-Fi IP；
     * - onLinkPropertiesChanged 额外用于捕获 DHCP 完成：onAvailable 先于 IP
     *   分配触发，此时 IP 为 0；IP 分配后系统触发 onLinkPropertiesChanged，
     *   需要派发补偿事件以触发内网策略重检。
     */
    private fun buildNetworkCallback(listener: NetworkChangeListener): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchIfWifiCellularSwitched(listener)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                dispatchIfWifiCellularSwitched(listener)
            }

            override fun onLost(network: Network) {
                dispatchIfWifiCellularSwitched(listener)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) {
                dispatchIfWifiIpAssigned(listener)
            }
        }
    }

    /**
     * 在网络接口切换到 Wi-Fi 或蜂窝时派发事件。
     *
     * 修复说明：
     * 原逻辑仅识别"直接 WiFi↔蜂窝"切换，忽略了 Android 切换时常见的
     * WiFi→NONE→蜂窝（或反向）中间过渡。此处改为：只要当前网络变为
     * WiFi 或蜂窝（即有真实物理接口可用），就派发事件，让上层重建 UDP Socket。
     */
    private fun dispatchIfWifiCellularSwitched(listener: NetworkChangeListener) {
        val previous = lastTransport
        val current = currentTransport()
        if (previous == current) {
            return
        }
        lastTransport = current
        if (current != NetworkTransport.WIFI && current != NetworkTransport.CELLULAR) {
            return
        }
        val wifiInfo = if (current == NetworkTransport.WIFI) currentWifiIpv4Info() else null
        lastDispatchedWifiIpv4 = wifiInfo?.address
        val reason = "${previous.name.lowercase()}_to_${current.name.lowercase()}"
        android.util.Log.d(
            "NetworkChangeObserver",
            "Network change: $reason, from=$previous, to=$current, ip=${wifiInfo?.address},prefix=${wifiInfo?.prefixLength}",
        )
        listener.onNetworkChanged(
            NetworkChangeEvent(
                reason = reason,
                from = previous,
                to = current,
                wifiIpv4 = wifiInfo?.address,
                wifiPrefixLength = wifiInfo?.prefixLength,
            ),
        )
    }

    /**
     * DHCP 完成补偿：当 WiFi IP 从无到有时，派发策略补偿事件。
     *
     * 说明：
     * 1. onAvailable 触发时 IP 可能尚未分配（rawIp==0），此时内网策略检查
     *    无法判断是否命中，需要在 IP 分配后（onLinkPropertiesChanged）补检一次；
     * 2. 补偿事件 from=WIFI, to=WIFI，用于标记"仅策略重检，不需重建 Socket"；
     * 3. 若 IP 未改变则忽略，防止 onLinkPropertiesChanged 频繁触发时产生噪声。
     */
    private fun dispatchIfWifiIpAssigned(listener: NetworkChangeListener) {
        if (currentTransport() != NetworkTransport.WIFI) {
            return
        }
        val wifiInfo = currentWifiIpv4Info() ?: return
        val previousIp = lastDispatchedWifiIpv4
        if (wifiInfo.address == previousIp) {
            return
        }
        lastDispatchedWifiIpv4 = wifiInfo.address
        android.util.Log.d(
            "NetworkChangeObserver",
            "WiFi IP assigned/changed: prev=$previousIp new=${wifiInfo.address}/${wifiInfo.prefixLength}",
        )
        listener.onNetworkChanged(
            NetworkChangeEvent(
                reason = "wifi_ip_assigned",
                from = NetworkTransport.WIFI,
                to = NetworkTransport.WIFI,
                wifiIpv4 = wifiInfo.address,
                wifiPrefixLength = wifiInfo.prefixLength,
            ),
        )
    }

    /**
     * 读取当前 Wi-Fi IPv4 文本。
     */
    fun currentWifiIpv4Address(): String? = currentWifiIpv4Info()?.address

    /**
     * 读取当前 Wi-Fi IPv4 信息。
     *
     * 说明：
     * 1. 仅在当前传输为 Wi-Fi 时返回；
     * 2. 只从系统 Wi-Fi 信息读取地址，明确排除 VPN/TUN 虚拟网卡地址。
     */
    @Suppress("DEPRECATION")
    fun currentWifiIpv4Info(
        observedNetwork: Network? = null,
        observedCapabilities: NetworkCapabilities? = null,
        observedLinkProperties: android.net.LinkProperties? = null,
        preferObservedNetwork: Boolean = true,
    ): WifiIpv4Info? {
        if (currentTransport() != NetworkTransport.WIFI) {
            return null
        }
        val wifiInfo = wifiManager.connectionInfo ?: return null
        val rawIp = wifiInfo.ipAddress
        if (rawIp == 0) {
            return null
        }
        val address = formatLittleEndianIpv4(rawIp)
        val prefixLength = resolveWifiPrefixLength()
        return WifiIpv4Info(
            address = address,
            prefixLength = prefixLength,
        )
    }

    /**
     * 返回当前观察到的传输类型。
     *
     * 说明：
     * 1. 优先通过 activeNetworkInfo 获取当前“对应用可见”的默认网络类型；
     * 2. 仅区分 Wi-Fi 与蜂窝，其他统一视为 NONE（策略层不处理）。
     */
    @Suppress("DEPRECATION")
    fun currentTransport(): NetworkTransport {
        val activeInfo = connectivityManager.activeNetworkInfo
        if (activeInfo != null && activeInfo.isConnected) {
            return when (activeInfo.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkTransport.WIFI
                ConnectivityManager.TYPE_MOBILE -> NetworkTransport.CELLULAR
                else -> NetworkTransport.NONE
            }
        }
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkTransport.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkTransport.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.NONE
        }
    }

    /**
     * 解析 Wi-Fi 前缀长度。
     *
     * 说明：
     * 1. 先用 DHCP netmask；
     * 2. 失败则回退 /24，满足当前“同网段判断”需求。
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
}
