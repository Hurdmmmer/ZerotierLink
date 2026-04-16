package io.github.jimmy.ztlink.app.ui.components.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * 网络列表页。
 *
 * 架构说明：
 * - 使用公共 [AppTopBar]，通过 actions 槽注入"加入网络"的 + 图标按钮，
 *   与标题同行，符合 MD3 TopAppBar actions 规范。
 * - 不使用 FAB：加入网络是高频主操作，放在 TopBar 更易触达，
 *   同时避免 FAB 遮挡列表最后一项。
 * - 底部状态信息作为列表最后一个 item，随列表滚动，不固定在屏幕底部。
 *
 * 状态来源：
 * - 本组件内部通过 Hilt 获取 [NetworksViewModel] 并收集状态，
 *   不再由 NavHost 透传 ViewModel 或网络列表。
 *
 * @param onNetworkClick   卡片点击进入详情。
 * @param onJoinNetwork    TopBar + 按钮点击，跳转加入网络页。
 */
@Composable
fun NetworksScreen(
    onNetworkClick: (String) -> Unit,
    onJoinNetwork: () -> Unit,
) {
    val viewModel: NetworksViewModel = hiltViewModel()
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val networks = uiState.value.networks

    ObserveUiEvents(viewModel.uiEvents)

    val nodeId: String = ""
    val coreVersion = ""
    val appVersion = ""

    val dimen = ZtTheme.dimen

    Scaffold(
        modifier       = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nav_networks),
                subtitle = if (nodeId.isNotBlank()) "Node · ${nodeId.take(10)}…" else "",
                actions = {
                    IconButton(onClick = onJoinNetwork) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.network_join),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->

        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (networks.isEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyNetworkHint(
                                onJoinNetwork = onJoinNetwork,
                                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier       = Modifier
                        .padding(innerPadding)
                        .padding(horizontal = dimen.space16),
                    verticalArrangement = Arrangement.spacedBy(dimen.space12),
                    contentPadding = PaddingValues(
                        top    = dimen.space8,
                        bottom = dimen.space24,
                    ),
                ) {
                    items(networks, key = { it.networkId }) { network ->
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            NetworkCard(
                                network     = network,
                                onToggle    = { enabled -> viewModel.toggleNetwork(network.networkId, enabled) },
                                onClick     = { onNetworkClick(network.networkId) },
                                onLongClick = { showMenu = true },
                            )

                            // 长按弹出菜单，锚定在卡片上
                            DropdownMenu(
                                expanded         = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.network_copy_id)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.requestCopyNetworkId(network.networkId)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text  = stringResource(R.string.network_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.deleteNetwork(network.networkId)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint     = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // 底部状态信息：跟随列表滚动，不遮挡内容
                    item {

                        BottomStatusBar(
                            nodeId      = nodeId,
                            coreVersion = coreVersion,
                            appVersion  = appVersion,
                        )
                    }
            }
            }
        }
    }
}

// ── 内部组件 ──────────────────────────────────────────────────────────────

