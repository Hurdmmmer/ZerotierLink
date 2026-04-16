package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 固定尺寸设计令牌。
 * 使用范围：
 * - 控件高度、图标大小、面板最大高度、触控区域等固定尺寸。
 */

@Immutable
data class ZtDimenTokens(
    /**
     *  0.dp
     */
    val space0: Dp = 0.dp,
    /**
     *  2.dp
     */
    val space2: Dp = 2.dp,
    /**
     *  4.dp
     */
    val space4: Dp = 4.dp,
    /**
     *  8.dp
     */
    val space8: Dp = 8.dp,
    /**
     *  12.dp
     */
    val space12: Dp = 12.dp,
    /**
     * 16.dp
     */
    val space16: Dp = 16.dp,
    /**
     * 20.dp
     */
    val space20: Dp = 20.dp,
    /**
     * 24.dp
     */
    val space24: Dp = 24.dp,
    /**
     * 32.dp
     */
    val space32: Dp = 32.dp,
    /**
     * 40.dp
     */
    val space40: Dp = 40.dp,
    /**
     * 56.dp
     */
    val controlHeightLarge: Dp = 56.dp,
    /**
     * 154.dp
     */
    val dnsPanelHeight: Dp = 154.dp,
    /**
     * 0.5.dp
     */
    val hairline: Dp = 0.5.dp,
)

/**
 * Local Composition for ZtSpacingTokens.
 * ZtSpacingTokens 的本地组合（CompositionLocal）。
 */
val LocalZtDimenTokens = staticCompositionLocalOf { ZtDimenTokens() }
