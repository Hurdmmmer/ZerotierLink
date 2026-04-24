package io.github.jimmy.ztlink.app.ui.components.moons

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun MoonCard(
    moon: MoonListItem,
    onCopyMoonWorldId: () -> Unit,
    onDeleteCache: () -> Unit,
    onDeleteMoon: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val semantic = ZtTheme.semantic
    val sourceTint = if (moon.sourceType == MoonSourceType.FILE) semantic.root else semantic.connected
    val cacheTint = if (moon.cacheState == MoonCacheState.CACHED) semantic.connected else colors.onSurfaceVariant
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            color = colors.outlineVariant.copy(alpha = 0.35f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = sourceTint,
                            shape = CircleShape,
                        ),
                )

                Text(
                    text = moon.moonWorldIdHex,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Pill(
                    containerColor = sourceTint.copy(alpha = 0.12f),
                    borderColor = sourceTint.copy(alpha = 0.35f),
                    borderWidth = 0.5.dp,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (moon.sourceType == MoonSourceType.FILE) {
                            stringResource(R.string.moon_source_file)
                        } else {
                            stringResource(R.string.moon_source_orbit)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.1.sp,
                        ),
                        color = sourceTint,
                        maxLines = 1,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.moon_actions_more),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.moon_copy_world_id)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onCopyMoonWorldId()
                            },
                        )
                        if (moon.canDeleteCache) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.moon_delete_cache)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteCache()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.moon_delete_orbit),
                                    color = colors.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = colors.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteMoon()
                            },
                        )
                    }
                }
            }

            ItemDivider(
                modifier = Modifier.fillMaxWidth(),
                horizontalPadding = 0.dp,
                alpha = 0.26f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MoonMetaField(
                    label = stringResource(R.string.moon_seed_label_short),
                    value = moon.moonSeedHex,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .widthIn(min = 0.5.dp)
                        .height(28.dp)
                        .background(colors.outlineVariant.copy(alpha = 0.26f)),
                )
                MoonMetaField(
                    label = stringResource(R.string.moon_cache_status_label),
                    value = if (moon.cacheState == MoonCacheState.CACHED) {
                        stringResource(R.string.moon_cache_cached)
                    } else {
                        stringResource(R.string.moon_cache_wait_to_fetch)
                    },
                    valueColor = cacheTint,
                    modifier = Modifier.weight(1f),
                    valueAlign = TextAlign.End,
                    labelAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun MoonMetaField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    labelAlign: TextAlign = TextAlign.Start,
    valueAlign: TextAlign = TextAlign.Start,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
            maxLines = 1,
            textAlign = labelAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = valueAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

