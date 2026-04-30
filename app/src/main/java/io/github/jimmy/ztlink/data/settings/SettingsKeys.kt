package io.github.jimmy.ztlink.data.settings

/**
 * 设置模块统一 key 常量。
 *
 * 说明：
 * 1. 所有 key 放在一处，避免字符串散落导致拼写错误。
 * 2. 命名尽量与旧项目保持一致，便于迁移与排查。
 */
object SettingsKeys {
    /** SharedPreferences 文件名。 */
    const val PREFS_NAME = "ztlink_settings"

    /** 主题模式：SYSTEM / LIGHT / DARK。 */
    const val THEME_MODE = "theme_mode"
    /** 是否启用动态配色。 */
    const val DYNAMIC_COLOR = "dynamic_color"
    /** 强调色预设。 */
    const val ACCENT_PRESET = "accent_preset"

    /** 开机启动。 */
    const val START_ON_BOOT = "general_start_zerotier_on_boot"
    /** 最近一次已处理开机自启的 bootCount。 */
    const val START_ON_BOOT_LAST_HANDLED_BOOT_COUNT = "general_start_on_boot_last_handled_boot_count"
    /** 是否启用自定义 planet。 */
    const val PLANET_USE_CUSTOM = "planet_use_custom"
    /** 是否启用自动路由探测。 */
    const val PLANET_AUTO_ROUTE_CHECK = "planet_auto_route_check"
    /** 用于探测内网环境的固定 IP。 */
    const val PLANET_INTRANET_PROBE_IP = "planet_intranet_probe_ip"
    /** planet 来源类型。 */
    const val PLANET_SOURCE_TYPE = "planet_source_type"
    /** planet 来源展示值（文件名或 URL）。 */
    const val PLANET_SOURCE_DISPLAY = "planet_source_display"
    /** planet 来源原始值（Uri 或 URL）。 */
    const val PLANET_SOURCE_RAW_VALUE = "planet_source_raw_value"

    /** 是否允许蜂窝数据。 */
    const val NETWORK_USE_CELLULAR_DATA = "network_use_cellular_data"
    /** 是否禁用 IPv6。 */
    const val NETWORK_DISABLE_IPV6 = "network_disable_ipv6"
    /** 应用白名单（自动跳过该 App 转发）的包名列表。 */
    const val NETWORK_WHITELIST_APP_PACKAGES = "network_bypass_app_packages"
    /** 是否不再提示通知权限提醒。 */
    const val DISABLE_NO_NOTIFICATION_ALERT = "disable_no_notification_alert"
}
