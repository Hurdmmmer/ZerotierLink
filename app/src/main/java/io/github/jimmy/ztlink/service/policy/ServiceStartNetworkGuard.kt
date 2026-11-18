package io.github.jimmy.ztlink.service.policy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.data.settings.SettingsStore
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceErrorCode
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动/入网前的网络环境门禁。
 *
 * 目标：
 * 1. 对齐老项目的“无网络直接拦截”行为；
 * 2. 对齐“蜂窝网络开关只要关闭就禁止蜂窝启动”的行为；
 * 3. 将门禁逻辑集中到核心层，避免散落在 UI 层。
 */
@Singleton
class ServiceStartNetworkGuard @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val networkChangeObserver: NetworkChangeObserver,
) {

    /**
     * 校验当前网络环境是否允许启动/入网。
     *
     * 核心检查项：
     * 1. 是否有基础网络连接（Wi-Fi/数据）。
     * 2. 蜂窝网络权限合规性（遵循用户设置开关）。
     *
     * @return `null` 表示允许继续；非空表示应立即拦截并返回错误原因。
     */
    suspend fun validateStartOrJoin(): ServiceError? {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

        // 第一步：解析当前连接类型。
        // 说明：
        // 1. 优先结合 activeNetwork 与 NetworkChangeObserver 的底层真实网络判断；
        // 2. 在 Wi-Fi 切蜂窝的瞬态切换窗口（几十毫秒内 activeNetwork 可能短暂为 null）执行最多 2 次短重试，
        //    避免 NetworkChangeObserver 已识别到蜂窝网络时被误拦为“无可用网络”。
        var connectionType = resolveCurrentConnection(connectivityManager)
        var attempt = 0
        while (connectionType == CurrentConnection.NONE && attempt < TRANSIENT_RETRY_COUNT) {
            delay(TRANSIENT_RETRY_DELAY_MS)
            connectionType = resolveCurrentConnection(connectivityManager)
            attempt += 1
        }
        if (connectionType == CurrentConnection.NONE) {
            return ServiceError(
                code = ServiceErrorCode.VALIDATION_FAILED,
                message = appContext.getString(R.string.error_no_network_available),
                recoverable = true,
            )
        }

        // 第三步：蜂窝网络（移动数据）合规性检查
        // 从设置中读取“允许使用蜂窝网络”的开关状态
        val useCellularData = settingsStore.readState(forceDisableCustomPlanet = false).useCellularData
        // 判断当前连接是否为蜂窝网络（如 4G/5G）
        val isCellular = connectionType == CurrentConnection.CELLULAR

        // 如果当前是移动数据，但用户在设置中关闭了移动数据连接开关，则拦截
        if (isCellular && !useCellularData) {
            return ServiceError(
                code = ServiceErrorCode.VALIDATION_FAILED,
                message = appContext.getString(R.string.error_cellular_disabled_by_settings),
                recoverable = true,
            )
        }

        // 所有检查通过，允许继续执行启动或入网操作
        return null
    }

    /**
     * 解析当前连接类型。
     *
     * 说明：
     * 1. 优先使用 [NetworkChangeObserver] 过滤掉 VPN 后的真实底层传输类型；
     * 2. 若未命中则回退检查 `activeNetwork`（跳过自身 VPN 传输）；
     * 3. 避免在 Wi-Fi -> 蜂窝切换瞬态或 VPN 已建立时误判为无网络。
     */
    private fun resolveCurrentConnection(
        connectivityManager: ConnectivityManager,
    ): CurrentConnection {
        when (networkChangeObserver.currentTransport()) {
            NetworkTransport.CELLULAR -> return CurrentConnection.CELLULAR
            NetworkTransport.WIFI,
            NetworkTransport.ETHERNET,
            -> return CurrentConnection.OTHER
            else -> Unit
        }

        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
        }
        val activeConnection = activeCapabilities?.toCurrentConnection()
        return activeConnection ?: CurrentConnection.NONE
    }

    private companion object {
        const val TRANSIENT_RETRY_COUNT: Int = 3
        const val TRANSIENT_RETRY_DELAY_MS: Long = 150L
    }
}

/**
 * 当前网络连接类型。
 */
private enum class CurrentConnection {
    /** 无可用连接 */
    NONE,

    /** 蜂窝网络 */
    CELLULAR,

    /** 其他网络（Wi-Fi/以太网等） */
    OTHER,
}

/**
 * 将网络能力映射为连接类型。
 */
private fun NetworkCapabilities.toCurrentConnection(): CurrentConnection {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return CurrentConnection.NONE
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        return CurrentConnection.CELLULAR
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    ) {
        return CurrentConnection.OTHER
    }
    return CurrentConnection.OTHER
}
