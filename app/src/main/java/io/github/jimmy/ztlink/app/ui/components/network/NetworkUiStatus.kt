// NetworkUiModels.kt
package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.runtime.Immutable

/** 网络连接状态 */
enum class NetworkStatus {
    CONNECTED,      // 已连接（绿色）
    REQUESTING_CONFIGURATION, // 正在请求配置（等待控制器授权前置态）
    AUTHENTICATION_REQUIRED,  // 需要控制器授权（授权等待态）
    DISCONNECTED,   // 已断开（灰色）
    ACCESS_DENIED,  // 无访问权限（红色）
    NOT_FOUND,      // 网络不存在（红色）
}

/** DNS 模式 */
enum class DnsMode { NONE, NETWORK, CUSTOM }

/** 列表页单条网络卡片数据 */
@Immutable
data class NetworkListItem(
    val networkId: String,
    val name: String,
    val status: NetworkStatus,
    val isEnabled: Boolean,         // 开关状态
    val isLan: Boolean,             // 是否内网
    val p2pSummary: String,         // 如 "3 peers direct"
    val assignedIps: List<String>,  // 分配的 IP 列表
)

/** 详情页合并数据 */
@Immutable
data class NetworkDetail(
    val networkId: String,
    val name: String,
    val status: NetworkStatus,
    val type: String,               // Public / Private
    val mac: String,
    val mtu: Int,
    val broadcastEnabled: Boolean,
    val bridgingEnabled: Boolean,
    val assignedIps: List<String>,
    val dnsServers: List<String>,
    val dnsMode: Enum<*>,
    val defaultRoute: Boolean,
    val isLan: Boolean,
)
