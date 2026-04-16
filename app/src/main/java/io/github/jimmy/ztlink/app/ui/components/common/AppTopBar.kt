package io.github.jimmy.ztlink.app.ui.components.common

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

/**
 * 应用通用顶部栏组件。
 *
 * 扩展了 [actions] 槽，允许各页面向右侧注入图标按钮（如加入网络的 + 号），
 * 保持顶栏视觉一致性的同时支持页面级操作扩展。
 *
 * @param title          标题文字。
 * @param subtitle       副标题，不为空时显示在标题下方（labelSmall）。
 * @param navigationIcon 左侧导航图标区（返回键等），默认为空。
 * @param actions        右侧操作区，接受 RowScope 内的任意 Composable。
 * @param containerColor 顶栏背景色，默认透明让全局背景透出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String                                   = "",
    subtitle: String                                = "",
    navigationIcon: @Composable () -> Unit          = {},
    actions: @Composable RowScope.() -> Unit        = {},
    containerColor: Color                           = Color.Transparent,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.zIndex(1f),
        color    = containerColor,
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text  = title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text  = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions        = actions,
            colors         = TopAppBarDefaults.topAppBarColors(
                containerColor         = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
    }
}