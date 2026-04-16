package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 背景与氛围层令牌。
 *
 * 层次设计（从下到上）：
 *   baseColor          → MD3 colorScheme.background，深浅模式自动切换
 *   topGlowBrush       → 顶部品牌色轻晕，高度约 200dp，引导视线向下
 *   bottomGlowBrush    → 底部径向晕，与顶部夹持内容区，避免页面"下沉"感
 *   dialogGlowBrush    → 弹窗专属，小半径径向晕，营造浮起感
 *
 * alpha 管理原则：
 *   AccentPalette 中的 topGlow / bottomGlow / dialogGlow 均为纯色（无 alpha），
 *   所有透明度在此文件的 [buildBackgroundTokens] 中统一注入，避免双重叠加。
 */
@Immutable
data class ZtBackgroundTokens(
    val baseColor: Color,
    val topGlowBrush: Brush,
    val bottomGlowBrush: Brush,
    val dialogGlowBrush: Brush,
)

/**
 * CompositionLocal 占位默认值。
 * 实际值由 [buildBackgroundTokens] 在 ZerotierLinkTheme 初始化时注入。
 */
val LocalZtBackgroundTokens = staticCompositionLocalOf {
    ZtBackgroundTokens(
        baseColor       = Color.Black,
        topGlowBrush    = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
        bottomGlowBrush = Brush.radialGradient(listOf(Color.Transparent, Color.Transparent)),
        dialogGlowBrush = Brush.radialGradient(listOf(Color.Transparent, Color.Transparent)),
    )
}

/**
 * 根据当前主题状态构建背景令牌。
 *
 * @param baseBackground      来自 MD3 colorScheme.background
 * @param accent              当前预设的调色板，提供纯色光晕锚点
 * @param isDark              深色模式下光晕透明度略高，保证在深色背景上可见
 * @param dynamicColorEnabled Android 12+ 动态取色开启时，品牌光晕退至极淡，
 *                            避免与壁纸色产生色相冲突
 */
fun buildBackgroundTokens(
    baseBackground: Color,
    accent: AccentPalette,
    isDark: Boolean,
    dynamicColorEnabled: Boolean,
): ZtBackgroundTokens {

    // ── 透明度分级 ──────────────────────────────────────────────────
    // 深色背景本身偏暗，光晕需略强才可感知；浅色背景底色亮，光晕需克制避免喧宾夺主
    val alphaTop    = if (isDark) 0.11f else 0.07f
    val alphaBottom = if (isDark) 0.08f else 0.05f
    val alphaDialog = if (isDark) 0.14f else 0.08f

    // 动态取色模式下减半，让壁纸色主导，品牌色退居装饰层
    val dynamicFactor = if (dynamicColorEnabled) 0.45f else 1.0f

    // ── 光晕颜色 ────────────────────────────────────────────────────
    // topGlow：同色相品牌色，垂直消散，形成顶部品牌氛围
    val topGlowColor = accent.topGlow.copy(alpha = alphaTop * dynamicFactor)

    // bottomGlow：AccentPalette 中已轻微偏移色相，底部落地感更自然
    val bottomGlowColor = accent.bottomGlow.copy(alpha = alphaBottom * dynamicFactor)

    // dialogGlow：与 primary 同色相，强化弹窗的品牌归属感
    val dialogGlowColor = accent.dialogGlow.copy(alpha = alphaDialog * dynamicFactor)

    // ── 构建渐变 ────────────────────────────────────────────────────
    return ZtBackgroundTokens(
        baseColor = baseBackground,

        // 顶部：垂直向下，品牌色从顶边缘自然消散至透明
        topGlowBrush = Brush.verticalGradient(
            colors = listOf(topGlowColor, Color.Transparent),
        ),

        // 底部：径向渐变，中心锚点在底部中央
        // center 用固定偏移模拟"底部中心"，radius 控制扩散面积
        // 注：实际使用时建议在 Box 里配合 fillMaxSize 让渐变自然平铺
        bottomGlowBrush = Brush.radialGradient(
            colors = listOf(bottomGlowColor, Color.Transparent),
            center = Offset(0.5f * 1080f, 1.0f * 2400f), // 近似底部中心，正式项目可用 onGloballyPositioned 动态传入
            radius = 680f,
        ),

        // 弹窗：小半径、中心亮，营造浮起感
        dialogGlowBrush = Brush.radialGradient(
            colors = listOf(dialogGlowColor, Color.Transparent),
            radius = 380f,
        ),
    )
}