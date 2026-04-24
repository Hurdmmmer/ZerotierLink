package io.github.jimmy.ztlink.service.policy

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 路由策略动作。
 */
enum class RoutePolicyAction {
    /** 保持运行状态（通常指正常的 VPN 中转状态） */
    KEEP_RUNNING,

    /** 进入仅监控模式（例如在内网环境下，关闭 VPN 链路，仅监测网络环境变化） */
    ENTER_MONITOR_ONLY,

    /** 恢复中转模式（例如离开内网后，重新建立 VPN 链路） */
    RESUME_RELAY,
}

/**
 * 路由策略决策结果。
 *
 * @property action 决策动作。
 * @property inIntranet 是否在内网；可空表示未知。
 * @property detail 诊断细节。
 * @property enabled 当前策略是否启用。
 */
data class RoutePolicyDecision(
    val action: RoutePolicyAction,
    val inIntranet: Boolean?,
    val detail: String,
    val enabled: Boolean,
)

/**
 * 启动阶段策略检查结果。
 *
 * @property shouldEnterMonitorOnly 是否应在启动前直接进入仅监控模式。
 * @property enabled 当前策略是否启用。
 * @property inIntranet 是否命中内网；可空表示未知。
 * @property detail 诊断细节。
 */
data class StartPolicyCheckResult(
    val shouldEnterMonitorOnly: Boolean,
    val enabled: Boolean,
    val inIntranet: Boolean?,
    val detail: String,
)

/**
 * 内网探测状态快照。
 *
 * @property enabled 是否启用自动探测策略。
 * @property inIntranet 是否在内网；可空表示未知。
 * @property reason 触发原因。
 * @property detail 诊断细节。
 */
data class IntranetCheckState(
    val enabled: Boolean,
    val inIntranet: Boolean?,
    val reason: String,
    val detail: String,
)

/**
 * 路由策略对 runtime 的委托能力。
 */
interface RoutePolicyRuntimeDelegate {

    /**
     * 进入仅监控模式。
     *
     * @param reason 触发原因。
     * @param detail 诊断细节。
     */
    suspend fun enterMonitorOnly(
        reason: String,
        detail: String,
    )

    /**
     * 恢复中转。
     *
     * @param reason 触发原因。
     */
    suspend fun resumeRelay(
        reason: String,
    )

    /**
     * 当前是否处于仅监控模式。
     *
     * @return 是否处于仅监控模式。
     */
    fun isMonitorOnlyMode(): Boolean

    /**
     * 当前服务是否运行中。
     *
     * @return 服务运行状态。
     */
    fun isServiceRunning(): Boolean

    /**
     * 分发内网探测状态。
     *
     * @param state 内网探测状态。
     */
    suspend fun dispatchIntranetState(
        state: IntranetCheckState,
    )
}

/**
 * 路由策略评估器。
 *
 * 策略规则：
 * 1. 用户关闭自动探测时，不干预；
 * 2. 用户配置了“内网 Wi-Fi SSID”且当前命中时，进入监控模式；
 * 3. 未命中时保持运行或触发恢复。
 */
