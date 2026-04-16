package io.github.jimmy.ztlink.app.ui.components.network.join

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.AppTopBar
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.components.common.ObserveUiEvents
import io.github.jimmy.ztlink.app.ui.components.network.DnsMode
import io.github.jimmy.ztlink.app.ui.components.network.NetworksUiEvent
import io.github.jimmy.ztlink.app.ui.components.network.NetworksViewModel
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsSectionCard
import io.github.jimmy.ztlink.app.ui.theme.ZerotierLinkShapes
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme
import kotlinx.coroutines.launch

private const val DEFAULT_ROUTE_PREF_KEY = "default_route_enabled_networks"
private const val NETWORK_ID_LENGTH = 16

private fun Char.isHexDigit(): Boolean {
    val lower = lowercaseChar()
    return this in '0'..'9' || lower in 'a'..'f'
}

private fun isValidIpAddress(input: String): Boolean {
    val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    val ipv6  = Regex("""^[0-9a-fA-F:]{2,39}$""")
    return ipv4.matches(input) || ipv6.matches(input)
}

/**
 * 校验自定义 DNS 输入组是否合法。
 *
 * 规则（对齐老项目）：
 * 1. CUSTOM 模式允许 4 个输入框都为空（仍可加入网络）；
 * 2. 只要某个输入框非空，就必须是合法 IPv4/IPv6；
 * 3. 任意一个非空输入非法，则整体判定为非法。
 *
 * @param values 4 个 DNS 输入框的当前值。
 * @return true 表示允许提交加入网络；false 表示存在非法输入。
 */
private fun isCustomDnsGroupValid(values: List<String>): Boolean {
    return values.all { value ->
        val trimmed = value.trim()
        trimmed.isEmpty() || isValidIpAddress(trimmed)
    }
}

