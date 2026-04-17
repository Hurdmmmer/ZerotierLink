package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.MetaLine
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme


/**
 * 网络卡片组件。
 *
 * 功能：展示 ZeroTier 网络的详细信息，包括名称、ID、分配的 IP、状态标签以及 LAN/P2P 标识。
 *
 * 参数：
 * @param network 网络项数据模型，包含当前网络的所有状态信息。
 * @param isAnyOtherEnabled 是否有其他网络处于启用状态，用于控制开关的启用逻辑。
 * @param onToggle 开关状态改变时的回调。
 * @param onClick 单击卡片时的回调（通常用于跳转到详情页）。
 * @param onLongClick 长按卡片时的回调（通常用于触发快捷操作）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkCard(
    network: NetworkListItem,
    isAnyOtherEnabled: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dimen = ZtTheme.dimen
    val cardShape = MaterialTheme.shapes.extraLarge

    // 根据网络状态动态计算主题颜色（用于开关 Track 和状态指示）
    val statusColor by animateColorAsState(
        targetValue = when (network.status) {
            NetworkStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
            NetworkStatus.REQUESTING_CONFIGURATION -> MaterialTheme.colorScheme.secondary
            NetworkStatus.AUTHENTICATION_REQUIRED -> MaterialTheme.colorScheme.primary
            NetworkStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
            NetworkStatus.ACCESS_DENIED,
            NetworkStatus.NOT_FOUND -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300),
        label = "statusColor",
    )

    // 点击反馈逻辑：按下时通过缩放提供物理感
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(),
        label = "networkCardPressedScale",
    )

    // 根据当前网络状态映射对应的辅助说明文案
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
            .clip(cardShape)
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            // 背景色逻辑：启用时使用品牌淡色背景增强识别度，禁用时弱化
            containerColor = if (network.isEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
            },
        ),
        // 边框逻辑：仅在启用状态下显示微弱边框，提升层次感
        border = if (network.isEnabled) {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        } else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(dimen.space12),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(dimen.space8)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = network.name.ifBlank { network.networkId },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(dimen.space8))
                        StatusPill(network.status)
                    }

                    MetaLine(
                        label = "Network ID",
                        value = network.networkId,
                        showColon = true,
                        labelStyle = MaterialTheme.typography.labelSmall,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        valueStyle = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        valueModifier = Modifier.weight(1f),
                        spacing = dimen.space8,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )

                    // IP 地址显示逻辑：已分配则显示，未启用或获取中则显示提示文案
                    val ipSummary = remember(network.assignedIps) {
                        network.assignedIps.take(2).joinToString("  ·  ")
                    }
                    val ipDisplayText = when {
                        ipSummary.isNotBlank() -> ipSummary
                        !network.isEnabled -> stringResource(R.string.network_ip_status_disconnected)
                        network.status == NetworkStatus.CONNECTED -> stringResource(R.string.network_ip_status_no_address)
                        else -> stringResource(R.string.network_ip_status_pending)
                    }

                    MetaLine(
                        label = "Assign IP",
                        value = ipDisplayText,
                        showColon = true,
                        labelStyle = MaterialTheme.typography.labelSmall,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        valueStyle = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (ipSummary.isNotBlank()) FontWeight.Medium else FontWeight.Normal
                        ),
                        valueColor = if (ipSummary.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        valueModifier = Modifier.weight(1f),
                        spacing = dimen.space8,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dimen.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (network.isLan) LanChip()
                        if (network.p2pSummary.isNotBlank()) P2pLabel(network.p2pSummary)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )

                    Text(
                        text = stringResource(activationHintRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(96.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            )

            Box(
                modifier = Modifier
                    .width(74.dp)
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 主开关：控制网络启用/禁用。若有其他网络正在启用且当前项未启用，则禁用开关。
                Switch(
                    checked = network.isEnabled,
                    onCheckedChange = onToggle,
                    enabled = !isAnyOtherEnabled || network.isEnabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = statusColor,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }
        }
    }
}

/**
 * 局域网标识标签。
 *
 * 功能：当网络被识别为局域网环境时，在卡片上显示明显的“LAN”标识。
 * 关键逻辑：使用 Tertiary 颜色系统，通过特定的图标和背景色与普通状态区分。
 */
@Composable
private fun LanChip() {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Home,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = stringResource(R.string.network_lan),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * P2P 连接状态标签。
 *
 * 功能：展示当前网络的 P2P 穿透或中转状态摘要。
 * 参数：
 * @param summary P2P 状态的描述文本。
 */
@Composable
private fun P2pLabel(summary: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 状态指示胶囊。
 *
 * 功能：以胶囊形状展示网络当前的连接状态，通过颜色和动画提供即时反馈。
 * 参数：
 * @param status 网络的连接状态枚举。
 * 关键逻辑：
 * 1. 颜色映射：根据状态（已连接、配置中、需认证、断开等）映射到对应的 MD3 容器颜色。
 * 2. 动效反馈：针对“请求配置”和“待认证”等中间态，展示 MD3 小型环形等待指示器。
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
    // 等待态动效合并到胶囊内部，避免和右侧开关区域产生视觉冲突。
    val isPending = status == NetworkStatus.REQUESTING_CONFIGURATION ||
            status == NetworkStatus.AUTHENTICATION_REQUIRED
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isPending) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(10.dp),
                    color = fg,
                    strokeWidth = 1.6.dp,
                    trackColor = fg.copy(alpha = 0.2f),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
