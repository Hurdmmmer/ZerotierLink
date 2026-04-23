package io.github.jimmy.ztlink.model.runtime

/**
 * 单个 Peer 的对象。
 *
 * @property peerId Peer 标识。
 * @property role 节点角色文本。
 * @property address 对端地址文本。
 * @property latencyMs 延迟（毫秒）。
 * @property version 版本文本。
 */
data class RuntimePeerInfo(
    val peerId: String,
    val role: String,
    val address: String?,
    val latencyMs: Long?,
    val version: String?,
)
