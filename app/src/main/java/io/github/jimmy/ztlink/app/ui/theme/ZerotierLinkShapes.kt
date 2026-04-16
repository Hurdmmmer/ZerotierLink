// app/src/main/java/io/github/jimmy/ztlink/ui/theme/Shape.kt
package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角 Token：
 * 与卡片、按钮、对话框层级保持一致。
 */
val ZerotierLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
