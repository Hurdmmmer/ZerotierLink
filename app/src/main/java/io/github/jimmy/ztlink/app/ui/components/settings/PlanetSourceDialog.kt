package io.github.jimmy.ztlink.app.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * Planet 来源配置弹窗。
 *
 * 视觉设计决策：
 * - 文件选择：ListItem 风格可点击行（左图标 + 标题/副标题 + 右箭头），
 *   与真正的输入框明确区分，避免两个"输入框"并排带来的视觉沉重感。
 * - 文件行和 URL 区用 surfaceContainerHighest 背景色块分组，
 *   替代 OutlinedTextField 的边框，减少灰色线条的视觉噪声。
 * - URL 输入框保留 OutlinedTextField，但去掉弹窗光晕，
 *   让焦点态的 primary 色边框成为唯一的视觉高亮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanetSourceDialog(
    initialUrl: String,
    selectedFileName: String,
    onDismiss: () -> Unit,
    onChooseLocalFile: () -> Unit,
    onConfirmUrl: (String) -> Unit,
) {
    val spacing  = ZtTheme.dimen
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl) }

    val focusManager = LocalFocusManager.current


    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
        modifier         = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { focusManager.clearFocus(force = true) }
                )
            }
            .padding(horizontal = spacing.space20),
    ) {
        Surface(
            shape          = MaterialTheme.shapes.extraLarge,
            color          = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier       = Modifier
                .fillMaxWidth()
                .widthIn(min = 280.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = spacing.space24,
                        end    = spacing.space24,
                        top    = spacing.space24,
                        bottom = spacing.space16,
                    ),
            ) {

                // ── 标题 ─────────────────────────────────────────────
                Text(
                    text  = stringResource(R.string.settings_planet_source_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(spacing.space8))

                Text(
                    text  = stringResource(R.string.settings_planet_source_dialog_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(spacing.space20))

                // ── 文件选择区 ───────────────────────────────────────
                // ListItem 风格：surfaceContainerHighest 背景色块 + 圆角，
                // 替代 OutlinedTextField 边框，视觉更轻盈
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer)  // ← 去掉 copy(alpha=0.55f)
                        .clickable(onClick = onChooseLocalFile)
                        .padding(horizontal = spacing.space16, vertical = spacing.space12),
                ) {
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (selectedFileName.isNotBlank())
                                Icons.AutoMirrored.Filled.InsertDriveFile
                            else
                                Icons.Outlined.FileOpen,
                            contentDescription = null,
                            modifier           = Modifier.size(20.dp),
                            tint = if (selectedFileName.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer,
                        )

                        Spacer(Modifier.width(spacing.space12))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = selectedFileName.ifBlank {
                                    stringResource(R.string.settings_planet_source_choose_file)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedFileName.isNotBlank())
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text  = if (selectedFileName.isNotBlank())
                                    stringResource(R.string.settings_planet_source_file_label)
                                else
                                    stringResource(R.string.settings_planet_source_file_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedFileName.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 箭头和左侧内容在同一 Row，verticalAlignment 已保证居中
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier           = Modifier.size(ZtTheme.dimen.space24),
                            tint               = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(Modifier.height(spacing.space16))

                // ── URL 区说明 ────────────────────────────────────────
                Text(
                    text  = stringResource(R.string.settings_planet_source_url_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(spacing.space8))

                // ── URL 输入框 ────────────────────────────────────────
                // 只有这里用 OutlinedTextField，边框在整个弹窗里是唯一的，
                // 不再与文件选择控件竞争视觉注意力
                OutlinedTextField(
                    value         = urlInput,
                    onValueChange = { urlInput = it },
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = MaterialTheme.shapes.medium,
                    label         = { Text(stringResource(R.string.settings_planet_source_url_label)) },
                    placeholder   = { Text(stringResource(R.string.settings_planet_source_url_hint)) },
                    leadingIcon   = {
                        Icon(
                            imageVector        = Icons.Outlined.Link,
                            contentDescription = null,
                            modifier           = Modifier.size(20.dp),
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    colors     = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedLabelColor    = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 输入框背景与弹窗容器融合，不产生"框中框"的割裂感
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )

                Spacer(Modifier.height(spacing.space20))

                // ── 底部按钮 ──────────────────────────────────────────
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_action_cancel))
                    }
                    Spacer(Modifier.width(spacing.space8))
                    TextButton(
                        onClick = { onConfirmUrl(urlInput.trim()) },
                        enabled = urlInput.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.settings_planet_source_download_and_use))
                    }
                }
            }
        }
    }
}