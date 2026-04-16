package io.github.jimmy.ztlink.model.network

import com.zerotier.sdk.VirtualNetworkStatus

/**
 * ZeroTier SDK 状态映射工具。
 *
 * 说明：
 * - 这里负责把 SDK 状态统一映射到领域层状态，避免业务层到处写分支。
 */
object ZeroTierStatusMapper {

    /**
     * 将 SDK 虚拟网络状态映射为领域状态。
     */
    fun fromVirtualNetworkStatus(status: VirtualNetworkStatus?): NetworkConnectionStatus {
        return when (status) {
            VirtualNetworkStatus.NETWORK_STATUS_REQUESTING_CONFIGURATION ->
                NetworkConnectionStatus.REQUESTING_CONFIGURATION
            VirtualNetworkStatus.NETWORK_STATUS_OK ->
                NetworkConnectionStatus.OK
            VirtualNetworkStatus.NETWORK_STATUS_ACCESS_DENIED ->
                NetworkConnectionStatus.ACCESS_DENIED
            VirtualNetworkStatus.NETWORK_STATUS_NOT_FOUND ->
                NetworkConnectionStatus.NOT_FOUND
            VirtualNetworkStatus.NETWORK_STATUS_PORT_ERROR ->
                NetworkConnectionStatus.PORT_ERROR
            VirtualNetworkStatus.NETWORK_STATUS_CLIENT_TOO_OLD ->
                NetworkConnectionStatus.CLIENT_TOO_OLD
            VirtualNetworkStatus.NETWORK_STATUS_AUTHENTICATION_REQUIRED ->
                NetworkConnectionStatus.AUTHENTICATION_REQUIRED
            null -> NetworkConnectionStatus.UNKNOWN
        }
    }
}

