package io.github.jimmy.ztlink.service.policy

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
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
    suspend fun evaluate(
        reason: String,
        observedTransport: NetworkTransport? = null,
        observedWifiSsid: String? = null,
    ): RoutePolicyDecision {
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
        val configuredSsid = normalizeSsid(settings.probeWifiSsid)
        if (configuredSsid.isBlank()) {
            return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "probe_wifi_ssid_not_configured",
                enabled = true,
            )
        }
        when (observedTransport) {
            NetworkTransport.CELLULAR,
            NetworkTransport.ETHERNET,
            -> {
                return RoutePolicyDecision(
                    action = RoutePolicyAction.RESUME_RELAY,
                    inIntranet = false,
                    detail = "observed_transport_not_wifi,transport:$observedTransport,reason:$reason",
                    enabled = true,
                )
            }

            NetworkTransport.NONE -> {
                return RoutePolicyDecision(
                    action = RoutePolicyAction.KEEP_RUNNING,
                    inIntranet = null,
                    detail = "observed_transport_none,reason:$reason",
                    enabled = true,
                )
            }

            NetworkTransport.WIFI,
            NetworkTransport.VPN,
            NetworkTransport.UNKNOWN,
            null,
            -> Unit
        }
        val capabilities = resolveActiveUnderlyingNetworkCapabilities() ?: return RoutePolicyDecision(
            action = RoutePolicyAction.KEEP_RUNNING,
            inIntranet = null,
            detail = "no_active_underlying_network",
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
        val currentSsid = normalizeSsid(observedWifiSsid.orEmpty())
            .takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
            ?: currentWifiSsid(capabilities)
            ?: return RoutePolicyDecision(
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
    private fun currentWifiSsid(capabilities: NetworkCapabilities? = null): String? {
        val activeCapabilities = capabilities ?: resolveActiveUnderlyingNetworkCapabilities() ?: return null
        if (!activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
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
        val normalized = readSsidFromTransportInfo(activeCapabilities)
            ?: normalizeSsid(wifiManager.connectionInfo?.ssid.orEmpty())
        if (normalized.isBlank() || normalized.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return normalized
    }

    private fun readSsidFromTransportInfo(capabilities: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(wifiInfo.ssid.orEmpty()).takeIf { it.isNotBlank() }
    }

    /**
     * 解析当前真实底层网络能力。
     *
     * 关键原因：
     * - VPN 建立后 `activeNetwork` 可能变成 VPN 自身；
     * - SSID 策略必须读取 Wi-Fi/蜂窝/以太网这类底层网络，否则会把 VPN 误判为非 Wi-Fi，
     *   进而错误恢复或无法进入仅监听。
     */
    private fun resolveActiveUnderlyingNetworkCapabilities(): NetworkCapabilities? {
        val activeCapabilities = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.takeIf { it.isUsableUnderlyingNetwork() }
        if (activeCapabilities != null) {
            return activeCapabilities
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }
        return connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
            .filter { it.isUsableUnderlyingNetwork() }
            .minByOrNull { it.transportPriority() }
    }

    private fun normalizeSsid(value: String): String {
        return value.trim().trim('"')
    }
}

/**
 * 路由策略协调器。
 */
@Singleton
class RoutePolicyCoordinator @Inject constructor(
    private val evaluator: RoutePolicyEvaluator,
    private val networkChangeObserver: NetworkChangeObserver,
) {

    /** 自动检测互斥锁。 */
    private val runningMutex: Mutex = Mutex()

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
     * @return 启动阶段策略检查结果。
     */
    suspend fun checkStartPolicy(
        reason: String,
    ): StartPolicyCheckResult {
        logChain("启动策略检查开始 原因=$reason")
        val decision = evaluator.evaluate(
            reason = reason,
            observedTransport = null,
            observedWifiSsid = networkChangeObserver.currentWifiSsid(),
        )
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
    ): Boolean {
        return checkStartPolicy(
            reason = reason,
        ).shouldEnterMonitorOnly
    }

    /**
     * 触发自动路由策略复检。
     *
     * @param reason 触发原因。
     */
    suspend fun triggerAutoRoutePolicyCheck(reason: String) {
        triggerAutoRoutePolicyCheck(
            reason = reason,
            observedTransport = null,
        )
    }

    /**
     * 触发自动路由策略复检。
     *
     * @param reason 触发原因。
     * @param observedTransport 网络观察器已解析出的目标传输类型；可空时由评估器自行读取系统网络。
     */
    suspend fun triggerAutoRoutePolicyCheck(
        reason: String,
        observedTransport: NetworkTransport?,
        observedWifiSsid: String? = null,
    ) {
        val liveWifiSsid = when (observedTransport) {
            NetworkTransport.WIFI -> networkChangeObserver.currentWifiSsid() ?: observedWifiSsid
            else -> observedWifiSsid
        }
        logChain("自动策略复检开始 原因=$reason 观察传输=${observedTransport ?: "none"} 观察SSID=${liveWifiSsid ?: "none"}")
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
            val decision = evaluator.evaluate(
                reason = reason,
                observedTransport = observedTransport,
                observedWifiSsid = liveWifiSsid,
            )
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
                    if (delegate.isMonitorOnlyMode()) {
                        logChain("自动策略跳过进入仅监听 原因=$reason 详情=already_monitor_only")
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
    }
}

/** 判断该网络是否适合作为 VPN 底层网络。 */
private fun NetworkCapabilities.isUsableUnderlyingNetwork(): Boolean {
    return hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/** 网络优先级：Wi-Fi/以太网优先，其次蜂窝，其他网络靠后。 */
private fun NetworkCapabilities.transportPriority(): Int {
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 10
    }
}