@Singleton
class RoutePolicyEvaluator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsStateHolder: SettingsStateHolder,
) {

    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager: WifiManager =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * 执行策略评估。
     *
     * @param reason 触发原因。
     * @return 策略决策结果。
     */
    suspend fun evaluate(reason: String): RoutePolicyDecision {
        val settings = settingsStateHolder.currentState()
        if (!settings.planetUseCustom) {
            // 关键逻辑：
            // 内网 SSID 探测策略只在“自定义 Planet”链路下生效。
            // 关闭自定义 Planet 后应无条件回到 KEEP_RUNNING。
            return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "custom_planet_disabled",
                enabled = false,
            )
        }
        if (!settings.planetAutoRouteCheck) {
            return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "auto_route_check_disabled",
                enabled = false,
            )
        }
        val configuredSsid = settings.probeWifiSsid.trim()
        if (configuredSsid.isBlank()) {
            return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "probe_wifi_ssid_not_configured",
                enabled = true,
            )
        }
        val activeNetwork = connectivityManager.activeNetwork ?: return RoutePolicyDecision(
            action = RoutePolicyAction.KEEP_RUNNING,
            inIntranet = null,
            detail = "no_active_network",
            enabled = true,
        )
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return RoutePolicyDecision(
            action = RoutePolicyAction.KEEP_RUNNING,
            inIntranet = null,
            detail = "no_active_network_capabilities",
            enabled = true,
        )
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return RoutePolicyDecision(
                action = RoutePolicyAction.RESUME_RELAY,
                inIntranet = false,
                detail = "active_network_not_wifi,reason:$reason",
                enabled = true,
            )
        }
        val currentSsid = currentWifiSsid() ?: return RoutePolicyDecision(
            action = RoutePolicyAction.KEEP_RUNNING,
            inIntranet = null,
            detail = "current_wifi_unknown",
            enabled = true,
        )
        val inIntranet = currentSsid == configuredSsid
        return RoutePolicyDecision(
            action = if (inIntranet) RoutePolicyAction.ENTER_MONITOR_ONLY else RoutePolicyAction.RESUME_RELAY,
            inIntranet = inIntranet,
            detail = "ssid_match:$inIntranet,ssid:$currentSsid,reason:$reason",
            enabled = true,
        )
    }

    /**
     * 读取当前 Wi-Fi SSID。
     *
     * @return 当前 SSID；不可读时返回 null。
     */
    private fun currentWifiSsid(): String? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            return null
        }
        val raw = wifiManager.connectionInfo?.ssid ?: return null
        // Android 返回值通常带双引号，需要统一归一化。
        val normalized = raw.trim().trim('"')
        if (normalized.isBlank() || normalized.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return normalized
    }
}

/**
 * 路由策略协调器。
 */
