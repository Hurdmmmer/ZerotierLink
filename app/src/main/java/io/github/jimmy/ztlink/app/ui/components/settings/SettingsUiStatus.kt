package io.github.jimmy.ztlink.app.ui.components.settings

import androidx.compose.runtime.Immutable
import io.github.jimmy.ztlink.app.ui.theme.AccentPreset

/**
 * 主题模式
 */
enum class ThemeMode {
    /** 跟随系统 */
    SYSTEM,
    /** 浅色 */
    LIGHT,
    /** 深色 */
    DARK
}

/**
 * Planet 来源类型。
 */
enum class PlanetSourceType {
    /** 尚未配置来源。 */
    NONE,
    /** 使用本地文件作为来源。 */
    FILE,
    /** 使用 URL 下载作为来源。 */
    URL
}

/**
 * 用户可选的主题设置。
 */
@Immutable
data class ThemeSettings(
    /** 主题模式（系统默认/浅色/深色） */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否启用动态配色（仅支持 Android 12+） */
    val dynamicColor: Boolean = false,
    /** 预设的强调色 */
    val accentPreset: AccentPreset = AccentPreset.CLASSIC_BLUE
)

/**
 * 设置页 UI 状态模型。
 *
 * 说明：
 * 1. 该模型只服务于“页面展示与交互控制”，不直接承担持久化职责。
 * 2. 字段以“页面可见配置 + 页面联动状态”为核心，便于 Compose 直接渲染。
 * 3. 后续可由 ViewModel 将 domain/data 层的数据映射为本对象。
 */
data class SettingsUiState(

    /**
     * 当前强调色预设。
     * 当 dynamicColor = true 时，仅用于展示当前值，不一定可编辑。
     */
    val themeSettings: ThemeSettings = ThemeSettings(),

    /**
     * 是否开机启动 ZeroTier 服务。
     * 该开关属于策略配置，实际生效时机为设备重启后。
     */
    val startOnBoot: Boolean = true,

    /**
     * 是否启用自定义 Planet。
     * 关闭时，Planet 相关子设置应处于不可编辑状态。
     */
    val planetUseCustom: Boolean = false,

    /**
     * 是否启用“内网自动路由探测”。
     * 仅在 planetUseCustom = true 时允许编辑。
     */
    val planetAutoRouteCheck: Boolean = false,

    /**
     * 内网探测WIFI SSID。
     * 允许为空。
     */
    val probeWifiSsid: String = "",

    /**
     * Planet 来源类型（文件 / URL / 未配置）。
     */
    val planetSourceType: PlanetSourceType = PlanetSourceType.NONE,

    /**
     * Planet 来源展示文本（例如文件名或 URL）。
     */
    val planetSourceDisplay: String = "",

    /**
     * Planet 来源原始值（例如文件 Uri 或 URL）。
     */
    val planetSourceRawValue: String = "",

    /**
     * 是否允许蜂窝网络下建立连接。
     * false 时，在移动网络场景应阻断连接并提示用户。
     */
    val useCellularData: Boolean = false,

    /**
     * 是否禁用 IPv6。
     * 通常需要“断开并重连”后，隧道配置才会完全生效。
     */
    val disableIpv6: Boolean = false,

    /**
     * 是否不再提示“通知权限未开启”提醒。
     * 该项通常不在设置页显式展示，但可作为统一状态的一部分保留。
     */
    val disableNoNotificationAlert: Boolean = false
) {
    /**
     * Planet 文件相关入口是否可用。
     * 规则：仅当启用自定义 Planet 时可用。
     */
    val planetFileActionsEnabled: Boolean
        get() = planetUseCustom

    /**
     * 探测 IP 输入是否可用。
     * 规则：必须同时启用自定义 Planet 与自动路由探测。
     */
    val planetProbeIpEnabled: Boolean
        get() = planetUseCustom && planetAutoRouteCheck
}