/**
 * 单张网络卡片。
 *
 * 视觉层次（从左到右）：
 * 1. 4dp 左侧竖线：颜色跟随连接状态，是最快速的状态感知入口
 * 2. 网络名称 + Switch：一眼看到名称，右手可直接拨动开关
 * 3. Network ID：等宽字体，辨识度高
 * 4. 标签行：状态 Badge + LAN 标签 + P2P 摘要
 * 5. 分配的 IP：最多展示 2 条，等宽字体 + primary 色
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    network: NetworkListItem,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dimen = ZtTheme.dimen

    val statusColor by animateColorAsState(
        targetValue = when (network.status) {
            NetworkStatus.CONNECTED     -> MaterialTheme.colorScheme.tertiary
            NetworkStatus.REQUESTING_CONFIGURATION -> MaterialTheme.colorScheme.secondary
            NetworkStatus.AUTHENTICATION_REQUIRED -> MaterialTheme.colorScheme.primary
            NetworkStatus.DISCONNECTED  -> MaterialTheme.colorScheme.outline
            NetworkStatus.ACCESS_DENIED,
            NetworkStatus.NOT_FOUND     -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300),
        label         = "networkStatusColor",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape  = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Box 包裹，让竖线可以用 matchParentSize
        Box(modifier = Modifier.fillMaxWidth()) {

            // 内容区：左边留出竖线宽度的 padding
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimen.space16 + 4.dp,  // 4dp 竖线宽度 + 正常内边距
                        end = dimen.space12,
                        top = dimen.space12,
                        bottom = dimen.space12,
                    ),
            ) {
                // ── 标题行 ────────────────────────────────────────────
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = network.name.ifBlank { network.networkId },
                        style    = MaterialTheme.typography.titleMedium,
                        color    = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Switch(
                        checked         = network.isEnabled,
                        onCheckedChange = onToggle,
                        modifier        = Modifier.padding(start = dimen.space8),
                    )
                }

                Spacer(Modifier.height(2.dp))

                // ── Network ID ────────────────────────────────────────
                Text(
                    text  = network.networkId,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(dimen.space8))

                // ── 标签行 ────────────────────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimen.space8),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    StatusBadge(network.status)
                    if (network.isLan) LanChip()
                    if (network.p2pSummary.isNotBlank()) P2pLabel(network.p2pSummary)
                }

                // ── 分配的 IP ─────────────────────────────────────────
                if (network.assignedIps.isNotEmpty()) {
                    Spacer(Modifier.height(dimen.space4))
                    network.assignedIps.take(2).forEach { ip ->
                        Text(
                            text  = ip,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 左侧状态竖线：绝对定位在 Box 左侧，高度撑满内容
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .matchParentSize()          // 现在在 BoxScope 里，可以用
                    .background(statusColor),
            )
        }
    }
}

/**
 * 连接状态徽章。
 * 使用 MD3 语义容器色，不硬编码颜色值。
 */
@Composable
private fun StatusBadge(status: NetworkStatus) {
    val (bg, fg, label) = when (status) {
        NetworkStatus.CONNECTED     -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.network_status_connected),
        )
        NetworkStatus.REQUESTING_CONFIGURATION    -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(R.string.network_status_requesting_configuration),
        )
        NetworkStatus.AUTHENTICATION_REQUIRED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.network_status_authentication_required),
        )
        NetworkStatus.DISCONNECTED  -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.network_status_disconnected),
        )
        NetworkStatus.ACCESS_DENIED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.network_status_access_denied),
        )
        NetworkStatus.NOT_FOUND     -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.network_status_not_found),
        )
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/** 内网标签 Chip */
@Composable
private fun LanChip() {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.Home,
            contentDescription = null,
            modifier           = Modifier.size(11.dp),
            tint               = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text  = stringResource(R.string.network_lan),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** P2P 摘要标签 */
@Composable
private fun P2pLabel(summary: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.Hub,
            contentDescription = null,
            modifier           = Modifier.size(12.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text  = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 空态引导区。
 * 图标 + 说明 + 引导按钮，告诉用户下一步怎么做。
 */
@Composable
private fun EmptyNetworkHint(
    modifier: Modifier = Modifier,
    onJoinNetwork: () -> Unit,
) {
    val dimen = ZtTheme.dimen
    Column(
        modifier            = modifier.padding(dimen.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector        = Icons.Outlined.Hub,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimen.space12))
        Text(
            text  = stringResource(R.string.network_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(dimen.space4))
        Text(
            text  = stringResource(R.string.network_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimen.space20))
        // 空态时提供一个明确的 CTA 按钮，不依赖用户发现 TopBar 的 + 号
        androidx.compose.material3.FilledTonalButton(
            onClick = onJoinNetwork,
            shape   = MaterialTheme.shapes.medium,
        ) {
            Icon(
                imageVector        = Icons.Filled.Add,
                contentDescription = null,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(dimen.space8))
            Text(stringResource(R.string.network_join))
        }
    }
}

/**
 * 底部状态信息区。
 * 显示 Node ID / Core 版本 / App 版本，视觉权重最低。
 * 作为 LazyColumn 最后一个 item，随列表滚动而不是固定在屏幕底部。
 */
@Composable
private fun BottomStatusBar(
    nodeId: String,
    coreVersion: String,
    appVersion: String,
) {
    val dimen = ZtTheme.dimen
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = dimen.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 在线指示点 + 版本号
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimen.space8),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Text(
                text  = "ZeroTier Link $appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        // Core 版本
        if (coreVersion.isNotBlank()) {
            Text(
                text  = "Core $coreVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        // Node ID 完整展示
        if (nodeId.isNotBlank()) {
            Text(
                text  = nodeId,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}