@Composable
fun JoinNetworkScreen(
    viewModel: NetworksViewModel               = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val dimen        = ZtTheme.dimen
    val context      = LocalContext.current
    val focusManager = LocalFocusManager.current

    val networkIdInteraction = remember { MutableInteractionSource() }
    val networkIdFocused     by networkIdInteraction.collectIsFocusedAsState()
    val listState            = rememberLazyListState()

    val defaultRoutePrefs = remember(context) {
        context.getSharedPreferences("join_network_prefs", Context.MODE_PRIVATE)
    }

    var networkId    by rememberSaveable { mutableStateOf("") }
    var defaultRoute by rememberSaveable { mutableStateOf(false) }
    var dnsMode      by rememberSaveable { mutableStateOf(DnsMode.NONE) }
    var customDnsV4_1 by rememberSaveable { mutableStateOf("") }
    var customDnsV4_2 by rememberSaveable { mutableStateOf("") }
    var customDnsV6_1 by rememberSaveable { mutableStateOf("") }
    var customDnsV6_2 by rememberSaveable { mutableStateOf("") }

    val customDnsInputs = listOf(customDnsV4_1, customDnsV4_2, customDnsV6_1, customDnsV6_2)

    val networkIdValid = networkId.length == NETWORK_ID_LENGTH && networkId.all { it.isHexDigit() }
    val networkIdError = networkId.isNotBlank() && !networkIdValid
    val customDnsValid = dnsMode != DnsMode.CUSTOM || isCustomDnsGroupValid(customDnsInputs)
    val canJoin = networkIdValid && customDnsValid

    // 一行观察：自动处理 Toast，手动处理跳转
    ObserveUiEvents(viewModel.uiEvents) { event ->
        if (event is NetworksUiEvent.NavigateBack) onBack()
    }

    LaunchedEffect(networkId, networkIdValid) {
        if (!networkIdValid) { defaultRoute = false; return@LaunchedEffect }
        val saved = defaultRoutePrefs.getStringSet(DEFAULT_ROUTE_PREF_KEY, emptySet()).orEmpty()
        defaultRoute = saved.contains(networkId)
    }

    Scaffold(
        modifier       = Modifier.fillMaxSize(),
        containerColor = ZtTheme.background.baseColor,
        topBar = {
            AppTopBar(
                title          = stringResource(R.string.network_join),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint               = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->

        BouncyOverScroll(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                // 点击空白区域清除焦点（收起软键盘）。
                // 这里使用 Tap 手势而非 clickable，避免与拖拽反馈手势产生冲突。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus(force = true) })
                },
        ) {
            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimen.space16),
                contentPadding = PaddingValues(
                    top    = dimen.space8,
                    bottom = innerPadding.calculateBottomPadding() + dimen.space32,
                ),
                verticalArrangement = Arrangement.spacedBy(dimen.space16),
            ) {

                // ── 卡片 1：Network ID ────────────────────────────────
                // 与 SettingScreen 的 Planet URL 输入框完全一致的写法：
                // SettingsSectionCard 包裹，内部直接 padding + OutlinedTextField
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_id_label)) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = dimen.space16,
                                vertical   = dimen.space12,
                            ),
                        ) {
                            OutlinedTextField(
                                value         = networkId,
                                onValueChange = { input ->
                                    val filtered = input.filter { it.isHexDigit() }.lowercase()
                                    if (filtered.length <= NETWORK_ID_LENGTH) networkId = filtered
                                },
                                modifier          = Modifier.fillMaxWidth(),
                                shape             = ZerotierLinkShapes.medium,
                                interactionSource = networkIdInteraction,
                                label = { Text(stringResource(R.string.network_id_hint)) },
                                placeholder = {
                                    Text(
                                        text       = stringResource(R.string.enter_network_id_label),
                                        fontFamily = FontFamily.Monospace,
                                        color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f
                                        ),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector        = Icons.Outlined.Hub,
                                        contentDescription = null,
                                        modifier           = Modifier.size(dimen.space20),
                                        tint = when {
                                            networkIdError   -> MaterialTheme.colorScheme.error
                                            networkIdFocused -> MaterialTheme.colorScheme.primary
                                            else             -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                supportingText = {
                                    Row(
                                        modifier              = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text  = if (networkIdError)
                                                stringResource(R.string.network_id_error)
                                            else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            text  = "${networkId.length} / $NETWORK_ID_LENGTH",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                networkIdValid -> MaterialTheme.colorScheme.primary
                                                networkIdError -> MaterialTheme.colorScheme.error
                                                else           -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                },
                                isError         = networkIdError,
                                singleLine      = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    keyboardType   = KeyboardType.Ascii,
                                    imeAction      = ImeAction.Done,
                                ),
                                // 与 SettingScreen 里 PlanetSourceDialog 的输入框颜色完全一致
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant,
                                    focusedLabelColor       = MaterialTheme.colorScheme.primary,
                                    unfocusedLabelColor     = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    errorBorderColor        = MaterialTheme.colorScheme.error,
                                ),
                            )
                        }
                    }
                }

                // ── 卡片 2：路由 & DNS 配置 ───────────────────────────
                // 与 SettingScreen 通用设置卡片完全相同的结构：
                // SettingsSectionCard > SettingSwitchRow > Divider > ThemeRow风格DNS选择
                item {
                    SettingsSectionCard(title = stringResource(R.string.network_config_label)) {

                        // Default Route 开关
                        // 直接复用 SettingScreen 里 SettingSwitchRow 完全相同的内部实现
                        JoinSwitchRow(
                            title           = stringResource(R.string.network_default_route),
                            summary         = stringResource(R.string.network_default_route_summary),
                            checked         = defaultRoute,
                            enabled         = networkIdValid,
                            onCheckedChange = { checked ->
                                defaultRoute = checked
                                val set = defaultRoutePrefs
                                    .getStringSet(DEFAULT_ROUTE_PREF_KEY, emptySet())
                                    .orEmpty().toMutableSet()
                                if (checked) set.add(networkId) else set.remove(networkId)
                                defaultRoutePrefs.edit {
                                    putStringSet(DEFAULT_ROUTE_PREF_KEY, set)
                                }
                            },
                        )

                        // 分割线：与 SettingItemDivider 完全一致
                        HorizontalDivider(
                            modifier  = Modifier.padding(horizontal = dimen.space16),
                            thickness = 0.5.dp,
                            color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )

                        // DNS 模式选择
                        // 与 SettingScreen ThemeAndDynamicRow 里的三段选择写法完全一致：
                        // Column > titleMedium 标题 > Row > Box(clip + background + clickable)
                        Column(modifier = Modifier.padding(vertical = dimen.space12)) {
                            Text(
                                text     = stringResource(R.string.network_dns_mode),
                                style    = MaterialTheme.typography.titleMedium,
                                color    = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(
                                    start  = dimen.space16 + 4.dp,
                                    bottom = dimen.space8,
                                ),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dimen.space32)
                                    .padding(horizontal = dimen.space16)
                                    .alpha(if (networkIdValid) 1f else 0.38f),
                                horizontalArrangement = Arrangement.spacedBy(dimen.space8),
                            ) {
                                DnsMode.entries.forEach { mode ->
                                    val isSelected = mode == dnsMode
                                    val label = when (mode) {
                                        DnsMode.NONE    -> stringResource(R.string.dns_mode_none)
                                        DnsMode.NETWORK -> stringResource(R.string.dns_mode_network)
                                        DnsMode.CUSTOM  -> stringResource(R.string.dns_mode_custom)
                                    }
                                    val scope = rememberCoroutineScope()
                                    val scale = remember { Animatable(1f) }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .scale(scale.value)
                                            .clip(ZerotierLinkShapes.medium)
                                            .background(
                                                if (isSelected)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                                        alpha = 0.7f
                                                    ),
                                            )
                                            .clickable {
                                                if (!networkIdValid) return@clickable
                                                scope.launch {
                                                    scale.animateTo(
                                                        0.9f,
                                                        spring(stiffness = Spring.StiffnessHigh),
                                                    )
                                                    scale.animateTo(
                                                        1f,
                                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                                    )
                                                }
                                                dnsMode = mode
                                            },
                                    ) {
                                        Text(
                                            text  = label,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold
                                                else FontWeight.Normal,
                                            ),
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // Custom DNS 输入框：AnimatedVisibility 展开/收起。
                        // 这里对齐老项目能力，提供 4 个输入位（IPv4 x2 + IPv6 x2）。
                        AnimatedVisibility(
                            visible = dnsMode == DnsMode.CUSTOM && networkIdValid,
                            enter   = expandVertically(),
                            exit    = shrinkVertically(),
                        ) {
                            Column {
                                HorizontalDivider(
                                    modifier  = Modifier.padding(horizontal = dimen.space16),
                                    thickness = 0.5.dp,
                                    color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = dimen.space16,
                                        vertical   = dimen.space12,
                                    ),
                                ) {
                                    val dnsLabels = listOf("IPv4 #1", "IPv4 #2", "IPv6 #1", "IPv6 #2")
                                    val dnsValues = customDnsInputs
                                    val dnsOnValueChange = listOf<(String) -> Unit>(
                                        { customDnsV4_1 = it },
                                        { customDnsV4_2 = it },
                                        { customDnsV6_1 = it },
                                        { customDnsV6_2 = it },
                                    )

                                    // 关键逻辑：
                                    // - CUSTOM 模式允许全部为空；
                                    // - 任意单个输入非空时，必须单独通过 IP 校验。
                                    dnsValues.forEachIndexed { index, value ->
                                        val trimmed = value.trim()
                                        val isError = trimmed.isNotEmpty() && !isValidIpAddress(trimmed)
                                        OutlinedTextField(
                                            value         = value,
                                            onValueChange = dnsOnValueChange[index],
                                            modifier      = Modifier.fillMaxWidth(),
                                            shape         = ZerotierLinkShapes.medium,
                                            label         = {
                                                Text("${stringResource(R.string.dns_custom_label)} ${dnsLabels[index]}")
                                            },
                                            placeholder   = {
                                                Text(
                                                    text  = stringResource(R.string.dns_custom_placeholder),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector        = Icons.Outlined.Dns,
                                                    contentDescription = null,
                                                    modifier           = Modifier.size(dimen.space20),
                                                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            isError = isError,
                                            supportingText = {
                                                Text(
                                                    text  = if (isError) {
                                                        stringResource(R.string.dns_custom_error)
                                                    } else {
                                                        // 与老项目行为对齐：自定义 DNS 可为空。
                                                        stringResource(R.string.dns_custom_optional_hint)
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isError) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                            },
                                            singleLine      = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Ascii,
                                                imeAction    = if (index == dnsValues.lastIndex) ImeAction.Done else ImeAction.Next,
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor      = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant,
                                                focusedLabelColor       = MaterialTheme.colorScheme.primary,
                                                unfocusedLabelColor     = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        // DNS 模式说明文字（NONE / NETWORK 时展示）
                        // 直接 bodySmall + padding，与 SettingScreen 的 dynamicColor hint 一致
                        AnimatedVisibility(
                            visible = dnsMode != DnsMode.CUSTOM,
                            enter   = expandVertically(),
                            exit    = shrinkVertically(),
                        ) {
                            val hint = when (dnsMode) {
                                DnsMode.NONE    -> stringResource(R.string.dns_mode_none_hint)
                                DnsMode.NETWORK -> stringResource(R.string.dns_mode_network_hint)
                                else            -> ""
                            }
                            if (hint.isNotBlank()) {
                                Text(
                                    text     = hint,
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        horizontal = dimen.space16,
                                        vertical   = dimen.space8,
                                    ),
                                )
                            }
                        }
                    }
                }

                // ── Join 按钮 ──────────────────────────────────────────
                // 与设置页主操作风格对齐
                item {
                    JoinButton(
                        enabled = canJoin,
                        onClick = {
                            viewModel.joinNetwork(
                                networkId = networkId,
                                defaultRoute = defaultRoute,
                                dnsMode = dnsMode,
                                customDnsList = customDnsInputs,
                            )
                        },
                    )
                }
            }
        }
    }
}

// ── 内部子组件 ────────────────────────────────────────────────────────────

/**
 * 与 SettingScreen SettingSwitchRow 完全相同的实现。
 * Surface(onClick) + scale 动画 + Row(title/summary Column + Switch)。
 */
@Composable
private fun JoinSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dimen        = ZtTheme.dimen
    val scope        = rememberCoroutineScope()
    val scale        = remember { Animatable(1f) }
    val contentAlpha = if (enabled) 1f else 0.38f

    Surface(
        onClick = {
            if (!enabled) return@Surface
            scope.launch {
                scale.animateTo(0.95f, spring(stiffness = Spring.StiffnessHigh))
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            onCheckedChange(!checked)
        },
        color    = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale.value),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = dimen.space16,
                vertical   = dimen.space12 + 2.dp,
            ),
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
                modifier        = Modifier.padding(start = dimen.space12),
            )
        }
    }
}

/**
 * Join 按钮。
 * extraLarge 圆角 + primary 色，与 MD3 FilledButton 规范一致。
 * scale 动画与 SettingScreen 里的 SettingSwitchRow / AccentPresetSelector 保持相同手感。
 */
@Composable
private fun JoinButton(enabled: Boolean, onClick: () -> Unit) {
    val dimen = ZtTheme.dimen
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }

    androidx.compose.material3.Button(
        onClick = {
            scope.launch {
                scale.animateTo(0.96f, spring(stiffness = Spring.StiffnessHigh))
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            onClick()
        },
        enabled  = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale.value),
        shape    = MaterialTheme.shapes.extraLarge,
        colors   = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor         = MaterialTheme.colorScheme.primary,
            contentColor           = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(vertical = dimen.space12),
    ) {
        Text(
            text  = stringResource(R.string.network_join),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}
