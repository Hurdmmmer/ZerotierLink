package io.github.jimmy.ztlink.app.utils

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 应用内运行时权限统一声明。
 *
 * 说明：
 * - 权限字符串集中管理，避免散落在页面层。
 * - 对外只暴露业务语义，不让页面关心版本分支。
 */
object AppPermissions {

    /**
     * 扫描 WiFi SSID 所需权限集合。
     *
     * 说明：
     * - Android 13+：同时申请 nearby + fine location，兼容不同 ROM 对 scanResults/ssid 的权限判定。
     * - Android 12 及以下：申请 fine location。
     */
    val wifiScanPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * 兼容旧调用方：单权限入口。
     * 非 WiFi 场景仍可使用。
     */
    val wifiScan: String
        get() = wifiScanPermissions.first()
}

/**
 * 运行时权限状态容器。
 *
 * @property isGranted 当前权限组是否全部授权。
 */
@Stable
class PermissionState internal constructor(
    initialGranted: Boolean,
) {
    var isGranted by mutableStateOf(initialGranted)
        internal set

    internal var launcher: (() -> Unit)? = null

    /**
     * 发起系统权限申请。
     */
    fun request() {
        launcher?.invoke()
    }
}

/**
 * 单权限重载，保持旧调用兼容。
 */
@Composable
fun rememberPermissionState(permission: String): PermissionState {
    return rememberPermissionState(arrayOf(permission))
}

/**
 * 多权限版本。
 *
 * @param permissions 需要同时满足的权限集合。
 */
@Composable
fun rememberPermissionState(permissions: Array<String>): PermissionState {
    val context = LocalContext.current

    val stablePermissions = remember(permissions.contentHashCode()) {
        permissions.distinct().toTypedArray()
    }

    val initialGranted = remember(stablePermissions.contentHashCode()) {
        stablePermissions.all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    val state = remember(stablePermissions.contentHashCode()) {
        PermissionState(initialGranted = initialGranted)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        state.isGranted = stablePermissions.all { permission ->
            result[permission] ?: (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    state.launcher = { launcher.launch(stablePermissions) }
    return state
}
