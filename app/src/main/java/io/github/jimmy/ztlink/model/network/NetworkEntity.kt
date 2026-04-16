package io.github.jimmy.ztlink.model.network

/**
 * 网络聚合实体。
 *
 * 用途：
 * - 在 Service / Repository / UseCase 间作为统一数据模型。
 */
data class NetworkEntity(
    /** 网络 ID。 */
    val networkId: NetworkId,
    /** 用户可见名称。 */
    val displayName: String = "",
    /** 是否启用该网络连接。 */
    val isEnabled: Boolean = true,
    /** 是否为最近一次激活网络。 */
    val lastActivated: Boolean = false,
    /** 网络配置。 */
    val config: NetworkConfigEntity = NetworkConfigEntity(
        routeViaZeroTier = false,
        dnsMode = NetworkDnsMode.NONE,
    ),
    /** 当前连接状态。 */
    val status: NetworkConnectionStatus = NetworkConnectionStatus.DISCONNECTED,
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
