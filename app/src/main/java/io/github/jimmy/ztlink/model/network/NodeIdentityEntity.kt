package io.github.jimmy.ztlink.model.network

/**
 * 本机 ZeroTier 节点身份快照。
 */
data class NodeIdentityEntity(
    /** 节点 ID（数值形态）。 */
    val nodeId: Long,
    /** 节点 ID（十六进制字符串）。 */
    val nodeIdHex: String,
)
