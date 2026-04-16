package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 设置界面展示的强调色预设。
 *
 * 设计原则：每个 AccentPreset 对应一套完整的 AccentPalette，
 * 由 materialkolor 算法以 primary 为 seed 生成完整 MD3 色盘，
 * 其余字段用于装饰层（光晕、渐变）而非 colorScheme。
 */
enum class AccentPreset {
    CLASSIC_BLUE,
    CYAN_TEAL,
    SUNSET_ORANGE,
    EMERALD_GREEN,
    ROSE_RED,
    INDIGO_SLATE,
    AMBER_GOLD,
    LIME_MINT,
    VIBRANT_PURPLE
}

/**
 * 单个强调色预设的完整调色板。
 *
 * 注意：topGlow / bottomGlow / dialogGlow 均为纯色（不含 alpha），
 * 透明度由 [buildBackgroundTokens] 根据深浅模式统一注入，避免双重叠加。
 *
 * @param primary        MD3 seed 色，同时作为品牌主色
 * @param primaryAlt     备选主色，用于渐变的第二锚点
 * @param primaryDark    深色模式下的亮化主色，用于深色背景上的文字/图标着色
 * @param containerLight 浅色模式容器背景（chip、badge 等）
 * @param containerDark  深色模式容器背景
 * @param topGlow        顶部氛围光晕的纯色锚点
 * @param bottomGlow     底部氛围光晕的纯色锚点
 * @param dialogGlow     弹窗背板光晕的纯色锚点
 */
@Immutable
data class AccentPalette(
    val primary: Color,
    val primaryAlt: Color,
    val primaryDark: Color,
    val containerLight: Color,
    val containerDark: Color,
    val topGlow: Color,
    val bottomGlow: Color,
    val dialogGlow: Color,
)

/**
 * 根据预设返回对应的调色板。
 *
 * 取色策略：
 * - primary 选取中等饱和度、适合算法生成的色相锚点
 * - topGlow 倾向与 primary 同色相，视觉上统一品牌氛围
 * - bottomGlow 可轻微偏移色相（互补/类似色），增加底部空间感
 * - dialogGlow 与 primary 保持同色相，强化弹窗的品牌归属感
 */
fun accentPaletteOf(preset: AccentPreset): AccentPalette = when (preset) {

    AccentPreset.CLASSIC_BLUE -> AccentPalette(
        primary        = Color(0xFF1A6FEB),
        primaryAlt     = Color(0xFF3D8BFF),
        primaryDark    = Color(0xFF82AEFF),
        containerLight = Color(0xFFD8E8FF),
        containerDark  = Color(0xFF0D2850),
        topGlow        = Color(0xFF1A6FEB),   // 纯色，alpha 由 buildBackgroundTokens 控制
        bottomGlow     = Color(0xFF18A875),   // 偏移至青绿，底部落地感
        dialogGlow     = Color(0xFF1A6FEB),
    )

    AccentPreset.CYAN_TEAL -> AccentPalette(
        primary        = Color(0xFF0A9E9D),
        primaryAlt     = Color(0xFF14C8D4),
        primaryDark    = Color(0xFF5DD8D7),
        containerLight = Color(0xFFCCF5F5),
        containerDark  = Color(0xFF083840),
        topGlow        = Color(0xFF0A9E9D),
        bottomGlow     = Color(0xFF0A6E9D),   // 偏蓝，增加深度
        dialogGlow     = Color(0xFF0A9E9D),
    )

    AccentPreset.SUNSET_ORANGE -> AccentPalette(
        primary        = Color(0xFFD95430),
        primaryAlt     = Color(0xFFFF7043),
        primaryDark    = Color(0xFFFF9C7A),
        containerLight = Color(0xFFFFDDD4),
        containerDark  = Color(0xFF552214),
        topGlow        = Color(0xFFD95430),
        bottomGlow     = Color(0xFFCC7B18),   // 偏琥珀，暖色落地
        dialogGlow     = Color(0xFFD95430),
    )

    AccentPreset.EMERALD_GREEN -> AccentPalette(
        primary        = Color(0xFF1A9B68),
        primaryAlt     = Color(0xFF28C27F),
        primaryDark    = Color(0xFF5ED9A5),
        containerLight = Color(0xFFD8F5E8),
        containerDark  = Color(0xFF0C3828),
        topGlow        = Color(0xFF1A9B68),
        bottomGlow     = Color(0xFF1A7A9B),   // 偏青蓝，清凉感
        dialogGlow     = Color(0xFF1A9B68),
    )

    AccentPreset.ROSE_RED -> AccentPalette(
        primary        = Color(0xFFD04060),
        primaryAlt     = Color(0xFFE8607A),
        primaryDark    = Color(0xFFFF96AD),
        containerLight = Color(0xFFFFD8DF),
        containerDark  = Color(0xFF541828),
        topGlow        = Color(0xFFD04060),
        bottomGlow     = Color(0xFFD06040),   // 偏珊瑚，柔和落地
        dialogGlow     = Color(0xFFD04060),
    )

    AccentPreset.INDIGO_SLATE -> AccentPalette(
        primary        = Color(0xFF4458D4),
        primaryAlt     = Color(0xFF6678F0),
        primaryDark    = Color(0xFF9AABFF),
        containerLight = Color(0xFFDCE2FF),
        containerDark  = Color(0xFF1C2458),
        topGlow        = Color(0xFF4458D4),
        bottomGlow     = Color(0xFF6044D4),   // 偏紫，层次感
        dialogGlow     = Color(0xFF4458D4),
    )

    AccentPreset.AMBER_GOLD -> AccentPalette(
        primary        = Color(0xFFC4841A),
        primaryAlt     = Color(0xFFE8A830),
        primaryDark    = Color(0xFFFFBF60),
        containerLight = Color(0xFFFFE8C4),
        containerDark  = Color(0xFF543810),
        topGlow        = Color(0xFFC4841A),
        bottomGlow     = Color(0xFFB85C1A),   // 偏棕橙，沉稳落地
        dialogGlow     = Color(0xFFC4841A),
    )

    AccentPreset.LIME_MINT -> AccentPalette(
        primary        = Color(0xFF54A028),
        primaryAlt     = Color(0xFF78C040),
        primaryDark    = Color(0xFFAADE70),
        containerLight = Color(0xFFE4F5D4),
        containerDark  = Color(0xFF264814),
        topGlow        = Color(0xFF54A028),
        bottomGlow     = Color(0xFF28A068),   // 偏翠绿，清新感
        dialogGlow     = Color(0xFF54A028),
    )

    AccentPreset.VIBRANT_PURPLE -> AccentPalette(
        primary        = Color(0xFF7C4DF0),
        primaryAlt     = Color(0xFF9E78FA),
        primaryDark    = Color(0xFFBFAAFF),
        containerLight = Color(0xFFEEE4FF),
        containerDark  = Color(0xFF281848),
        topGlow        = Color(0xFF7C4DF0),
        bottomGlow     = Color(0xFF4D50D4),   // 偏靛蓝，增加神秘感
        dialogGlow     = Color(0xFF7C4DF0),
    )
}