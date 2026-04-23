package io.github.jimmy.ztlink.model.network

import com.zerotier.sdk.VirtualNetworkStatus
import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum

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
    fun fromVirtualNetworkStatus(status: VirtualNetworkStatus?): NetworkStatusEnum {
        return when (status) {
            VirtualNetworkStatus.NETWORK_STATUS_REQUESTING_CONFIGURATION ->
                NetworkStatusEnum.REQUESTING_CONFIGURATION
            VirtualNetworkStatus.NETWORK_STATUS_OK ->
                NetworkStatusEnum.OK
            VirtualNetworkStatus.NETWORK_STATUS_ACCESS_DENIED ->
                NetworkStatusEnum.ACCESS_DENIED
            VirtualNetworkStatus.NETWORK_STATUS_NOT_FOUND ->
                NetworkStatusEnum.NOT_FOUND
            VirtualNetworkStatus.NETWORK_STATUS_PORT_ERROR ->
                NetworkStatusEnum.PORT_ERROR
            VirtualNetworkStatus.NETWORK_STATUS_CLIENT_TOO_OLD ->
                NetworkStatusEnum.CLIENT_TOO_OLD
            VirtualNetworkStatus.NETWORK_STATUS_AUTHENTICATION_REQUIRED ->
                NetworkStatusEnum.AUTHENTICATION_REQUIRED
            null -> NetworkStatusEnum.UNKNOWN
        }
    }
}

