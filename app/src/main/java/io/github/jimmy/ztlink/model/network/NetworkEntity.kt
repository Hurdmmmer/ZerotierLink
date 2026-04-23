package io.github.jimmy.ztlink.model.network

import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum

/**
 * 网络聚合实体。
 *
 * 用途：
 * - 在 Service / Repository / UI 状态同步链路中作为统一数据模型。
 */
data class NetworkEntity(
    /** 网络 ID。 */
    val networkId: NetworkId,
    /** 用户可见名称。 */
    val displayName: String = "",
    /**
     * 是否启用该网络连接（历史持久字段）。
     *
     * 说明：
     * - 新链路下开关状态以运行态内存为准，不依赖该字段；
     * - 保留该字段主要用于兼容旧数据结构。
     */
    val isEnabled: Boolean = false,
    /** 是否为最近一次激活网络。 */
    val lastActivated: Boolean = false,
    /** 网络配置。 */
    val config: NetworkConfig = NetworkConfig(
        routeViaZeroTier = false,
        dnsMode = NetworkDnsModeEnum.NONE,
    ),
    /** 当前连接状态。 */
    val status: NetworkStatusEnum = NetworkStatusEnum.DISCONNECTED,
    /** 分配到的 IP 列表。 */
    val assignedIps: List<String> = emptyList(),
    /** 下发的 DNS 服务器列表。 */
    val dnsServers: List<String> = emptyList(),
    /** 虚拟网卡 MAC 地址。 */
    val mac: String = "",
    /** MTU 值。 */
    val mtu: Int? = null,
    /** 是否启用广播。 */
    val broadcastEnabled: Boolean = false,
    /** 是否启用桥接。 */
    val bridgingEnabled: Boolean = false,
)
