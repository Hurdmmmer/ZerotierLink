package io.github.jimmy.ztlink.service.observer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
                dispatch(listener, "network_available")
            }

            override fun onLost(network: Network) {
                dispatch(listener, "network_lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                dispatch(listener, "network_capabilities_changed")
            }
        }
        networkCallback = callback
        lastTransport = resolveTransport()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder().build()
            connectivityManager.registerNetworkCallback(request, callback)
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
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else {
            null
        } ?: return NetworkTransport.NONE

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkTransport.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.UNKNOWN
        }
    }
}

