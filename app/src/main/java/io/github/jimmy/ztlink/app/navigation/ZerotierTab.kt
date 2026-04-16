package io.github.jimmy.ztlink.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.jimmy.ztlink.R

/**
 * 底部导航标签定义。
 *
 * @property route 稳定路由键，避免导航状态失效。
 * @property titleRes 标签文案的字符串资源 ID。
 * @property iconOutlined 未选中状态图标。
 * @property iconFilled 选中状态图标。
 */
enum class ZerotierTab(
    val route: String,
    val titleRes: Int,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    NETWORKS(
        route = "networks",
        titleRes = R.string.nav_networks,
        iconOutlined = Icons.Outlined.Hub,
        iconFilled = Icons.Filled.Hub,
    ),
    PEERS(
        route = "peers",
        titleRes = R.string.nav_peers,
        iconOutlined = Icons.Outlined.Lan,
        iconFilled = Icons.Filled.Lan,
    ),
    MOONS(
        route = "moons",
        titleRes = R.string.nav_moons,
        iconOutlined = Icons.Outlined.CellTower,
        iconFilled = Icons.Filled.CellTower,
    ),
    SETTINGS(
        route = "settings",
        titleRes = R.string.nav_settings,
        iconOutlined = Icons.Outlined.Settings,
        iconFilled = Icons.Filled.Settings,
    ),
}
