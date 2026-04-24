package io.github.jimmy.ztlink.app.ui.components.peers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun PeerCard(
    peer: PeerListItem,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val semantic = ZtTheme.semantic
    val isDark = colors.surface.luminance() < 0.5f
    val accent = remember(peer.roleType, peer.pathType, semantic) {
        when {
            peer.roleType == PeerRoleType.PLANET || peer.roleType == PeerRoleType.MOON -> semantic.root
            peer.pathType == PeerPathType.DIRECT -> semantic.connected
            else -> semantic.relay
        }
    }

    val baseBackground = colors.surfaceContainerHigh.copy(alpha = 0.95f)
    val backgroundMix = if (isDark) 0.11f else 0.08f
    val cardBackground by animateColorAsState(
        targetValue = lerp(baseBackground, accent, backgroundMix),
        animationSpec = tween(220),
        label = "peerCardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = colors.outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(220),
        label = "peerCardBorder",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cardBackground)
            .border(
                width = 0.7.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(accent)
            Text(
                text = peer.peerId,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.12).sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RolePill(
                role = peer.roleType,
                accent = accent,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PathPill(
                pathType = peer.pathType,
                accent = accent,
            )
            Spacer(modifier = Modifier.weight(1f))
            CompactInfoPill(
                text = peer.latencyMs?.let { "${it} ms" }
                    ?: stringResource(R.string.peers_card_latency_no_connection),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.72f),
                )
                Text(
                    text = peer.endpoint ?: stringResource(R.string.peers_card_path_relay_hint),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = colors.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CompactInfoPill(
                text = "v ${peer.version ?: stringResource(R.string.peers_card_version_unknown)}",
                monospace = true,
                contentAlpha = 0.86f,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(accent: Color) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(accent.copy(alpha = 0.26f)),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(accent),
        )
    }
}

@Composable
private fun RolePill(
    role: PeerRoleType,
    accent: Color,
) {
    val label = when (role) {
        PeerRoleType.PLANET -> stringResource(R.string.peers_card_role_planet)
        PeerRoleType.MOON -> stringResource(R.string.peers_card_role_moon)
        PeerRoleType.LEAF -> stringResource(R.string.peers_card_role_leaf)
        PeerRoleType.UNKNOWN -> stringResource(R.string.peers_card_role_unknown)
    }
    Pill(
        containerColor = accent.copy(alpha = 0.14f),
        borderWidth = 0.5.dp,
        borderColor = accent.copy(alpha = 0.34f),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PathPill(
    pathType: PeerPathType,
    accent: Color,
) {
    val label = when (pathType) {
        PeerPathType.DIRECT -> stringResource(R.string.peers_card_path_direct)
        PeerPathType.RELAY -> stringResource(R.string.peers_card_path_relay)
    }
    Pill(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.56f),
        borderWidth = 0.5.dp,
        borderColor = accent.copy(alpha = 0.34f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactInfoPill(
    text: String,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 0.90f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 1.5.dp),
) {
    val colors = MaterialTheme.colorScheme
    Pill(
        modifier = modifier,
        containerColor = colors.surfaceContainerHighest.copy(alpha = 0.52f),
        borderWidth = 0.5.dp,
        borderColor = colors.outlineVariant.copy(alpha = 0.38f),
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (monospace) FontFamily.Monospace else null,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.08.sp,
            ),
            color = colors.onSurface.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
