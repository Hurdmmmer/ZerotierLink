package io.github.jimmy.ztlink.model.runtime

import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.service.ServiceError

/**
 * 当前 Node运行状态对象。
 *
 * @property nodeReady 节点是否就绪。
 * @property tunnelReady 隧道是否就绪。
 * @property vpnSocketReady VPN 套接字是否就绪。
 * @property activeNetworkId 当前活跃网络 ID。
 * @property monitorOnlyMode 是否处于仅监控模式。
 * @property lastError 最近一次错误。
 */
data class NodeState(
    val nodeReady: Boolean,
    val tunnelReady: Boolean,
    val vpnSocketReady: Boolean,
    val activeNetworkId: NetworkId?,
    val monitorOnlyMode: Boolean,
    val lastError: ServiceError?,
)
