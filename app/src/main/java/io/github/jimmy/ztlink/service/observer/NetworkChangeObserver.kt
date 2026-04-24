package io.github.jimmy.ztlink.service.observer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateObservedNetwork(network)
                dispatch(listener, "network_available")
            }

            override fun onLost(network: Network) {
                observedNetworks.remove(network)
                dispatch(listener, "network_lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.isUsableUnderlyingNetwork()) {
                    observedNetworks[network] = networkCapabilities
                } else {
                    observedNetworks.remove(network)
                }
                dispatch(listener, "network_capabilities_changed")
            }
        }
        networkCallback = callback
        lastTransport = resolveTransport()
        connectivityManager.registerNetworkCallback(buildUnderlyingNetworkRequest(), callback)
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
            ),
        )
    }

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
