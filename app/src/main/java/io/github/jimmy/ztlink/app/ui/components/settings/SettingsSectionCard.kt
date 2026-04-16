package io.github.jimmy.ztlink.app.ui.components.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * 设置页分组卡片。
 *
 * 颜色层级（从下到上）：
 *   background（最底层）
 *   surfaceContainer       ← 导航栏 NavigationBar 使用这一级
 *   surfaceContainerHigh   ← 卡片使用这一级，比导航栏深一档，产生可感知的层次差
 *
 * 同时加 outlineVariant 细描边（0.6dp），在深色模式下描边会更明显，
 * 弥补深色背景下纯靠色调差不够清晰的问题。
 */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = ZtTheme.dimen

    Column(modifier = modifier.fillMaxWidth()) {

        Text(
            text  = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start  = spacing.space16 + 4.dp,
                bottom = spacing.space4 + 2.dp,
            ),
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                // 细描边：在深色模式下帮助卡片与背景区分，浅色模式下几乎不可见
                .border(
                    width = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.extraLarge,
                ),
            shape  = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                // surfaceContainerHigh：比 NavigationBar（surfaceContainer）深一级
                // MD3 surface tonal 层级：background < surface < surfaceContainerLowest
                // < surfaceContainerLow < surfaceContainer < surfaceContainerHigh < surfaceContainerHighest
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content  = content,
            )
        }
    }
}