package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.ItemDivider
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme
import kotlin.math.abs

private enum class StatusVisualTone {
    CONNECTED,
    MONITORING,
    PENDING,
    OFFLINE,
    INACTIVE,
    ERROR,
}

private data class StatusVisualSpec(
    val accent: Color,
    val pending: Boolean,
    val tone: StatusVisualTone,
    val pillContainerColor: Color,
)

private fun buildStatusVisualSpec(
    status: NetworkStatus,
    semantic: io.github.jimmy.ztlink.app.ui.theme.ZtSemanticColors,
    colorScheme: androidx.compose.material3.ColorScheme,
    isDarkThemeSurface: Boolean,
): StatusVisualSpec {
    val authPendingAccent = lerp(
        colorScheme.tertiary,
        colorScheme.error,
        if (isDarkThemeSurface) 0.12f else 0.08f,
    )
    val noConnectionAccent = lerp(
        colorScheme.secondary,
        colorScheme.error,
        if (isDarkThemeSurface) 0.18f else 0.12f,
    )

    return when (status) {
        NetworkStatus.CONNECTED -> StatusVisualSpec(
            accent = semantic.connected,
            pending = false,
            tone = StatusVisualTone.CONNECTED,
            pillContainerColor = semantic.connected.copy(alpha = if (isDarkThemeSurface) 0.24f else 0.14f),
        )

        NetworkStatus.MONITORING -> StatusVisualSpec(
            // 监听态并入“已连接”色系，避免出现第三套重色导致视觉割裂。
            accent = semantic.connected,
            pending = false,
            tone = StatusVisualTone.CONNECTED,
            pillContainerColor = semantic.connected.copy(alpha = if (isDarkThemeSurface) 0.20f else 0.12f),
        )

        NetworkStatus.REQUESTING_CONFIGURATION -> StatusVisualSpec(
            accent = semantic.root,
            pending = true,
            tone = StatusVisualTone.PENDING,
            pillContainerColor = semantic.root.copy(alpha = if (isDarkThemeSurface) 0.34f else 0.20f),
        )

        NetworkStatus.AUTHENTICATION_REQUIRED -> StatusVisualSpec(
            accent = authPendingAccent,
            pending = true,
            tone = StatusVisualTone.PENDING,
            pillContainerColor = authPendingAccent.copy(alpha = if (isDarkThemeSurface) 0.36f else 0.22f),
        )

        NetworkStatus.NO_CONNECTION -> StatusVisualSpec(
            accent = noConnectionAccent,
            pending = false,
            tone = StatusVisualTone.OFFLINE,
            pillContainerColor = colorScheme.secondaryContainer,
        )

        NetworkStatus.DISCONNECTED -> StatusVisualSpec(
            accent = semantic.inactive,
            pending = false,
            tone = StatusVisualTone.INACTIVE,
            pillContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.90f),
        )

        NetworkStatus.ACCESS_DENIED,
        NetworkStatus.NOT_FOUND,
            -> StatusVisualSpec(
            accent = semantic.errorStrong,
            pending = false,
            tone = StatusVisualTone.ERROR,
            pillContainerColor = colorScheme.errorContainer,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkCard(
    network: NetworkListItem,
    isProcessing: Boolean = false,
    isAnyOtherEnabled: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cardShape = MaterialTheme.shapes.extraLarge
    val semantic = ZtTheme.semantic
    val isDarkThemeSurface = colorScheme.surface.luminance() < 0.5f
    val statusSpec = remember(network.status, semantic, colorScheme, isDarkThemeSurface) {
        buildStatusVisualSpec(
            status = network.status,
            semantic = semantic,
            colorScheme = colorScheme,
            isDarkThemeSurface = isDarkThemeSurface,
        )
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val indication = LocalIndication.current
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.986f else 1f,
        animationSpec = spring(),
        label = "networkCardScale",
    )

    val switchDisabled = (!network.isEnabled && isAnyOtherEnabled) || isProcessing

    val baseCardBackground = colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
    val baseCardBorder = colorScheme.outlineVariant.copy(alpha = 0.35f)
    val primaryTextColor = colorScheme.onSurface
    val secondaryTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val subtleTextColor = colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

    // 关键逻辑：
    // 激活态颜色不再使用固定混合比，而是根据：
    // 1) 当前亮/暗主题；
    // 2) 状态色与底色的亮度差；
    // 动态提高激活态可见度，避免“切主题后激活态和背景糊在一起”。
    val activeBackgroundMix =
        remember(baseCardBackground, statusSpec.accent, isDarkThemeSurface, statusSpec.tone) {
            computeActiveBackgroundMix(
                base = baseCardBackground,
                tint = statusSpec.accent,
                isDarkThemeSurface = isDarkThemeSurface,
                tone = statusSpec.tone,
            )
        }
    val activeBorderMix = remember(statusSpec.tone, isDarkThemeSurface) {
        when (statusSpec.tone) {
            StatusVisualTone.CONNECTED -> if (isDarkThemeSurface) 0.88f else 0.84f
            StatusVisualTone.MONITORING -> if (isDarkThemeSurface) 0.97f else 0.94f
            StatusVisualTone.PENDING -> if (isDarkThemeSurface) 0.96f else 0.93f
            StatusVisualTone.OFFLINE -> if (isDarkThemeSurface) 0.94f else 0.90f
            StatusVisualTone.INACTIVE -> if (isDarkThemeSurface) 0.90f else 0.86f
            StatusVisualTone.ERROR -> if (isDarkThemeSurface) 0.98f else 0.95f
        }
    }
    val activeBorderAlpha = remember(statusSpec.tone) {
        when (statusSpec.tone) {
            StatusVisualTone.CONNECTED -> 0.72f
            StatusVisualTone.MONITORING -> 0.94f
            StatusVisualTone.PENDING -> 0.93f
            StatusVisualTone.OFFLINE -> 0.90f
            StatusVisualTone.INACTIVE -> 0.82f
            StatusVisualTone.ERROR -> 0.96f
        }
    }

    val cardBackground by animateColorAsState(
        targetValue = if (network.isEnabled) {
            lerp(baseCardBackground, statusSpec.accent, activeBackgroundMix)
        } else {
            baseCardBackground
        },
        animationSpec = tween(220),
        label = "cardBackground",
    )
    // 激活态边框：在原有 outlineVariant 基础上提高透明度，不换色
    val borderColor by animateColorAsState(
        targetValue = if (network.isEnabled) {
            colorScheme.outlineVariant.copy(alpha = 0.75f)
        } else {
            baseCardBorder   // 0.35f alpha
        },
        animationSpec = tween(220),
        label = "cardBorder",
    )
    // borderWidth 保持，但幅度收窄
    val borderWidth by animateDpAsState(
        targetValue = if (network.isEnabled) 1.0.dp else 0.6.dp,
        animationSpec = tween(220),
        label = "cardBorderWidth",
    )
    val ipText = remember(network.assignedIps, network.isEnabled, network.status) {
        network.assignedIps.take(2).joinToString("  ·  ")
    }
    val hasIp = ipText.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressedScale)
            .clip(cardShape)
            .background(cardBackground)
            .border(width = borderWidth, color = borderColor, shape = cardShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(
                    tint = statusSpec.accent,
                    glowing = network.isEnabled,
                )

                Text(
                    text = network.name.ifBlank { network.networkId },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.12).sp,
                    ),
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                StatusPill(
                    status = network.status,
                    accent = statusSpec.accent,
                    pending = statusSpec.pending,
                    containerColor = statusSpec.pillContainerColor,
                    textColor = primaryTextColor,
                )

                Switch(
                    checked = network.isEnabled,
                    onCheckedChange = onToggle,
                    enabled = !switchDisabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorScheme.onPrimary,
                        checkedTrackColor = statusSpec.accent,
                        uncheckedThumbColor = colorScheme.outline,
                        uncheckedTrackColor = colorScheme.surfaceContainerHighest,
                        disabledCheckedThumbColor = colorScheme.onSurface.copy(alpha = 0.42f),
                        disabledCheckedTrackColor = colorScheme.onSurface.copy(alpha = 0.18f),
                        disabledUncheckedThumbColor = colorScheme.onSurface.copy(alpha = 0.26f),
                        disabledUncheckedTrackColor = colorScheme.onSurface.copy(alpha = 0.10f),
                    ),
                    modifier = Modifier
                        .height(28.dp)
                        .padding(start = 4.dp),
                )
            }

            ItemDivider(
                modifier = Modifier.fillMaxWidth(),
                horizontalPadding = 0.dp,
                alpha = 0.28f,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaField(
                    label = "NETWORK ID",
                    value = network.networkId,
                    valueColor = primaryTextColor.copy(alpha = 0.92f),
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .height(34.dp)
                        .align(Alignment.CenterVertically)
                        .background(colorScheme.outlineVariant.copy(alpha = 0.32f)),
                )

                MetaField(
                    label = "ASSIGNED IP",
                    value = ipText.ifBlank {
                        when {
                            !network.isEnabled -> "—"
                            network.status == NetworkStatus.CONNECTED -> "No address"
                            network.status == NetworkStatus.NO_CONNECTION -> "No link"
                            else -> "Pending…"
                        }
                    },
                    valueColor = if (hasIp) {
                        primaryTextColor.copy(alpha = 0.92f)
                    } else {
                        subtleTextColor
                    },
                    valueFontWeight = if (hasIp) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
            }

            ItemDivider(
                modifier = Modifier.fillMaxWidth(),
                horizontalPadding = 0.dp,
                alpha = 0.24f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (network.isLan) {
                    LanChip(
                        tint = semantic.connected,
                    )
                }
                if (network.p2pSummary.isNotBlank()) {
                    P2pChip(
                        summary = network.p2pSummary,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                val hintRes = when (network.status) {
                    NetworkStatus.CONNECTED -> R.string.network_status_hint_connected
                    NetworkStatus.MONITORING -> R.string.network_status_hint_monitoring
                    NetworkStatus.REQUESTING_CONFIGURATION -> R.string.network_status_hint_requesting_configuration
                    NetworkStatus.AUTHENTICATION_REQUIRED -> R.string.network_status_hint_authentication_required
                    NetworkStatus.NO_CONNECTION -> R.string.network_status_hint_no_connection
                    NetworkStatus.DISCONNECTED -> R.string.network_status_hint_disconnected
                    NetworkStatus.ACCESS_DENIED -> R.string.network_status_hint_access_denied
                    NetworkStatus.NOT_FOUND -> R.string.network_status_hint_not_found
                }
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
        }
    }
}

/**
 * 计算激活态背景混合比例。
 *
 * 设计目标：
 * 1. 非激活态与激活态至少有明确视觉差异；
 * 2. 暗色主题下适度提升混合比例，避免色彩被背景吞没；
 * 3. 当 tint 与 base 亮度过近时自动再加一档，防止主题切换后发灰发淡。
 */
private fun computeActiveBackgroundMix(
    base: Color,
    tint: Color,
    isDarkThemeSurface: Boolean,
    tone: StatusVisualTone,
): Float {
    // 背景只需"微感知"，颜色信息交给 Dot 和 Pill 传达
    val toneBaseMix = when (tone) {
        StatusVisualTone.CONNECTED -> if (isDarkThemeSurface) 0.09f else 0.06f
        StatusVisualTone.MONITORING -> if (isDarkThemeSurface) 0.11f else 0.08f
        StatusVisualTone.PENDING -> if (isDarkThemeSurface) 0.10f else 0.07f
        StatusVisualTone.OFFLINE -> if (isDarkThemeSurface) 0.09f else 0.06f
        StatusVisualTone.INACTIVE -> if (isDarkThemeSurface) 0.07f else 0.05f
        StatusVisualTone.ERROR -> if (isDarkThemeSurface) 0.12f else 0.09f
    }
    // 亮度差小时不要加混合比，而是让 Dot 颜色去做补偿
    // 这里只做微调防止完全无感
    val luminanceGap = abs(base.luminance() - tint.luminance())
    val boost = when {
        luminanceGap < 0.08f -> 0.02f   // 原来 0.08f，方向改为只微补
        luminanceGap < 0.14f -> 0.01f
        else -> 0f
    }
    val (minMix, maxMix) = when (tone) {
        StatusVisualTone.CONNECTED -> 0.05f to 0.12f
        StatusVisualTone.MONITORING -> 0.06f to 0.14f
        StatusVisualTone.PENDING -> 0.06f to 0.13f
        StatusVisualTone.OFFLINE -> 0.05f to 0.12f
        StatusVisualTone.INACTIVE -> 0.04f to 0.10f
        StatusVisualTone.ERROR -> 0.07f to 0.15f
    }
    return (toneBaseMix + boost).coerceIn(minMix, maxMix)
}

@Composable
private fun StatusDot(tint: Color, glowing: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (glowing) {
            // 光晕稍微大一点，让 Dot 更有存在感，弥补背景收敛后的感知差
            Box(
                modifier = Modifier
                    .size(ZtTheme.dimen.space16)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(tint.copy(alpha = 0.28f)) // 原 0.22f
            )
        }
        Box(
            modifier = Modifier
                .size(ZtTheme.dimen.space8)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(tint)
        )
    }
}

