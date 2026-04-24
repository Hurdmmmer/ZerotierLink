package io.github.jimmy.ztlink.app.ui.components.peers

import androidx.compose.runtime.Immutable

/**
 * Peer 角色类型。
 *
 * 说明：
 * 角色信息来自 ZeroTier SDK 的 peer role，用于卡片标签与摘要统计。
 */
@Immutable
enum class PeerRoleType {
    PLANET,
    MOON,
    LEAF,
    UNKNOWN,
}

/**
 * Peer 当前链路类型。
 *
 * 规则：
 * 存在可用地址视为直连，否则视为中继。
 */
@Immutable
enum class PeerPathType {
    DIRECT,
    RELAY,
}

/**
 * Peers 列表单项 UI 模型。
 *
 * @property peerId 节点地址（十六进制）字符串。
 * @property roleType 节点角色。
 * @property pathType 当前链路类型（直连/中继）。
 * @property endpoint 当前路径地址文本（可能为空）。
 * @property latencyMs 往返时延（毫秒）。
 * @property version 对端版本号文本。
 */
@Immutable
data class PeerListItem(
    val peerId: String,
    val roleType: PeerRoleType,
    val pathType: PeerPathType,
    val endpoint: String?,
    val latencyMs: Long?,
    val version: String?,
)

/**
 * Peers 页面摘要模型。
 *
 * 说明：
 * 1) 为顶部摘要卡片提供直接展示字段；
 * 2) rootServerIps 主要用于 Planet 根服务链路信息展示。
 */
@Immutable
data class PeerSummary(
    val totalCount: Int = 0,
    val directCount: Int = 0,
    val relayCount: Int = 0,
    val planetCount: Int = 0,
    val moonCount: Int = 0,
    val leafCount: Int = 0,
    val rootServerIps: List<String> = emptyList(),
) {
    val primaryRootServerIp: String?
        get() = rootServerIps.firstOrNull()
}
