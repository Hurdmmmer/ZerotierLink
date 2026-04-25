package io.github.jimmy.ztlink.app.ui.components.moons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.components.common.Pill
import io.github.jimmy.ztlink.app.ui.components.common.SummaryMetricCell
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

@Composable
fun MoonsScreen(
    modifier: Modifier = Modifier,
    externalBottomPadding: Dp = 0.dp,
    viewModel: MoonsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveUiEvents(viewModel.uiEvents)

    var showAddMenu by remember { mutableStateOf(false) }
    var showOrbitDialog by rememberSaveable { mutableStateOf(false) }
    var moonWorldIdInput by rememberSaveable { mutableStateOf("") }
    var moonSeedInput by rememberSaveable { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importMoonFromUri(uri)
        }
    }

    val subtitle = when {
        uiState.activeNetworkId.isNullOrBlank() -> stringResource(R.string.moons_subtitle_no_active_network)
        else -> stringResource(
            R.string.moons_subtitle_active_network,
            uiState.activeNetworkId!!.take(8),
            uiState.summary.totalCount,
        )
    }

    if (showOrbitDialog) {
        AlertDialog(
            onDismissRequest = { showOrbitDialog = false },
            title = {
                Text(text = stringResource(R.string.moon_orbit_info))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = moonWorldIdInput,
                        onValueChange = { moonWorldIdInput = it.trim() },
                        label = { Text(stringResource(R.string.moon_world_id_label)) },
                        placeholder = { Text(stringResource(R.string.moon_world_id_hint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = moonSeedInput,
                        onValueChange = { moonSeedInput = it.trim() },
                        label = { Text(stringResource(R.string.moon_seed_label)) },
                        placeholder = { Text(stringResource(R.string.moon_seed_hint)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addMoonByOrbit(
                            moonWorldIdInput = moonWorldIdInput,
                            moonSeedInput = moonSeedInput,
                        )
                        showOrbitDialog = false
                        moonWorldIdInput = ""
                        moonSeedInput = ""
                    },
                ) {
                    Text(stringResource(R.string.moon_add_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOrbitDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nav_moons),
                subtitle = subtitle,
                actions = {
                    IconButton(
                        enabled = !uiState.isOperating,
                        onClick = viewModel::refreshMoons,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.moons_action_refresh),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Box {
                        IconButton(
                            enabled = !uiState.isOperating,
                            onClick = { showAddMenu = true },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.moons_action_add),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.moon_source_orbit)) },
                                onClick = {
                                    showAddMenu = false
                                    showOrbitDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.moon_source_file)) },
                                onClick = {
                                    showAddMenu = false
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                },
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
                item(key = "moon-summary-card") {
                    MoonSummaryCard(
                        activeNetworkId = uiState.activeNetworkId,
                        summary = uiState.summary,
                    )
                }

                if (!uiState.isLoading && uiState.moons.isEmpty()) {
                    item(key = "moon-empty-state") {
                        MoonEmptyState()
                    }
                } else {
                    items(
                        items = uiState.moons,
                        key = { it.moonWorldId },
                    ) { moon ->
                        MoonCard(
                            moon = moon,
                            onCopyMoonWorldId = { viewModel.copyMoonWorldId(moon.moonWorldId) },
                            onDeleteCache = { viewModel.deleteMoonCache(moon.moonWorldId) },
                            onDeleteMoon = { viewModel.deleteMoonOrbit(moon.moonWorldId) },
                        )
                    }
                }

                item(key = "moon-list-bottom-spacer") {
                    Spacer(modifier = Modifier.height(listBottomSpacerHeight))
                }
            }
        }
    }
}

@Composable
private fun MoonSummaryCard(
    activeNetworkId: String?,
    summary: MoonSummary,
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
                    text = stringResource(R.string.moons_summary_title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MoonCountBadge(count = summary.totalCount)
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
                    text = activeNetworkId ?: stringResource(R.string.moons_summary_network_none),
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
                    label = stringResource(R.string.moon_source_file),
                    value = summary.fromFileCount,
                    tint = semantic.root,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.moon_source_orbit),
                    value = summary.fromOrbitCount,
                    tint = semantic.connected,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetricCell(
                    label = stringResource(R.string.moon_cache_cached),
                    value = summary.cachedCount,
                    tint = semantic.connected,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCell(
                    label = stringResource(R.string.moon_cache_wait_to_fetch),
                    value = summary.waitingFetchCount,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MoonCountBadge(count: Int) {
    Pill(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        borderWidth = 0.5.dp,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.moons_summary_count_badge, count),
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
private fun MoonEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
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
                text = stringResource(R.string.moons_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.moons_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

