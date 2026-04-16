package io.github.jimmy.ztlink.app.ui.components.settings

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.BouncyOverScroll
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme
import io.github.jimmy.ztlink.app.utils.PermissionState
import kotlinx.coroutines.launch

/**
 * WiFi SSID 选择底部弹窗。
 *
 * 职责：
 * 1. 展示当前可用 WiFi 列表并提供单选。
 * 2. 在权限不足、系统定位服务关闭、列表为空时给出对应提示。
 * 3. 选中后回传 SSID，由上层决定如何存储。
 *
 * @param currentSsid 当前已保存的 SSID，用于高亮选中态。
 * @param wifiPermission WiFi 读取权限状态，包含是否授权与申请动作。
 * @param onSsidSelected 用户选中 SSID 后的回调。
 * @param onDismiss 关闭弹窗回调。
 */
@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSsidPickerSheet(
    currentSsid: String,
    wifiPermission: PermissionState,
    onSsidSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = ZtTheme.dimen
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Android 13 上仅有运行时权限仍可能拿不到扫描结果。
    // 系统定位服务总开关关闭时，scanResults 往往会返回空列表。
    val locationServiceEnabled = remember { isLocationServiceEnabled(context) }

    val connectedSsid = remember(wifiPermission.isGranted, locationServiceEnabled) {
        resolveConnectedSsid(context, wifiPermission.isGranted && locationServiceEnabled)
    }
    val scannedSsids = remember(wifiPermission.isGranted, locationServiceEnabled, connectedSsid) {
        resolveScannedSsids(
            context = context,
            hasPermission = wifiPermission.isGranted && locationServiceEnabled,
            connectedSsid = connectedSsid,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { SheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = spacing.space16),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = spacing.space24,
                    vertical = spacing.space12,
                ),
            ) {
                Text(
                    text = stringResource(R.string.settings_wifi_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_wifi_picker_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            when {
                !wifiPermission.isGranted -> NoPermissionHint(wifiPermission::request)
                !locationServiceEnabled -> LocationServiceOffHint()
                scannedSsids.isEmpty() -> EmptyWifiHint()
                else -> {
                    BouncyOverScroll(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            itemsIndexed(
                                items = scannedSsids,
                                key = { _, ssid -> ssid },
                            ) { index, ssid ->
                                val isConnected = ssid == connectedSsid
                                val isSelected = ssid == currentSsid

                                WifiSsidItem(
                                    ssid = ssid,
                                    isConnected = isConnected,
                                    isSelected = isSelected,
                                    onClick = {
                                        onSsidSelected(ssid)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个 WiFi 列表项。
 *
 * @param ssid 展示的 WiFi 名称。
 * @param isConnected 是否为当前系统已连接网络。
 * @param isSelected 是否为当前配置中已选中的网络。
 * @param onClick 点击该项时的回调。
 */
@Composable
private fun WifiSsidItem(
    ssid: String,
    isConnected: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = ZtTheme.dimen
    val scope   = rememberCoroutineScope()
    // 使用 Animatable 管理缩放动画状态，初始值为 1f
    val scale   = remember { Animatable(1f) }

    Surface(
        onClick = {
            scope.launch {
                // 活跃的弹性反馈：先快速收缩，然后弹性回弹
                // 阶段 1：快速压缩 (High Stiffness)
                scale.animateTo(0.92f, spring(stiffness = Spring.StiffnessHigh))
                // 阶段 2：带有 Bouncy 感的回弹
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            onClick()
        },
        // 已连接的网络使用更明显的背景色高亮
        color   = if (isConnected) 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) 
            else Color.Transparent,
        shape   = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space12, vertical = 2.dp)
            .scale(scale.value) // 应用缩放动画
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.space12, vertical = spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Wifi else Icons.Outlined.Wifi,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.space16),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = ssid,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        // 已连接的文字加粗
                        fontWeight = if (isConnected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isConnected) {
                    Text(
                        text = stringResource(R.string.settings_wifi_picker_connected),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 无权限提示区。
 *
 * @param onRequestPermission 用户点击授权按钮时的回调。
 */
@Composable
private fun NoPermissionHint(onRequestPermission: () -> Unit) {
    val spacing = ZtTheme.dimen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space24, vertical = spacing.space20),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.space12),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiFind,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space4),
        ) {
            Text(
                text = stringResource(R.string.settings_wifi_picker_no_permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_wifi_picker_no_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onRequestPermission, shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.settings_wifi_picker_grant))
        }
    }
}

/**
 * 系统定位服务关闭时的提示区。
 *
 * 说明：Android 10+ 常见设备在定位总开关关闭时不会返回 WiFi 扫描列表。
 */
@Composable
private fun LocationServiceOffHint() {
    val spacing = ZtTheme.dimen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space24, vertical = spacing.space20),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiFind,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_wifi_picker_location_off_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_wifi_picker_location_off_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 扫描结果为空时的提示区。
 */
@Composable
private fun EmptyWifiHint() {
    val spacing = ZtTheme.dimen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space24, vertical = spacing.space20),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
    ) {
        Icon(
            imageVector = Icons.Outlined.Wifi,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_wifi_picker_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_wifi_picker_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * BottomSheet 拖拽指示器。
 */
@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZtTheme.dimen.space12, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/**
 * 检查系统定位服务总开关是否开启。
 * Android 10+ 在多数设备上，即便权限已授权，定位总开关关闭时仍无法返回扫描列表。
 */
private fun isLocationServiceEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        locationManager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF,
        ) != Settings.Secure.LOCATION_MODE_OFF
    }
}

/**
 * 清洗 SSID 文本。
 *
 * 处理内容：去掉系统返回的双引号，并过滤空值与 <unknown ssid>。
 */
private fun String.sanitizeSsid(): String? =
    removeSurrounding("\"")
        .takeIf { it.isNotBlank() && it != "<unknown ssid>" }

/**
 * 获取当前系统已连接 WiFi 的 SSID。
 *
 * @param context 应用上下文。
 * @param hasPermission 当前是否满足读取所需权限与前置条件。
 * @return 当前连接的 SSID；获取失败时返回 null。
 */
@SuppressLint("MissingPermission", "ServiceCast")
@RequiresApi(Build.VERSION_CODES.Q)
private fun resolveConnectedSsid(context: Context, hasPermission: Boolean): String? {
    if (!hasPermission) return null
    return runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            wm.connectionInfo
        } ?: @Suppress("DEPRECATION") wm.connectionInfo

        wifiInfo?.ssid?.sanitizeSsid()
    }.getOrNull()
}

/**
 * 获取可选 WiFi 列表。
 *
 * @param context 应用上下文。
 * @param hasPermission 当前是否满足扫描所需权限与前置条件。
 * @param connectedSsid 当前已连接的 SSID，用于排序置顶。
 * @return 去重并清洗后的 SSID 列表，异常时返回空列表。
 */
@SuppressLint("MissingPermission")
private fun resolveScannedSsids(
    context: Context,
    hasPermission: Boolean,
    connectedSsid: String?,
): List<String> {
    if (!hasPermission) return emptyList()
    return runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.scanResults
            ?.mapNotNull { result ->
                val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.wifiSsid?.toString()
                } else {
                    @Suppress("DEPRECATION")
                    result.SSID
                }
                raw?.sanitizeSsid()
            }
            ?.distinct()
            ?.sortedWith(compareByDescending { it == connectedSsid })
            ?: emptyList()
    }.getOrElse { emptyList() }
}
