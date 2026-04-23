package io.github.jimmy.ztlink.model.network

import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum

/**
 * 加入网络用例的输入参数契约。
 */
data class JoinNetwork(
    /** 目标网络 ID。 */
    val networkId: NetworkId,
    /** 是否通过 ZeroTier 下发默认路由。 */
    val routeViaZeroTier: Boolean,
    /** DNS 模式。 */
    val dnsMode: NetworkDnsModeEnum,
    /** 自定义 DNS 值（仅 CUSTOM 模式使用）。 */
    val customDns: String = "",
    /** 网络显示名称（可为空）。 */
    val displayName: String = "",
)
