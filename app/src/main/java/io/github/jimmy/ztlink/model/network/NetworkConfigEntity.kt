package io.github.jimmy.ztlink.model.network

/**
 * 应用侧持久化的网络配置实体。
 */
data class NetworkConfigEntity(
    /** 是否将默认路由指向 ZeroTier。 */
    val routeViaZeroTier: Boolean,
    /** DNS 模式。 */
    val dnsMode: NetworkDnsMode,
    /** 自定义 DNS 地址（仅在 CUSTOM 模式下生效）。 */
    val customDns: String = "",
)
