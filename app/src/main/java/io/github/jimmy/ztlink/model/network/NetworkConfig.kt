package io.github.jimmy.ztlink.model.network

import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum

/**
 * 应用侧持久化的网络配置实体。
 */
data class NetworkConfig(
    /** 是否将默认路由指向 ZeroTier。 */
    val routeViaZeroTier: Boolean,
    /** DNS 模式。 */
    val dnsMode: NetworkDnsModeEnum,
    /** 自定义 DNS 地址（仅在 CUSTOM 模式下生效）。 */
    val customDns: String = "",
)
