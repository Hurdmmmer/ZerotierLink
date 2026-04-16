package io.github.jimmy.ztlink.app.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 通用的信息行组件。
 * 支持左右布局、自定义样式、颜色、间距以及右侧后缀组件。
 */
@Composable
fun MetaLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    showColon: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    valueStyle: TextStyle = MaterialTheme.typography.labelSmall,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueModifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    suffix: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showColon) "$label:" else label,
            style = labelStyle,
            color = labelColor,
        )
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = valueModifier,
        )
        if (suffix != null) {
            suffix()
        }
    }
}
