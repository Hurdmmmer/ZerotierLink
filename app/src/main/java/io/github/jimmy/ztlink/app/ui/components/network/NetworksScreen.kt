package io.github.jimmy.ztlink.app.ui.components.network

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun NetworksScreen(
    viewModel: NetworksViewModel,
    onNetworkClick: (String) -> Unit,
    onJoinNetwork: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val networks = uiState.networks
    val processingIds = uiState.processingIds
    val nodeId = ""      // ViewModel 提供
    val appVersion = "1.0.0"
    var pendingEnableNetworkId by remember { mutableStateOf<String?>(null) }

    // 与老项目保持一致：
    // 点击开启时先走系统 VPN 授权，授权通过后再真正派发 Join。
    val vpnAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val networkId = pendingEnableNetworkId
        pendingEnableNetworkId = null
        if (networkId == null) {
            return@rememberLauncherForActivityResult
        }
        val stillNeedPermission = VpnService.prepare(context) != null
        if (stillNeedPermission) {
            viewModel.notifyVpnAuthorizationRequired()
            return@rememberLauncherForActivityResult
        }
        viewModel.requestEnableNetwork(networkId)
    }

    ObserveUiEvents(viewModel.uiEvents)

    val dimen = ZtTheme.dimen

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nav_networks),
                // Node ID 截短显示在副标题，完整 ID 在底部状态栏
                subtitle = nodeId.takeIf { it.isNotBlank() }
                    ?.let { "Node · ${it.take(10)}…" }
                    ?: "",
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
        // BouncyOverScroll 只吃 top padding，不重复计算
        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .background(ZtTheme.background.baseColor)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (networks.isEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyNetworkHint(
                                onJoinNetwork = onJoinNetwork,
                                modifier = Modifier.padding(
                                    bottom = innerPadding.calculateBottomPadding(),
                                ),
                            )
                        }
                    }
                }
            } else {
                // 互斥逻辑：预先判断是否有任何网络处于开启状态
                val hasAnyEnabled = remember(networks) { networks.any { it.isEnabled } }
                val hasAnyProcessing = remember(processingIds) { processingIds.isNotEmpty() }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = dimen.space16),
                    // top = space8：TopBar 下方只留 8dp，不要过大空隙
                    contentPadding = PaddingValues(
                        top = dimen.space8,
                        bottom = innerPadding.calculateBottomPadding() + dimen.space16,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimen.space8),
                ) {
                    item(key = "planet_route_status") {
                        PlanetRouteStatusCard(
                            planetRouteType = uiState.planetRouteType,
                            rootServerIp = uiState.planetRootServerIp,
                        )
                    }

                    items(networks, key = { it.networkId }) { network ->
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            NetworkCard(
                                network = network,
                                isProcessing = processingIds.contains(network.networkId),
                                // 如果已有其他网络开启，且不是本网络，则标记为互斥
                                isAnyOtherEnabled = (hasAnyEnabled && !network.isEnabled) ||
                                        (hasAnyProcessing && !processingIds.contains(network.networkId)),
                                onToggle = { enabled ->
                                    if (!enabled) {
                                        viewModel.toggleNetwork(network.networkId, enabled = false)
                                        return@NetworkCard
                                    }
                                    val prepareIntent = VpnService.prepare(context)
                                    if (prepareIntent == null) {
                                        viewModel.requestEnableNetwork(network.networkId)
                                    } else {
                                        pendingEnableNetworkId = network.networkId
                                        vpnAuthLauncher.launch(prepareIntent)
                                    }
                                },
                                onClick = { onNetworkClick(network.networkId) },
                                onLongClick = { showMenu = true },
                            )

                            DropdownMenu(
                                expanded = showMenu,
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
                                            text = stringResource(R.string.network_delete),
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
                            nodeId = nodeId,
                            appVersion = appVersion,
                        )
                    }
                }
            }
        }
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
        modifier = modifier.padding(dimen.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimen.space12))
        Text(
            text = stringResource(R.string.network_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(dimen.space4))
        Text(
            text = stringResource(R.string.network_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimen.space20))
        androidx.compose.material3.FilledTonalButton(
            onClick = onJoinNetwork,
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(dimen.space8))
            Text(stringResource(R.string.network_join))
        }
    }
}

@Composable
private fun BottomStatusBar(
    nodeId: String,
    appVersion: String,
) {
    val dimen = ZtTheme.dimen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimen.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimen.space8),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Text(
                text = "ZeroTier Link $appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (nodeId.isNotBlank()) {
            Text(
                text = nodeId,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun PlanetRouteStatusCard(
    planetRouteType: PlanetRouteType,
    rootServerIp: String?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = ZtTheme.semantic
    val isDarkThemeSurface = colorScheme.surface.luminance() < 0.5f
    val routePillColors = remember(planetRouteType, semantic, colorScheme, isDarkThemeSurface) {
        resolveNetworkPillColors(
            semantic = planetRouteType.toPillSemantic(),
            semanticColors = semantic,
            colorScheme = colorScheme,
            isDarkSurface = isDarkThemeSurface,
        )
    }
    val accentColor = routePillColors.tone

    data class Spec(
        val icon: ImageVector,
        val badgeText: String,
        val title: String,
        val desc: String,
    )

    val spec = when (planetRouteType) {
        PlanetRouteType.OFFICIAL -> Spec(
            icon      = Icons.Outlined.Public,
            badgeText = stringResource(R.string.network_planet_route_chip_official),
            title     = rootServerIp ?: stringResource(R.string.network_planet_route_official),
            desc      = if (rootServerIp != null)
                stringResource(R.string.network_planet_route_summary_root_server_ip, rootServerIp)
            else
                stringResource(R.string.network_planet_route_summary_official),
        )
        PlanetRouteType.NON_OFFICIAL -> Spec(
            icon      = Icons.Outlined.Storage,
            badgeText = stringResource(R.string.network_planet_route_chip_non_official),
            title     = rootServerIp ?: stringResource(R.string.network_planet_route_non_official),
            desc      = if (rootServerIp != null)
                stringResource(R.string.network_planet_route_summary_root_server_ip, rootServerIp)
            else
                stringResource(R.string.network_planet_route_summary_non_official),
        )
    }

    Surface(
        modifier        = Modifier.fillMaxWidth(),
        shape           = MaterialTheme.shapes.large,
        color           = colorScheme.surfaceContainerHigh,
        tonalElevation  = 0.dp,
        shadowElevation = 0.dp,
        border          = BorderStroke(0.5.dp, colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 图标色块 — 与 Banner 保持一致
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color = Color.Transparent),  // 背景色由 Icon 的 tint 决定
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = spec.icon,
                        contentDescription = null,
                        tint               = accentColor,
                        modifier           = Modifier.size(32.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = stringResource(R.string.network_planet_panel_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text     = spec.title,
                        style    = MaterialTheme.typography.titleSmall,
                        color    = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Pill(
                    containerColor = routePillColors.container,
                    shape = CircleShape,
                    borderWidth = 0.5.dp,
                    borderColor = routePillColors.border,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = spec.badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = routePillColors.tone,
                    )
                }
            }
        }
    }
}
