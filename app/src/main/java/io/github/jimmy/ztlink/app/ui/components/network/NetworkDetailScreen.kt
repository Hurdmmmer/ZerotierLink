package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ItemDivider
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsSectionCard
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    networkId: String,
    viewModel: NetworksViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = uiState.details[networkId] ?: return
    val planetRouteText = uiState.planetRootServerIp?.let {
        stringResource(R.string.network_planet_route_root_server_ip, it)
    } ?: when (uiState.planetRouteType) {
        PlanetRouteType.OFFICIAL -> stringResource(R.string.network_planet_route_official)
        PlanetRouteType.NON_OFFICIAL -> stringResource(R.string.network_planet_route_non_official)
    }

    val dimen = ZtTheme.dimen
    val titleText = detail.name.ifBlank { detail.networkId }
    val subtitleText = detail.networkId
        .takeIf { detail.name.isNotBlank() && detail.name != detail.networkId }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ZtTheme.background.baseColor),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            AppTopBar(
                title = titleText,
                subtitle = subtitleText ?: "",
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimen.space16),
                verticalArrangement = Arrangement.spacedBy(dimen.space16),
                contentPadding = PaddingValues(
                    top = dimen.space8,
                    bottom = dimen.space16,
                ),
            ) {

                // ── 卡片 1：分配 IP ───────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_assigned_ips)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = dimen.space16,
                                    end = dimen.space16,
                                    top = dimen.space16,
                                    bottom = dimen.space12,
                                ),
                            verticalArrangement = Arrangement.spacedBy(dimen.space4),
                        ) {
                            if (detail.assignedIps.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.network_no_ip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.6f),
                                )
                            } else {
                                detail.assignedIps.forEach { ip ->
                                    // IP 地址用 bodyLarge + monospace + primary，
                                    // 视觉权重明显强于下方 MetaLine 的 bodySmall，
                                    // 用户扫视时第一眼就落在 IP 上
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            StatusRow(status = detail.status, isLan = detail.isLan)
                        }
                    }
                }

                // ── 卡片 2：网络信息 ──────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_info_label)) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 技术标识符（name / id / mac / mtu）统一用 monospace + primary，
                            // 与卡片 1 的 IP 保持同一视觉语言：蓝紫色 = 可寻址的技术标识符
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_name_label),
                                value = detail.name.ifBlank { "-" },
                                valueStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_id_label),
                                value = detail.networkId,
                                valueStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                            ItemDivider()
                            // type 是描述性文字，onSurface 默认色即可
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_type_label),
                                value = detail.type,
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_mac_label),
                                value = detail.mac,
                                valueStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_mtu_label),
                                value = detail.mtu.toString(),
                                valueStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_broadcast_label),
                                value = if (detail.broadcastEnabled) {
                                    stringResource(R.string.network_activation_active)
                                } else {
                                    stringResource(R.string.network_activation_inactive)
                                },
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_bridging_label),
                                value = if (detail.bridgingEnabled) {
                                    stringResource(R.string.network_activation_active)
                                } else {
                                    stringResource(R.string.network_activation_inactive)
                                },
                            )
                        }
                    }
                }

                // ── 卡片 3：配置 ──────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_config_label)) {

                        val onToggleRouteViaZeroTier: (Boolean) -> Unit = { enabled ->
                            viewModel.toggleRouteViaZeroTier(detail.networkId, enabled)
                        }

                        // Toggle 行：垂直 padding 加到 space16，增大点击热区
                        // Switch.onCheckedChange = null，事件由外层 toggleable 统一派发，
                        // 避免点击 Switch 本体时触发两次状态变更
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = detail.defaultRoute,
                                    role = Role.Switch,
                                    onValueChange = onToggleRouteViaZeroTier,
                                )
                                .padding(
                                    horizontal = dimen.space16,
                                    vertical = dimen.space16,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.network_default_route),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
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
                                onCheckedChange = null,
                                modifier = Modifier.padding(start = dimen.space12),
                            )
                        }

                        ItemDivider()

                        NetworkDetailMetaLine(
                            label = stringResource(R.string.network_dns_mode),
                            value = when (detail.dnsMode) {
                                DnsMode.NONE    -> stringResource(R.string.dns_mode_none)
                                DnsMode.NETWORK -> stringResource(R.string.dns_mode_network)
                                DnsMode.CUSTOM  -> stringResource(R.string.dns_mode_custom)
                                else            -> "-"
                            },
                        )

                        // DNS 服务器列表区域：用 surfaceContainerHighest 做底色，
                        // 与上方 MetaLine 行产生轻微色阶差，视觉上区分"1 label → N 地址"的列表块
                        if (detail.dnsServers.isNotEmpty()) {
                            ItemDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                            .copy(alpha = 0.5f),
                                    )
                                    .padding(
                                        horizontal = dimen.space16,
                                        vertical = dimen.space12,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.network_dns_servers),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                detail.dnsServers.forEach { dns ->
                                    Text(
                                        text = dns,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
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

// ── 私有子组件 ─────────────────────────────────────────────────────────────

/**
 * 详情页专用左右布局 MetaLine。
 *
 * 不复用公共 MetaLine（它是 Row + spacedBy，label 与 value 紧靠在一起），
 * 这里改为 label 靠左 + value 靠右的两端对齐布局：
 *   label  ············  value
 * label 占固定宽度比例（weight(1f)），value 靠右对齐（weight(1f) + textAlign End），
 * 两侧都能截断省略，不会互相挤压。
 */
@Composable
private fun NetworkDetailMetaLine(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodySmall,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val dimen = ZtTheme.dimen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimen.space16, vertical = dimen.space12),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            modifier = Modifier
                .weight(1f)
                .padding(start = dimen.space8),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * 状态 Pill + LAN 标签行。
 *
 * 颜色策略（对齐 mockup）：
 * - CONNECTED     → semantic.connected 淡背景 + 原色前景，跟随主题色
 * - REQUESTING    → secondaryContainer / onSecondaryContainer
 * - AUTH_REQUIRED → primaryContainer / onPrimaryContainer
 * - NO_CONNECTION → surfaceContainerHighest / onSurfaceVariant
 * - DISCONNECTED  → surfaceVariant / onSurfaceVariant（纯中性）
 * - ACCESS_DENIED / NOT_FOUND → errorContainer / onErrorContainer
 * - LAN           → primaryContainer / onPrimaryContainer，与 IP 同色相
 */
@Composable
private fun StatusRow(status: NetworkStatus, isLan: Boolean) {
    val dimen = ZtTheme.dimen
    val semantic = ZtTheme.semantic

    Row(
        horizontalArrangement = Arrangement.spacedBy(dimen.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (bg, fg, label) = when (status) {
            NetworkStatus.CONNECTED -> Triple(
                semantic.connected.copy(alpha = 0.15f),
                semantic.connected,
                stringResource(R.string.network_status_connected),
            )
            NetworkStatus.MONITORING -> Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
                stringResource(R.string.network_status_monitoring),
            )
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
            NetworkStatus.NO_CONNECTION -> Triple(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurfaceVariant,
                stringResource(R.string.network_status_no_connection),
            )
            NetworkStatus.DISCONNECTED -> Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                stringResource(R.string.network_status_disconnected),
            )
            NetworkStatus.ACCESS_DENIED -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                stringResource(R.string.network_status_access_denied),
            )
            NetworkStatus.NOT_FOUND -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                stringResource(R.string.network_status_not_found),
            )
        }

        Pill(
            containerColor = bg,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = fg,
            )
        }

        if (isLan) {
            Pill(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.network_lan),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
