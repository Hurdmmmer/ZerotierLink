package io.github.jimmy.ztlink.app.ui.components.peers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.components.common.SummaryMetricCell
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun PeersScreen(
    modifier: Modifier = Modifier,
    externalBottomPadding: Dp = 0.dp,
    viewModel: PeersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    ObserveUiEvents(viewModel.uiEvents)

    // 页面可见性驱动 peers 刷新：
    // 每次进入前台都触发一次“立即 + 补刷”，避免首次快照过早导致列表长期偏少。
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenStarted()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenStopped()
        }
    }

    val subtitle = when {
        uiState.activeNetworkId.isNullOrBlank() -> stringResource(R.string.peers_subtitle_no_active_network)
        else -> stringResource(
            R.string.peers_subtitle_active_network,
            uiState.activeNetworkId!!.take(8),
            uiState.summary.totalCount,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nav_peers),
                subtitle = subtitle,
                actions = {
                    IconButton(
                        onClick = viewModel::refreshPeers,
                        enabled = !uiState.isRefreshing,
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 1.8.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.peers_action_refresh),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .background(ZtTheme.background.baseColor)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            when {
                uiState.isLoading && uiState.peers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                else -> {
                    val listBottomSpacerHeight =
                        externalBottomPadding +
                            innerPadding.calculateBottomPadding() +
                            ZtTheme.dimen.space24

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = ZtTheme.dimen.space16),
                        contentPadding = PaddingValues(
                            top = ZtTheme.dimen.space8,
                            bottom = ZtTheme.dimen.space8,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ZtTheme.dimen.space8),
                    ) {
                        item(key = "peer-summary-card") {
                            PeerSummaryCard(
                                activeNetworkId = uiState.activeNetworkId,
                                summary = uiState.summary,
                            )
                        }

                        if (uiState.peers.isEmpty()) {
                            item(key = "peer-empty-state") {
                                PeerEmptyState(
                                    activeNetworkId = uiState.activeNetworkId,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = uiState.peers,
                                key = { index, peer ->
                                    // key 含索引兜底，避免重复 peerId 导致 Compose 复用错位或条目丢失。
                                    "${peer.peerId}_${peer.endpoint ?: "relay"}_$index"
                                },
                            ) { _, peer ->
                                PeerCard(peer = peer)
                            }
                        }

                        item(key = "peer-list-bottom-spacer") {
                            Spacer(modifier = Modifier.height(listBottomSpacerHeight))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerSummaryCard(
    activeNetworkId: String?,
    summary: PeerSummary,
) {
    val colors = MaterialTheme.colorScheme
    val semantic = ZtTheme.semantic

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.peers_summary_title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SummaryCountBadge(count = summary.totalCount)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NETWORK",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.6.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurfaceVariant.copy(alpha = 0.88f),
                )
                Text(
                    text = activeNetworkId ?: stringResource(R.string.peers_summary_network_none),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.onSurface.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetricCell(
                    label = stringResource(R.string.peers_card_path_direct),
                    value = summary.directCount,
                    tint = semantic.connected,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.peers_card_path_relay),
                    value = summary.relayCount,
                    tint = semantic.relay,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.peers_card_role_planet),
                    value = summary.planetCount,
                    tint = semantic.root,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetricCell(
                    label = stringResource(R.string.peers_card_role_moon),
                    value = summary.moonCount,
                    tint = semantic.root.copy(alpha = 0.88f),
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.peers_card_role_leaf),
                    value = summary.leafCount,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.peers_summary_total_short),
                    value = summary.totalCount,
                    tint = colors.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryCountBadge(count: Int) {
    Pill(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        borderWidth = 0.5.dp,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.peers_summary_count_badge, count),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
            maxLines = 1,
        )
    }
}

@Composable
private fun PeerEmptyState(
    activeNetworkId: String?,
) {
    val title = if (activeNetworkId.isNullOrBlank()) {
        stringResource(R.string.peers_empty_title_no_network)
    } else {
        stringResource(R.string.peers_empty_title_no_data)
    }
    val body = if (activeNetworkId.isNullOrBlank()) {
        stringResource(R.string.peers_empty_body_no_network)
    } else {
        stringResource(R.string.peers_empty_body_no_data)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
