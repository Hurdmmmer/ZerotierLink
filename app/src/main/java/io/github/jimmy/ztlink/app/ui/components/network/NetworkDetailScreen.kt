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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.github.jimmy.ztlink.app.ui.components.common.MetaLine
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsSectionCard
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * 网络详情页。
 *
 * @param networkId 网络 ID。
 * @param viewModel 网络页面共享 ViewModel。
 * @param onBack 返回回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    networkId: String,
    viewModel: NetworksViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 详情页面肯定不能为空的。如果为空直接返回
    val detail = uiState.details[networkId] ?: return

    val dimen = ZtTheme.dimen
    val titleText = detail.name.ifBlank { detail.networkId }
    val subtitleText = detail.networkId.takeIf { detail.name.isNotBlank() && detail.name != detail.networkId }

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
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_assigned_ips)) {
                        Column(
                            modifier = Modifier.padding(dimen.space16),
                            verticalArrangement = Arrangement.spacedBy(dimen.space8)
                        ) {
                            if (detail.assignedIps.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.network_no_ip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            } else {
                                detail.assignedIps.forEach { ip ->
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            StatusRow(status = detail.status, isLan = detail.isLan)
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = stringResource(R.string.network_info_label)) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_name_label),
                                value = detail.name.ifBlank { "-" },
                                valueStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_id_label),
                                value = detail.networkId,

                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_type_label),
                                value = detail.type,
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_mac_label),
                                value = detail.mac,
                                valueStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            ItemDivider()
                            NetworkDetailMetaLine(
                                label = stringResource(R.string.network_mtu_label),
                                value = detail.mtu.toString(),
                                valueStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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

                item {
                    SettingsSectionCard(title = stringResource(R.string.network_config_label)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = detail.defaultRoute,
                                    role = Role.Switch,
                                    onValueChange = { enabled ->
                                        viewModel.toggleRouteViaZeroTier(detail.networkId, enabled)
                                    },
                                )
                                .padding(horizontal = dimen.space16, vertical = dimen.space12),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.network_default_route),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
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
                                DnsMode.NONE -> stringResource(R.string.dns_mode_none)
                                DnsMode.NETWORK -> stringResource(R.string.dns_mode_network)
                                DnsMode.CUSTOM -> stringResource(R.string.dns_mode_custom)
                                else -> "-"
                            },
                        )

                        if (detail.dnsServers.isNotEmpty()) {
                            ItemDivider()
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = dimen.space16,
                                    vertical = dimen.space12,
                                ),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.network_dns_servers),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                detail.dnsServers.forEach { dns ->
                                    Text(
                                        text = dns,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun NetworkDetailMetaLine(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodySmall,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val dimen = ZtTheme.dimen
    MetaLine(
        label = label,
        value = value,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimen.space16, vertical = dimen.space12),
        labelStyle = MaterialTheme.typography.titleSmall,
        valueStyle = valueStyle,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        valueColor = valueColor,
        valueModifier = Modifier,
        spacing = dimen.space8,
    )
}

/** 状态 + LAN 标签行。 */
@Composable
private fun StatusRow(status: NetworkStatus, isLan: Boolean) {
    val dimen = ZtTheme.dimen
    Row(
        horizontalArrangement = Arrangement.spacedBy(dimen.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (bg, fg, label) = when (status) {
            NetworkStatus.CONNECTED -> Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
                stringResource(R.string.network_status_connected),
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
            NetworkStatus.DISCONNECTED -> Triple(
                MaterialTheme.colorScheme.surfaceContainerHighest,
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
        Surface(
            shape = CircleShape,
            color = bg,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = fg,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (isLan) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.network_lan),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
