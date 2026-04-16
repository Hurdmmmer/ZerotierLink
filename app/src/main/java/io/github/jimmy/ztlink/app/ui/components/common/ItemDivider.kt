package io.github.jimmy.ztlink.app.ui.components.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * 通用的分割线组件，预设了符合项目视觉风格的边距、粗细和颜色。
 */
@Composable
fun ItemDivider(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ZtTheme.dimen.space16,
    thickness: Dp = 0.5.dp,
    alpha: Float = 0.4f
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = horizontalPadding),
        thickness = thickness,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha),
    )
}
