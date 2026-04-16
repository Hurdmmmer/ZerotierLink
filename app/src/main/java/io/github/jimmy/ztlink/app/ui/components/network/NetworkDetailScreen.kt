package io.github.jimmy.ztlink.app.ui.components.network

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsSectionCard
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * 网络详情页。
 *
 * 布局：
 * - TopAppBar（支持滚动收起）
 * - 状态摘要卡片（大号 IP 展示）
 * - 静态信息卡片（ID、名称、MAC、MTU 等）
 * - 可写配置卡片（Default Route、DNS 模式）
 * - DNS 服务器列表
 *
 * @param networkId           网络 ID。
 * @param onBack              返回。
 * @param onDefaultRouteChange Default Route 开关回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    networkId: String,
    viewModel: NetworksViewModel               = hiltViewModel(),
    onBack: () -> Unit                        = {},
    onDefaultRouteChange: (Boolean) -> Unit   = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val detail = uiState.details[networkId] ?: NetworkDetail(
        networkId = networkId,
        name = networkId,
        status = NetworkStatus.DISCONNECTED,
        type = "Private",
        mac = "-",
        mtu = 2800,
        broadcastEnabled = false,
        bridgingEnabled = false,
        assignedIps = emptyList(),
        dnsServers = emptyList(),
        dnsMode = DnsMode.NETWORK,
        defaultRoute = false,
        isLan = false,
    )

    val dimen       = ZtTheme.dimen
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ZtTheme.background.baseColor)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(detail.name.ifBlank { detail.networkId })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimen.space16),
                verticalArrangement = Arrangement.spacedBy(dimen.space16),
                contentPadding = PaddingValues(vertical = dimen.space8),
            ) {

                // ── IP 地址摘要卡片 ───────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_assigned_ips)) {
                        Column(modifier = Modifier.padding(dimen.space16)) {
                            if (detail.assignedIps.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.network_no_ip),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                detail.assignedIps.forEach { ip ->
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(dimen.space8))
                            // 连接状态徽章复用
                            StatusRow(status = detail.status, isLan = detail.isLan)
                        }
                    }
                }

                // ── 网络静态信息 ──────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_info_label)) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DetailInfoRow(
                                label = stringResource(R.string.network_id_label),
                                value = detail.networkId,
                                monospace = true
                            )
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_name_label),
                                value = detail.name.ifBlank { "—" })
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_type_label),
                                value = detail.type
                            )
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_mac_label),
                                value = detail.mac,
                                monospace = true
                            )
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_mtu_label),
                                value = detail.mtu.toString()
                            )
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_broadcast_label),
                                value = if (detail.broadcastEnabled) "Enabled" else "Disabled"
                            )
                            DetailDivider()
                            DetailInfoRow(
                                label = stringResource(R.string.network_bridging_label),
                                value = if (detail.bridgingEnabled) "Enabled" else "Disabled"
                            )
                        }
                    }
                }

                // ── 可写配置 ──────────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_config_label)) {
                        // Default Route 开关（唯一可写项）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimen.space16, vertical = dimen.space12),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.network_default_route),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.network_default_route_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = detail.defaultRoute,
                        onCheckedChange = { enabled ->
                            onDefaultRouteChange(enabled)
                            viewModel.toggleNetwork(detail.networkId, enabled)
                        },
                        modifier = Modifier.padding(start = dimen.space12),
                    )
                }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = dimen.space16),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )

                        // DNS 模式（只读展示，修改需要重新加入）
                        DetailInfoRow(
                            label = stringResource(R.string.network_dns_mode),
                            value = when (detail.dnsMode) {
                                DnsMode.NONE -> "No DNS"
                                DnsMode.NETWORK -> "Network DNS"
                                DnsMode.CUSTOM -> "Custom DNS"
                                else -> {}
                            } as String,
                        )

                        // DNS 服务器列表
                        if (detail.dnsServers.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = dimen.space16),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = dimen.space16,
                                    vertical = dimen.space12
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.network_dns_servers),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(dimen.space4))
                                detail.dnsServers.forEach { dns ->
                                    Text(
                                        text = dns,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(dimen.space16)) }
            }
        }
    }
}

/** 只读信息行：label 左 + value 右，等宽字体可选 */
@Composable
private fun DetailInfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    val dimen = ZtTheme.dimen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimen.space16, vertical = dimen.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = if (monospace)
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else
                MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = ZtTheme.dimen.space16),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/** 状态 + LAN 标签行，复用列表页的 Badge 风格 */
@Composable
private fun StatusRow(status: NetworkStatus, isLan: Boolean) {
    val dimen = ZtTheme.dimen
    Row(
        horizontalArrangement = Arrangement.spacedBy(dimen.space8),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        val (bg, fg, label) = when (status) {
            NetworkStatus.CONNECTED     -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Connected")
            NetworkStatus.REQUESTING_CONFIGURATION -> Triple(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                stringResource(R.string.network_status_requesting_configuration),
            )
            NetworkStatus.AUTHENTICATION_REQUIRED -> Triple(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                stringResource(R.string.network_status_authentication_required),
            )
            NetworkStatus.DISCONNECTED  -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "Disconnected")
            NetworkStatus.ACCESS_DENIED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Access denied")
            NetworkStatus.NOT_FOUND     -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Not found")
        }
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
        }
        if (isLan) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(text = "LAN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}
