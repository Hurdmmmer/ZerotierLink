package io.github.jimmy.ztlink.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import io.github.jimmy.ztlink.app.ui.components.settings.ThemeMode
import io.github.jimmy.ztlink.app.ui.components.settings.ThemeSettings

// ── 工具函数 ───────────────────────────────────────────────────────────────

/**
 * 在两个颜色之间做线性混合。
 * amount = 0 → 返回 base，amount = 1 → 返回 tint。
 */
private fun blend(base: Color, tint: Color, amount: Float): Color =
    lerp(base, tint, amount.coerceIn(0f, 1f))

// ── 色盘构建 ───────────────────────────────────────────────────────────────

/**
 * 以 seed 色驱动 materialkolor 生成完整 MD3 ColorScheme。
 *
 * 选用 TonalSpot 风格原因：
 * - 主色饱和度适中，导航栏 indicator、按钮、容器色自然协调
 * - 相比 Vibrant 不会过度饱和，保持 ZeroTier 的工具类应用气质
 */
private fun buildSeedBasedColorScheme(
    accent: AccentPalette,
    isDark: Boolean,
): ColorScheme = dynamicColorScheme(
    seedColor  = accent.primary,
    isDark     = isDark,
    isAmoled   = false,
    style      = PaletteStyle.TonalSpot,
)

// ── 语义色构建 ─────────────────────────────────────────────────────────────

/**
 * 从最终 ColorScheme 派生业务语义色。
 *
 * 策略：
 * - 动态取色模式：语义色跟随系统壁纸色相，仅用 accent.primary 做轻量校正（≤20%），
 *   保证与系统动态色协调
 * - 自定义模式：语义色完全从 seed 生成的色盘派生，色相关系由算法保证
 */
private fun buildSemanticColors(
    colorScheme: ColorScheme,
    accent: AccentPalette,
    dynamicColorEnabled: Boolean,
): ZtSemanticColors = if (dynamicColorEnabled) {
    ZtSemanticColors(
        connected   = blend(colorScheme.tertiary,          accent.primary, 0.15f),
        relay       = blend(colorScheme.secondary,         accent.primary, 0.10f),
        root        = blend(colorScheme.primary,           accent.primary, 0.08f),
        warning     = colorScheme.tertiaryContainer,
        errorStrong = colorScheme.error,
        inactive    = colorScheme.outline,
    )
} else {
    ZtSemanticColors(
        connected   = blend(colorScheme.tertiary,          colorScheme.primary, 0.20f),
        relay       = blend(colorScheme.secondary,         colorScheme.primary, 0.15f),
        root        = colorScheme.primary,
        warning     = blend(colorScheme.tertiaryContainer, Color(0xFFD4921A),   0.30f),
        errorStrong = colorScheme.error,
        inactive    = colorScheme.outline,
    )
}

// ── 主题根节点 ─────────────────────────────────────────────────────────────

/**
 * 应用主题根节点，所有自定义 Token 在此统一注入 CompositionLocal。
 *
 * 调用层级：
 *   ZerotierLinkTheme
 *     ├── MaterialTheme（colorScheme / typography / shapes）
 *     ├── LocalZtSemanticColors   业务语义色
 *     ├── LocalZtMotionTokens     动效曲线与时长
 *     ├── LocalZtSpacingTokens    间距比例
 *     ├── LocalZtElevationTokens  层级高度
 *     └── LocalZtBackgroundTokens 背景与氛围层
 */
@Composable
fun ZerotierLinkTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val context   = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dynamicColorEnabled = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val useDarkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }

    val accent = accentPaletteOf(settings.accentPreset)

    val view = LocalView.current

    val colorScheme = when {
        dynamicColorEnabled -> if (useDarkTheme) dynamicDarkColorScheme(context)
        else              dynamicLightColorScheme(context)
        else                -> buildSeedBasedColorScheme(accent = accent, isDark = useDarkTheme)
    }

    val activity = view.context as? Activity
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }
    val semantic    = buildSemanticColors(colorScheme, accent, dynamicColorEnabled)
    val backgrounds = buildBackgroundTokens(
        baseBackground      = colorScheme.background,
        accent              = accent,
        isDark              = useDarkTheme,
        dynamicColorEnabled = dynamicColorEnabled,
    )

    // 构建符合 MD3 规范的动效令牌（此处为固定值；如需支持无障碍减速动画，
    // 可读取 LocalAccessibilityManager 并相应缩放时长）
    val motionTokens = ZtMotionTokens(
        fastMillis   = 160,
        normalMillis = 300,
        slowMillis   = 500,
        standardEasing             = CubicBezierEasing(0.2f,  0.0f, 0.0f,  1.0f),
        emphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f,  1.0f),
        emphasizedAccelerateEasing = CubicBezierEasing(0.3f,  0.0f, 0.8f,  0.15f),
    )

    CompositionLocalProvider(
        LocalZtSemanticColors   provides semantic,
        LocalZtMotionTokens     provides motionTokens,   // 真正注入实例，不再循环自引用
        LocalZtDimenTokens    provides ZtDimenTokens(),
        LocalZtElevationTokens  provides ZtElevationTokens(),
        LocalZtBackgroundTokens provides backgrounds,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = ZerotierLinkTypography,
            shapes      = ZerotierLinkShapes,
            content     = content,
        )
    }
}

// ── 统一访问入口 ───────────────────────────────────────────────────────────

/**
 * 自定义设计令牌的统一访问入口，用法类似 MaterialTheme：
 *
 *   ZtTheme.semantic.connected
 *   ZtTheme.motion.enterSpec<Float>()
 *   ZtTheme.dimen.x4
 */
/**
 * 自定义设计令牌的统一访问入口，用法类似 MaterialTheme：
 *
 *   ZtTheme.semantic.connected
 *   ZtTheme.motion.normalMillis
 *   ZtTheme.dimen.x4
 */
object ZtTheme {
    /** 业务语义色（如连接状态、警告、错误等） */
    val semantic: ZtSemanticColors
        @Composable @ReadOnlyComposable get() = LocalZtSemanticColors.current

    /** 动效令牌（包含动画时长和缓动曲线） */
    val motion: ZtMotionTokens
        @Composable @ReadOnlyComposable get() = LocalZtMotionTokens.current

    /** 尺寸与间距令牌（基于 4dp/8dp 步进的比例系统） */
    val dimen: ZtDimenTokens
        @Composable @ReadOnlyComposable get() = LocalZtDimenTokens.current

    /** 层级与高度令牌（控制阴影和视觉深度） */
    val elevation: ZtElevationTokens
        @Composable @ReadOnlyComposable get() = LocalZtElevationTokens.current

    /** 背景与氛围层（如列表项、卡片在不同状态下的底色） */
    val background: ZtBackgroundTokens
        @Composable @ReadOnlyComposable get() = LocalZtBackgroundTokens.current
}
