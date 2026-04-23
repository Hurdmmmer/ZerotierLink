package io.github.jimmy.ztlink.model.runtime

import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.service.AppWhitelistConfig

/**
 * 隧道重配置请求。
 *
 * @property networkId 目标网络 ID。
 * @property routeViaZeroTier 是否通过 ZeroTier 下发默认路由。
 * @property dnsMode DNS 模式。
 * @property customDnsServers 自定义 DNS 列表。
 * @property whitelistPackages 应用白名单包名列表（这些 App 会自动跳过转发）。
 * @property includeBuiltInWhitelistPackages 是否附加内置白名单包名。
 * @property reason 触发原因。
 */
data class VpnTunnelConfig(
    val networkId: NetworkId,
    val routeViaZeroTier: Boolean,
    val dnsMode: NetworkDnsModeEnum,
    val customDnsServers: List<String>,
    val whitelistPackages: List<String> = emptyList(),
    val includeBuiltInWhitelistPackages: Boolean = true,
    val reason: String,
) {
    /**
     * Runtime 隧道重配置请求 -> 应用白名单配置映射。
     */
    fun toAppWhitelistConfig(): AppWhitelistConfig {
        return AppWhitelistConfig(
            userWhitelistPackages = whitelistPackages,
            includeBuiltInWhitelistPackages = includeBuiltInWhitelistPackages,
        )
    }
}
