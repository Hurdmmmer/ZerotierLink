package io.github.jimmy.ztlink.app.ui.components.peers

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Peers 页面骨架。
 *
 * 说明：
 * 后续会接入直连/中继/root 等节点列表样式与状态颜色。
 */
@Composable
fun PeersScreen(
    modifier: Modifier = Modifier
) {
    Text(
        text = "Peers 页面正在开发中，敬请期待！",
        modifier = modifier
    )
}