@Composable
private fun StatusPill(
    status: NetworkStatus,
    accent: Color,
    pending: Boolean,
    containerColor: Color,
    textColor: Color,
) {
    val label = when (status) {
        NetworkStatus.CONNECTED -> stringResource(R.string.network_status_chip_connected)
        NetworkStatus.MONITORING -> stringResource(R.string.network_status_chip_monitoring)
        NetworkStatus.REQUESTING_CONFIGURATION -> stringResource(R.string.network_status_chip_requesting_configuration)
        NetworkStatus.AUTHENTICATION_REQUIRED -> stringResource(R.string.network_status_chip_authentication_required)
        NetworkStatus.NO_CONNECTION -> stringResource(R.string.network_status_chip_no_connection)
        NetworkStatus.DISCONNECTED -> stringResource(R.string.network_status_chip_disconnected)
        NetworkStatus.ACCESS_DENIED -> stringResource(R.string.network_status_chip_access_denied)
        NetworkStatus.NOT_FOUND -> stringResource(R.string.network_status_chip_not_found)
    }

    Pill(
        containerColor = containerColor,
        borderWidth = 0.5.dp,
        borderColor = textColor.copy(alpha = 0.28f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(9.dp),
                color = accent,
                strokeWidth = 1.4.dp,
                trackColor = accent.copy(alpha = 0.2f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.sp,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaField(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    valueFontWeight: FontWeight = FontWeight.Medium,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                letterSpacing = 0.65.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = valueFontWeight,
                letterSpacing = 0.12.sp,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LanChip(tint: Color) {
    val colorScheme = MaterialTheme.colorScheme
    Pill(
        containerColor = tint.copy(alpha = 0.11f),
        borderWidth = 0.5.dp,
        borderColor = tint.copy(alpha = 0.36f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Home,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = tint,
        )
        Text(
            text = stringResource(R.string.network_lan),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurface,
        )
    }
}

@Composable
private fun P2pChip(summary: String) {
    val colorScheme = MaterialTheme.colorScheme
    Pill(
        containerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