@Singleton
class RoutePolicyCoordinator @Inject constructor(
    private val evaluator: RoutePolicyEvaluator,
) {

    /** 自动检测互斥锁。 */
    private val runningMutex: Mutex = Mutex()

    /** 手动连接保护窗口截止时间（elapsedRealtime）。 */
    private val manualProtectionDeadlineMsRef: AtomicReference<Long> = AtomicReference(0L)

    /** Runtime 委托。 */
    private val runtimeDelegateRef: AtomicReference<RoutePolicyRuntimeDelegate?> = AtomicReference(null)

    /**
     * 绑定 runtime 委托。
     *
     * @param delegate 委托实现，可空。
     */
    fun bindRuntimeDelegate(delegate: RoutePolicyRuntimeDelegate?) {
        runtimeDelegateRef.set(delegate)
        logChain("策略委托绑定 attached=${delegate != null}")
    }

    /**
     * 更新“手动连接保护窗口”截止时间。
     *
     * @param hasExplicitNetworkId 是否显式指定了网络 ID。
     * @param protectionMs 保护窗口时长（毫秒）。
     * @return 新的保护截止时间戳（毫秒）。
     */
    fun updateManualProtectionDeadline(
        hasExplicitNetworkId: Boolean,
        protectionMs: Long,
    ): Long {
        if (!hasExplicitNetworkId) {
            manualProtectionDeadlineMsRef.set(0L)
            logChain("手动保护窗口已清除 原因=implicit_start")
            return 0L
        }
        val deadline = SystemClock.elapsedRealtime() + protectionMs
        manualProtectionDeadlineMsRef.set(deadline)
        logChain("手动保护窗口已更新 截止=$deadline 时长Ms=$protectionMs")
        return deadline
    }

    /**
     * 清理手动连接保护窗口。
     */
    fun clearManualProtectionDeadline() {
        manualProtectionDeadlineMsRef.set(0L)
        logChain("手动保护窗口已清除 原因=explicit_clear")
    }

    /**
     * 重置协调器运行状态。
     */
    fun resetRunningState() {
        // 互斥锁语义下不需要额外状态复位，这里保留接口语义。
    }

    /**
     * 启动阶段策略检查。
     *
     * 关键语义：
     * 1. 该方法只做“评估 + 状态分发”，不直接执行进入监控或恢复动作；
     * 2. 由调用方（Service 主链路）在同一执行上下文内决定下一步动作，
     *    避免在动作互斥锁内出现递归调用导致的重入问题。
     *
     * @param reason 触发原因。
     * @param hasExplicitNetworkId 是否显式指定了网络 ID。
     * @return 启动阶段策略检查结果。
     */
    suspend fun checkStartPolicy(
        reason: String,
        hasExplicitNetworkId: Boolean,
    ): StartPolicyCheckResult {
        logChain("启动策略检查开始 原因=$reason 显式网络=$hasExplicitNetworkId")
        if (hasExplicitNetworkId) {
            updateManualProtectionDeadline(hasExplicitNetworkId = true, protectionMs = DEFAULT_MANUAL_PROTECTION_MS)
        }
        val decision = evaluator.evaluate(reason)
        val delegate = runtimeDelegateRef.get()
        if (delegate != null) {
            delegate.dispatchIntranetState(
                IntranetCheckState(
                    enabled = decision.enabled,
                    inIntranet = decision.inIntranet,
                    reason = reason,
                    detail = decision.detail,
                ),
            )
        } else {
            logChain("启动策略状态分发跳过 原因=$reason 详情=no_delegate")
        }
        logChain(
            "启动策略决策 原因=$reason 启用=${decision.enabled} 动作=${decision.action} 内网=${decision.inIntranet} 详情=${decision.detail}",
        )
        return StartPolicyCheckResult(
            shouldEnterMonitorOnly = decision.enabled &&
                decision.inIntranet == true &&
                decision.action == RoutePolicyAction.ENTER_MONITOR_ONLY,
            enabled = decision.enabled,
            inIntranet = decision.inIntranet,
            detail = decision.detail,
        )
    }

    /**
     * 兼容旧调用入口：
     * 仅返回“是否应进入仅监控模式”。
     */
    suspend fun handleStartPolicy(
        reason: String,
        hasExplicitNetworkId: Boolean,
    ): Boolean {
        return checkStartPolicy(
            reason = reason,
            hasExplicitNetworkId = hasExplicitNetworkId,
        ).shouldEnterMonitorOnly
    }

    /**
     * 触发自动路由策略复检。
     *
     * @param reason 触发原因。
     */
    suspend fun triggerAutoRoutePolicyCheck(reason: String) {
        logChain("自动策略复检开始 原因=$reason")
        runningMutex.withLock {
            val delegate = runtimeDelegateRef.get()
            if (delegate == null) {
                logChain("自动策略复检跳过 原因=$reason 详情=no_delegate")
                return@withLock
            }
            if (!delegate.isServiceRunning()) {
                logChain("自动策略复检跳过 原因=$reason 详情=service_not_running")
                return@withLock
            }
            val decision = evaluator.evaluate(reason)
            delegate.dispatchIntranetState(
                IntranetCheckState(
                    enabled = decision.enabled,
                    inIntranet = decision.inIntranet,
                    reason = reason,
                    detail = decision.detail,
                ),
            )
            logChain(
                "自动策略决策 原因=$reason 启用=${decision.enabled} 动作=${decision.action} 内网=${decision.inIntranet} 详情=${decision.detail}",
            )
            if (!decision.enabled || decision.inIntranet == null) {
                return@withLock
            }
            when (decision.action) {
                RoutePolicyAction.KEEP_RUNNING -> Unit
                RoutePolicyAction.ENTER_MONITOR_ONLY -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now < (manualProtectionDeadlineMsRef.get() ?: 0L)) {
                        logChain("自动策略跳过进入仅监听 原因=$reason 详情=manual_protection_window")
                        return@withLock
                    }
                    delegate.enterMonitorOnly(reason, decision.detail)
                    logChain("自动策略执行 进入仅监听 原因=$reason")
                }

                RoutePolicyAction.RESUME_RELAY -> {
                    if (delegate.isMonitorOnlyMode() && delegate.isServiceRunning()) {
                        delegate.resumeRelay(reason)
                        logChain("自动策略执行 恢复转发 原因=$reason")
                    }
                }
            }
        }
    }

    private fun logChain(message: String) {
        Log.i(TAG, "[$LOG_KEY] $message")
    }

    private companion object {
        private const val TAG = "RoutePolicyCoordinator"
        private const val LOG_KEY = "ZTL_CHAIN"
        /** 默认手动连接保护窗口时长。 */
        private const val DEFAULT_MANUAL_PROTECTION_MS: Long = 15_000L
    }
}
