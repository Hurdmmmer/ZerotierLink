package io.github.jimmy.ztlink.service.policy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.data.settings.SettingsStore
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceErrorCode
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
        // 1. 优先按 activeNetwork 判定；
        // 2. 启动瞬时 activeNetwork 为空时，回退扫描 allNetworks；
        // 3. 回退扫描时优先判定 OTHER（Wi-Fi/以太网），避免误选蜂窝导致误拦截。
        val connectionType = resolveCurrentConnection(connectivityManager)
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
     * 1. activeNetwork 命中时直接判定；
     * 2. activeNetwork 缺失时，回退 allNetworks 扫描；
     * 3. allNetworks 结果按 OTHER > CELLULAR > NONE 优先级归并。
     */
    private fun resolveCurrentConnection(
        connectivityManager: ConnectivityManager,
    ): CurrentConnection {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
        }
        val activeConnection = activeCapabilities?.toCurrentConnection()
        if (activeConnection != null) {
            return activeConnection
        }

        // 仅在 activeNetwork 不可用时走回退扫描。
        // allNetworks 在此场景下是必要兜底，属于稳定设计的一部分。
        @Suppress("DEPRECATION")
        val fallbackConnections = connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { network -> connectivityManager.getNetworkCapabilities(network) }
            .map { capabilities -> capabilities.toCurrentConnection() }
            .toList()

        return when {
            fallbackConnections.any { it == CurrentConnection.OTHER } -> CurrentConnection.OTHER
            fallbackConnections.any { it == CurrentConnection.CELLULAR } -> CurrentConnection.CELLULAR
            else -> CurrentConnection.NONE
        }
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
