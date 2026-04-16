package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.MetaLine
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun NetworksScreen(
    onNetworkClick: (String) -> Unit,
    onJoinNetwork: () -> Unit,
) {
    val viewModel: NetworksViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val networks = uiState.networks
    val nodeId   = ""      // ViewModel 提供
    val appVersion = "1.0.0"

    ObserveUiEvents(viewModel.uiEvents)

    val dimen = ZtTheme.dimen

    Scaffold(
        modifier       = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(
                title    = stringResource(R.string.nav_networks),
                // Node ID 截短显示在副标题，完整 ID 在底部状态栏
                subtitle = nodeId.takeIf { it.isNotBlank() }
                    ?.let { "Node · ${it.take(10)}…" }
                    ?: "",
                actions  = {
                    IconButton(onClick = onJoinNetwork) {
                        Icon(
                            imageVector        = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.network_join),
                            tint               = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // BouncyOverScroll 只吃 top padding，不重复计算
        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (networks.isEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Box(
                            modifier         = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyNetworkHint(
                                onJoinNetwork = onJoinNetwork,
                                modifier      = Modifier.padding(
                                    bottom = innerPadding.calculateBottomPadding(),
                                ),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier       = Modifier
                        .fillMaxSize()
                        .padding(horizontal = dimen.space16),
                    // top = space8：TopBar 下方只留 8dp，不要过大空隙
                    contentPadding = PaddingValues(
                        top    = dimen.space8,
                        bottom = innerPadding.calculateBottomPadding() + dimen.space16,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimen.space8),
                ) {
                    items(networks, key = { it.networkId }) { network ->
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            NetworkCard(
                                network     = network,
                                onToggle    = { enabled ->
                                    viewModel.toggleNetwork(network.networkId, enabled)
                                },
                                onClick     = { onNetworkClick(network.networkId) },
                                onLongClick = { showMenu = true },
                            )

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
                                            null,
                                            Modifier.size(18.dp),
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
                                            null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    item {
                        BottomStatusBar(
                            nodeId      = nodeId,
                            appVersion  = appVersion,
                        )
                    }
                }
            }
        }
    }
}

// ── NetworkCard ───────────────────────────────────────────────────────────

/**
 * 网络卡片。
 *
 * 视觉设计决策：
 * 1. 左侧状态条负责“秒级识别当前状态”，降低扫描成本。
 * 2. 中间信息区分三层：名称/状态 -> ID -> IP/P2P/LAN，避免信息平铺。
 * 3. 右侧仅放连接开关，强调“全卡点击看详情、开关单独控制连接”。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    /** 当前网络项数据。 */
    network: NetworkListItem,
    /** 开关变更回调（启用/禁用网络）。 */
    onToggle: (Boolean) -> Unit,
    /** 点击卡片回调（进入详情页）。 */
    onClick: () -> Unit,
    /** 长按卡片回调（弹出菜单）。 */
    onLongClick: () -> Unit,
) {
    val dimen = ZtTheme.dimen
    val cardShape = MaterialTheme.shapes.extraLarge

    // 状态色：动画过渡，切换时平滑
    val statusColor by animateColorAsState(
        targetValue = when (network.status) {
            NetworkStatus.CONNECTED                -> MaterialTheme.colorScheme.tertiary
            NetworkStatus.REQUESTING_CONFIGURATION -> MaterialTheme.colorScheme.secondary
            NetworkStatus.AUTHENTICATION_REQUIRED  -> MaterialTheme.colorScheme.primary
            NetworkStatus.DISCONNECTED             -> MaterialTheme.colorScheme.outline
            NetworkStatus.ACCESS_DENIED,
            NetworkStatus.NOT_FOUND                -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300),
        label         = "statusColor",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 关键逻辑：
    // 整卡按压时做轻微缩放，反馈范围覆盖完整卡片。
    // 配合 clip(cardShape) 能让点击反馈与圆角保持一致。
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(),
        label = "networkCardPressedScale",
    )

    // 状态提示文案严格按当前连接状态映射，避免“文案与状态不一致”。
    val activationHintRes = when (network.status) {
        NetworkStatus.CONNECTED -> R.string.network_status_hint_connected
        NetworkStatus.REQUESTING_CONFIGURATION -> R.string.network_status_hint_requesting_configuration
        NetworkStatus.AUTHENTICATION_REQUIRED -> R.string.network_status_hint_authentication_required
        NetworkStatus.DISCONNECTED -> R.string.network_status_hint_disconnected
        NetworkStatus.ACCESS_DENIED -> R.string.network_status_hint_access_denied
        NetworkStatus.NOT_FOUND -> R.string.network_status_hint_not_found
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .scale(pressedScale)
            // 关键逻辑：
            // 卡片整体可点击进入详情，符合“所见即所得”的交互预期。
            .clip(cardShape)
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape    = cardShape,
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── 内容区 ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start  = dimen.space12,
                        top    = dimen.space12,
                        bottom = dimen.space12,
                        end    = dimen.space8,
                    ),
            ) {
                Column {
                    // 第一层：网络名称 + 激活状态
                    MetaLine(
                        label = "Network Name",
                        value = network.name.ifBlank { network.networkId },
                        modifier = Modifier.fillMaxWidth(),
                        labelStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        valueStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        valueModifier = Modifier.weight(1f),
                        spacing = dimen.space8,
                        suffix = { StatusPill(network.status) }
                    )
                    Spacer(Modifier.height(dimen.space8))
                    // 顶部名称区与下方信息区分隔，避免信息堆叠。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.height(dimen.space8))

                    // 第二层：Network ID
                    MetaLine(
                        label = "ID",
                        value = network.networkId,
                        showColon = true,
                        valueStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )

                    // 第三层：IP 摘要
                    val ipSummary = remember(network.assignedIps) {
                        network.assignedIps.take(2).joinToString("  ·  ")
                    }
                    if (ipSummary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        MetaLine(
                            label = "IP",
                            value = ipSummary,
                            showColon = true,
                            valueStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            valueColor = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(dimen.space8))

                    // 第四层：辅助标签（LAN / P2P）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dimen.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (network.isLan) {
                            LanChip()
                        }
                        if (network.p2pSummary.isNotBlank()) {
                            P2pLabel(network.p2pSummary)
                        }
                    }

                    Spacer(Modifier.height(dimen.space8))

                    // 第五层（卡片底部）：状态提示文案
                    // 关键逻辑：
                    // - 顶部已显示短状态标签，底部只保留“解释文案”避免重复信息；
                    // - 用户阅读路径固定为：名称+状态 -> 基础信息 -> 底部解释。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(activationHintRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── 分割线：按你的要求，用分割线区分左右区域 ────────────────
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(96.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    ),
            )

            // ── 开关区：独立的连接开关 ───────────────────────────────
            // 语义：开 = 启用连接，关 = 断开连接（不是路由开关）
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Switch(
                    checked         = network.isEnabled,
                    onCheckedChange = onToggle,
                    // 关键逻辑：
                    // 用户明确要求“这是连接开关，任何状态下都可点击”。
                    // 因此这里不再按 REQUESTING_CONFIGURATION 锁死开关。
                    enabled = true,
                    colors  = SwitchDefaults.colors(
                        checkedTrackColor   = statusColor,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }
        }
    }
}

// ── 状态标签组件 ──────────────────────────────────────────────────────────

/**
 * 顶部短状态标签。
 *
 * 说明：
 * 1. 使用短文案防止挤压右侧开关区域。
 * 2. 颜色与当前连接状态一致，保持视觉语义统一。
 *
 * @param status 当前连接状态。
 */
@Composable
private fun StatusPill(status: NetworkStatus) {
    val (bg, fg, label) = when (status) {
        NetworkStatus.CONNECTED -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.network_status_chip_connected),
        )
        NetworkStatus.REQUESTING_CONFIGURATION -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(R.string.network_status_chip_requesting_configuration),
        )
        NetworkStatus.AUTHENTICATION_REQUIRED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.network_status_chip_authentication_required),
        )
        NetworkStatus.DISCONNECTED -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.network_status_chip_disconnected),
        )
        NetworkStatus.ACCESS_DENIED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.network_status_chip_access_denied),
        )
        NetworkStatus.NOT_FOUND -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.network_status_chip_not_found),
        )
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LanChip() {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.Home,
            contentDescription = null,
            modifier           = Modifier.size(10.dp),
            tint               = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text  = stringResource(R.string.network_lan),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun P2pLabel(summary: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.Hub,
            contentDescription = null,
            modifier           = Modifier.size(11.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text  = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 空态 & 底部状态 ───────────────────────────────────────────────────────

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

@Composable
private fun BottomStatusBar(nodeId: String, appVersion: String) {
    val dimen = ZtTheme.dimen
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = dimen.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
