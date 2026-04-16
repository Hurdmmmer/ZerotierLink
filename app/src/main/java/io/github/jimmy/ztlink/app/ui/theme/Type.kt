package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.jimmy.ztlink.R

/**
 * 中文主字体族：
 * 使用 Noto Sans SC 保证简体中文在不同厂商设备上的一致性。
 */
private val ZtCjkSans = FontFamily(
    Font(R.font.noto_sans_sc_regular, FontWeight.Normal),
    Font(R.font.noto_sans_sc_medium, FontWeight.Thin),
    Font(R.font.noto_sans_sc_semibold, FontWeight.SemiBold)
)

/**
 * 技术字段等宽字体族：
 * 用于 Node ID / IP / MAC / Network ID 等信息，保证字符对齐。
 */
private val ZtMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium)
)

/**
 * 全局排版 Token：
 * - 所有中文 UI 文本默认走 ZtCjkSans
 * - 技术字符串专用样式走 ZtMono
 */
val ZerotierLinkTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = ZtCjkSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ZtCjkSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ZtCjkSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ZtCjkSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ZtCjkSans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ZtMono,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 0.2.sp
    )
)
