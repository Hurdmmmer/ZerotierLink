package io.github.jimmy.ztlink.app.ui.components.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.theme.AccentPreset
import io.github.jimmy.ztlink.app.ui.theme.ZerotierLinkShapes
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme
import io.github.jimmy.ztlink.app.ui.theme.accentPaletteOf
import io.github.jimmy.ztlink.app.utils.AppPermissions
import io.github.jimmy.ztlink.app.utils.rememberPermissionState
import io.github.jimmy.ztlink.data.settings.PlanetFileStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.jimmy.ztlink.app.ui.theme.LocalThemeSettings
import io.github.jimmy.ztlink.app.ui.theme.LocalUpdateTheme

/**
 * 设置页。
 *
 * 架构说明：
 * 1. 页面内部通过 Hilt 获取 [SettingsViewModel]，避免 NavHost/Activity 透传实例。
 * 2. 主题相关状态与更新通过 CompositionLocal 获取，保证主题入口统一。
 *
 * @param title 顶部标题文本。
 * @param externalBottomPadding 外层容器（含底栏）传入的底部避让值。
 * 为什么这样做：只在设置页滚动区应用该避让，避免 NavHost 整体 padding 造成底部白条。
 * @param modifier 外部修饰符。
 */
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun SettingScreen(
    title: String,
    externalBottomPadding: Dp = 0.dp,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
) {
    val settings: SettingsViewModel = hiltViewModel()
    val context = LocalContext.current
    val uiState = settings.settingUiState

    // 集中观察并处理通用 UI 事件（Toast、剪贴板等）
    ObserveUiEvents(settings.uiEvents)

    // 主题控制
    val themeSettings = LocalThemeSettings.current
    val updateTheme = LocalUpdateTheme.current

    var showPlanetSourceDialog by rememberSaveable { mutableStateOf(false) }
    val openPlanetFileLauncher = registerFileLauncher(context, settings)

    // 顶部加权限状态和 Sheet 控制
    val wifiPermission    = rememberPermissionState(AppPermissions.wifiScanPermissions)
    var showWifiPicker    by rememberSaveable { mutableStateOf(false) }

    val spacing = ZtTheme.dimen

    Scaffold(
        modifier       = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(title = title)
        },
    ) { innerPadding ->
        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top    = spacing.space8,
                    bottom = externalBottomPadding + spacing.space32
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.space16),
                verticalArrangement = Arrangement.spacedBy(spacing.space16),
            ) {
                // ── 通用设置 ─────────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.settings_section_general)) {

                        // Theme 模式 + 动态取色 合并一行
                        ThemeAndDynamicRow(
                            themeMode            = themeSettings.themeMode,
                            dynamicColor         = themeSettings.dynamicColor,
                            onThemeModeSelected  = { mode ->
                                updateTheme(themeSettings.copy(themeMode = mode))
                            },
                            onDynamicColorChanged = { enabled ->
                                updateTheme(themeSettings.copy(dynamicColor = enabled))
                            },
                        )

                        SettingItemDivider()

                        AccentPresetSelector(
                            selected   = themeSettings.accentPreset,
                            enabled    = !themeSettings.dynamicColor,
                            onSelected = { preset ->
                                updateTheme(themeSettings.copy(accentPreset = preset))
                            },
                        )

                        if (themeSettings.dynamicColor) {
                            Text(
                                text  = stringResource(R.string.settings_dynamic_color_accent_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = spacing.space16,
                                    vertical   = spacing.space8,
                                ),
                            )
                        }

                        SettingItemDivider()

                        SettingSwitchRow(
                            title           = stringResource(R.string.settings_item_start_on_boot),
                            summary         = stringResource(R.string.settings_item_start_on_boot_summary),
                            checked         = uiState.startOnBoot,
                            onCheckedChange = settings::toggleStartOnBoot,
                        )
                    }
                }

                // ── Planet 设置 ──────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.settings_section_planet)) {
                        SettingSwitchRow(
                            title           = stringResource(R.string.settings_item_use_custom_planet),
                            summary         = stringResource(R.string.settings_item_use_custom_planet_summary),
                            checked         = uiState.planetUseCustom,
                            onCheckedChange = settings::togglePlanetUseCustom,
                        )

                        SettingItemDivider()

                        // Planet 文件行
                        SettingActionRow(
                            title         = stringResource(R.string.settings_item_set_planet_file),
                            summary       = stringResource(R.string.settings_item_set_planet_file_summary),
                            trailingLabel = uiState.planetSourceDisplay.ifBlank { null },
                            enabled       = uiState.planetFileActionsEnabled,
                            onClick       = { showPlanetSourceDialog = true },
                        )
                        // Planet 来源配置弹窗
                        if (showPlanetSourceDialog) {
                            PlanetSourceDialog(
                                initialUrl = if (uiState.planetSourceType == PlanetSourceType.URL) {
                                    uiState.planetSourceRawValue
                                } else {
                                    ""
                                },
                                selectedFileName = if (uiState.planetSourceType == PlanetSourceType.FILE) {
                                    uiState.planetSourceDisplay
                                } else {
                                    ""
                                },
                                onDismiss = { showPlanetSourceDialog = false },
                                onChooseLocalFile = {
                                    // 启动文件选择器。
                                    // 注意：ZeroTier planet 文件通常是二进制流，且可能没有文件后缀，
                                    // 使用 */* 确保在所有设备上都能被用户选中。
                                    openPlanetFileLauncher.launch(arrayOf("*/*"))
                                },
                                onConfirmUrl = { url ->
                                    settings.setPlanetSourceFromUrl(url)
                                    showPlanetSourceDialog = false
                                }
                            )
                        }

                        SettingItemDivider()

                        SettingSwitchRow(
                            title           = stringResource(R.string.settings_item_auto_route_check),
                            summary         = stringResource(R.string.settings_item_auto_route_check_summary),
                            checked         = uiState.planetAutoRouteCheck,
                            enabled         = uiState.planetUseCustom,
                            onCheckedChange = settings::togglePlanetAutoRouteCheck,
                        )

                        // Planet 卡片内，替换原来的 OutlinedTextField Box：
                        SettingItemDivider()
                        // WiFi SSID 行
                        SettingActionRow(
                            title         = stringResource(R.string.settings_item_probe_wifi_ssid),
                            summary       = stringResource(R.string.settings_item_probe_wifi_ssid_summary),
                            trailingLabel = uiState.probeWifiSsid.ifBlank { null },
                            trailingIcon  = Icons.Outlined.Wifi,
                            enabled       = uiState.planetProbeIpEnabled,
                            onClick       = { showWifiPicker = true },
                        )
                        // Scaffold 外：
                        if (showWifiPicker) {
                            WifiSsidPickerSheet(
                                currentSsid    = uiState.probeWifiSsid,
                                wifiPermission = wifiPermission,
                                onSsidSelected = { ssid ->
                                    settings.updateProbeWifiSsid(ssid)
                                },
                                onDismiss = { showWifiPicker = false },
                            )
                        }
                    }
                }

                // ── 网络设置 ─────────────────────────────────────────────
                item {
                    SettingsSectionCard(title = stringResource(R.string.settings_section_network)) {
                        SettingSwitchRow(
                            title           = stringResource(R.string.settings_item_use_cellular_data),
                            summary         = stringResource(R.string.settings_item_use_cellular_data_summary),
                            checked         = uiState.useCellularData,
                            onCheckedChange = settings::toggleUseCellularData,
                        )

                        SettingItemDivider()

                        SettingSwitchRow(
                            title           = stringResource(R.string.settings_item_disable_ipv6),
                            summary         = stringResource(R.string.settings_item_disable_ipv6_summary),
                            checked         = uiState.disableIpv6,
                            onCheckedChange = settings::toggleDisableIpv6,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 注册文件选择器。
 */
@Composable
private fun registerFileLauncher(
    context: Context,
    settings: SettingsViewModel
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    // 预先获取备用文件名，因为 stringResource 不能在 ActivityResult 的回调中使用
    val fallbackName = stringResource(R.string.settings_planet_source_file_fallback_name)

    // 注册系统文件选择器：用于从设备存储中导入 Planet 配置文件
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // 解析选中文件的显示名称（如 planet），若解析失败则使用备用名
            val fileName = resolveFileDisplayName(context, it) ?: fallbackName
            settings.setPlanetSourceFromFile(
                displayName = fileName,
                rawUri = it.toString()
            )
        }
    }
}

// ── 内部辅助组件 ──────────────────────────────────────────────────────────

/**
 * 设置项之间的分割线。
 *
 * 设计意图：
 * 1. 使用低对比度细线分隔信息块，减少卡片内部“堆叠感”。
 * 2. 水平留白与内容区域对齐，保证视觉网格一致。
 */
@Composable
private fun SettingItemDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = ZtTheme.dimen.space16),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * 主题模式三段选择 + 动态取色开关，合并一行。
 *
 * MD3 推荐：
 * - 模式选择用 SegmentedButton 语义：选中项 primaryContainer，未选中 surfaceVariant
 * - 动态取色直接用文字 label + Switch，不加额外图标
 * - 按钮高度收紧到 icon(16dp) + label(11sp) + spacing(4dp) + padding(8dp*2) ≈ 47dp，与 Switch 行高对齐
 *
 * @param themeMode 当前主题模式（系统/浅色/深色）。
 * @param dynamicColor 是否启用动态取色（Android 12+）。
 * @param onThemeModeSelected 主题模式切换回调。
 * @param onDynamicColorChanged 动态取色开关变更回调。
 */
