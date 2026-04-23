package io.github.jimmy.ztlink.model.runtime

import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.model.network.NetworkId

/**
 * 单个网络的 runtime 配置对象。
 *
 * @property networkId 网络 ID。
 * @property name 网络名称（控制器下发，可能为空）。
 * @property status 连接状态。
 * @property assignedIps 已分配 IP 列表。
 * @property dnsServers DNS 列表。
 * @property mac 节点 MAC。
 * @property mtu MTU 值。
 * @property broadcastEnabled 是否开启广播。
 * @property bridgingEnabled 是否开启桥接。
 */
data class RuntimeNetworkInfo (
    val networkId: NetworkId,
    val name: String?,
    val status: NetworkStatusEnum,
    val assignedIps: List<String>,
    val dnsServers: List<String>,
    val mac: String,
    val mtu: Int?,
    val broadcastEnabled: Boolean,
    val bridgingEnabled: Boolean,
)
