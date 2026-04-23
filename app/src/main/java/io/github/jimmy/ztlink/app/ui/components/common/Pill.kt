package io.github.jimmy.ztlink.app.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun Pill(
    containerColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedShape = shape ?: ZtTheme.shapes.extraLarge

    Row(
        modifier = modifier
            .clip(resolvedShape)
            .background(containerColor)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(width = borderWidth, color = borderColor, shape = resolvedShape)
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = horizontalArrangement,
    ) {
        content()
    }
}