@Composable
private fun ThemeAndDynamicRow(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
) {
    val spacing = ZtTheme.dimen

    Column(modifier = Modifier.padding(vertical = spacing.space12)) {
        Text(
            text  = stringResource(R.string.settings_theme_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start  = spacing.space16 + 4.dp,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space16), // 保持行间距一致
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            // 左侧：三段式主题模式选择
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ZtTheme.dimen.space32), // 【关键】设为 32dp，正好对应 Switch 的视觉轨道高度
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            ) {
                ThemeMode.entries.forEach { mode ->
                    val isSelected = mode == themeMode
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_mode_system)
                        ThemeMode.LIGHT  -> stringResource(R.string.theme_mode_light)
                        ThemeMode.DARK   -> stringResource(R.string.theme_mode_dark)
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight() // 填满这 32dp 的指定高度
                            .clip(ZerotierLinkShapes.medium)
                            .background(
                                if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                            )
                            .clickable { onThemeModeSelected(mode) }
                    ) {
                        Text(
                            text  = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 竖向分割线，高度也设为 20dp 居中，这样更精致
            VerticalDivider(
                modifier = Modifier.height(ZtTheme.dimen.space24),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 右侧：动态取色控制
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            ) {
                Text(
                    text  = stringResource(R.string.settings_dynamic_color_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 原生的 Switch (系统会自动垂直居中)
                Switch(
                    checked         = dynamicColor,
                    onCheckedChange = onDynamicColorChanged,
                )
            }
        }
    }
}


/**
 * 强调色预设选择器。
 *
 * 交互说明：
 * 1. 使用圆形色块表达不同强调色预设。
 * 2. 点击时执行轻量按压动画，提升反馈感。
 * 3. 被选中项显示勾选图标，并提升边框对比度。
 *
 * @param selected 当前选中的强调色预设。
 * @param enabled 是否可交互；动态取色开启时通常禁用。
 * @param onSelected 选中某个预设时的回调。
 */
@Composable
private fun AccentPresetSelector(
    selected: AccentPreset,
    enabled: Boolean,
    onSelected: (AccentPreset) -> Unit,
) {
    val spacing = ZtTheme.dimen

    Column(
        modifier = Modifier
            .padding(horizontal = spacing.space16, vertical = spacing.space12)
            .alpha(if (enabled) 1f else 0.38f),
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
    ) {
        Text(
            text  = stringResource(R.string.settings_accent_preset),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        LazyRow(
            // 给首尾 item 预留安全边距，避免缩放动画在边缘被裁切。
            contentPadding = PaddingValues(horizontal = spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space8 + 2.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            items(AccentPreset.entries) { preset ->
                val palette    = accentPaletteOf(preset)
                val isSelected = preset == selected

                // 用 Animatable 控制 scale，点击时手动触发动画序列
                val scaleAnim = remember { Animatable(1f) }
                val scope     = rememberCoroutineScope()

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .scale(scaleAnim.value)
                        .clip(CircleShape)
                        .background(palette.primary)
                        .clickable(enabled = enabled) {
                            scope.launch {
                                // 阶段 1：高刚度下潜，确保瞬间点击也有可见反馈
                                scaleAnim.animateTo(
                                    targetValue   = 0.82f,
                                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                                )
                                // 阶段 2：带有 Bouncy 感的回弹
                                scaleAnim.animateTo(
                                    targetValue   = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness    = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                            onSelected(preset)
                        }
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            shape = CircleShape,
                        ),
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector        = Icons.Default.Check,
                            contentDescription = null,
                            tint     = if (palette.primary.luminance() > 0.35f)
                                Color(0xFF1A1A1A) else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 通用“标题 + 描述 + Switch”设置行。
 *
 * @param title 主标题文本。
 * @param summary 描述文本。
 * @param checked 当前开关状态。
 * @param enabled 是否可交互。
 * @param onCheckedChange 开关状态变更回调。
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing      = ZtTheme.dimen
    val contentAlpha = if (enabled) 1f else 0.38f
    val scope        = rememberCoroutineScope()
    // 增加按压缩放动画，提升反馈感
    val scale        = remember { Animatable(1f) }

    Surface(
        onClick = {
            if (enabled) {
                scope.launch {
                    // 阶段 1：高刚度下潜，确保瞬间点击也有可见反馈
                    scale.animateTo(0.95f, spring(stiffness = Spring.StiffnessHigh))
                    // 阶段 2：带有 Bouncy 感的回弹
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
                onCheckedChange(!checked)
            }
        },
        color    = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale.value) // 应用动画缩放
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.space16, vertical = spacing.space12 + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    text  = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
            Switch(
                checked         = checked,
                enabled         = enabled,
                onCheckedChange = onCheckedChange,
                modifier        = Modifier.padding(start = spacing.space12),
            )
        }
    }
}

/**
 * 通用"标题 + 描述 + 右箭头"动作行。
 *
 * 布局：左侧标题列（title + summary），右侧 trailingLabel（已配置时展示当前值）+ 箭头。
 * 所有内容在同一行，不换行不堆叠，信息密度合理。
 *
 * @param title         主标题。
 * @param summary       副标题，trailingLabel 存在时仍显示（两者不互斥）。
 * @param trailingLabel 右侧附加信息（如已选中的 SSID），用 primary 色区分，比 summary 视觉权重高。
 * @param enabled       是否可点击。
 * @param onClick       点击回调。
 */
@Composable
private fun SettingActionRow(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailingLabel: String?     = null,
    trailingIcon: ImageVector? = null,
) {
    val spacing      = ZtTheme.dimen
    val contentAlpha = if (enabled) 1f else 0.38f
    val scope        = rememberCoroutineScope()
    // 增加按压缩放动画，提升反馈感
    val scale        = remember { Animatable(1f) }

    Surface(
        onClick = {
            if (enabled) {
                scope.launch {
                    // 阶段 1：快速下潜反馈
                    scale.animateTo(0.95f, spring(stiffness = Spring.StiffnessHigh))
                    // 阶段 2：弹性回弹
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
                onClick()
            }
        },
        color    = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale.value)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.space16, vertical = spacing.space12),
            verticalAlignment = Alignment.CenterVertically,  // 箭头与左侧内容整体居中
        ) {
            // 左侧内容：两行（title + trailingLabel / summary）
            Column(modifier = Modifier.weight(1f)) {

                // ── 第一行：title + trailingLabel ────────────────────────
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = title,
                        style    = MaterialTheme.typography.titleMedium,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (trailingLabel != null) {
                        Spacer(Modifier.width(spacing.space8))
                        // 状态标签采用 "Pill/Chip" 设计，突出显示当前配置状态
                        Surface(
                            color  = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            shape  = CircleShape,
                            border = BorderStroke(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.widthIn(max = 140.dp)
                        ) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier              = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                if (trailingIcon != null) {
                                    Icon(
                                        imageVector        = trailingIcon,
                                        contentDescription = null,
                                        modifier           = Modifier.size(12.dp),
                                        tint               = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                                    )
                                }
                                Text(
                                    text     = trailingLabel,
                                    style    = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color    = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // ── 第二行：summary ───────────────────────────────────────
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.85f),
                )
            }

            // 箭头：在外层 Row 里，与左侧 Column 整体垂直居中
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier           = Modifier
                    .padding(start = spacing.space8)
                    .size(ZtTheme.dimen.space24),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.5f),
            )
        }
    }
}

/**
 * 从 Uri 中解析可读文件名，用于设置页展示。
 */
private fun resolveFileDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return null
}
