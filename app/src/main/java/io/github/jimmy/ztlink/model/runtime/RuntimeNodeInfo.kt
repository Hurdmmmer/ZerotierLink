package io.github.jimmy.ztlink.model.runtime

import io.github.jimmy.ztlink.model.network.NetworkId

/**
 * 节点查询结果。
 *
 * @property nodeId 节点 ID（无符号 Long）。
 * @property nodeIdHex 节点 ID（十六进制）。
 * @property online 节点是否在线。
 * @property version ZeroTier 版本字符串。
 * @property activeNetworkId 当前活动网络 ID。
 * @property joinedNetworkCount 当前已加入网络数量。
 */
data class RuntimeNodeInfo(
    val nodeId: Long,
    val nodeIdHex: String,
    val online: Boolean,
    val version: String?,
    val activeNetworkId: NetworkId?,
    val joinedNetworkCount: Int,
)

