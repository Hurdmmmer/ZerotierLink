package io.github.jimmy.ztlink.service.policy

import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 路由策略动作。
 */
enum class RoutePolicyAction {
    KEEP_RUNNING,
    ENTER_MONITOR_ONLY,
    RESUME_RELAY,
}

data class RoutePolicyDecision(
    val action: RoutePolicyAction,
    val inIntranet: Boolean?,
    val detail: String,
    val enabled: Boolean,
)

data class StartPolicyCheckResult(
    val shouldEnterMonitorOnly: Boolean,
    val enabled: Boolean,
    val inIntranet: Boolean?,
    val detail: String,
)

data class IntranetCheckState(
    val enabled: Boolean,
    val inIntranet: Boolean?,
    val reason: String,
    val detail: String,
)

interface RoutePolicyRuntimeDelegate {
    suspend fun enterMonitorOnly(reason: String, detail: String)
    suspend fun resumeRelay(reason: String)
    fun isMonitorOnlyMode(): Boolean
    fun isServiceRunning(): Boolean
    suspend fun dispatchIntranetState(state: IntranetCheckState)
}

/**
 * 路由策略评估器。
 *
 * 策略规则：
 * 1. 用户关闭自动探测时，不干预；
 * 2. 用户配置了“内网探测 IP”且当前 Wi-Fi IP 与其在同网段时，进入监控模式；
 * 3. 非 Wi-Fi 或不在同网段时恢复中转。
 */
@Singleton
class RoutePolicyEvaluator @Inject constructor(
    private val settingsStateHolder: SettingsStateHolder,
) {
    /**
     * 读取当前配置的内网探测 IP（归一化后）。
     *
     * 说明：
     * - 仅用于调试日志观测，避免每层重复读取 Settings。
     */
    fun currentConfiguredProbeIpv4(): String? {
        return normalizeIpv4(settingsStateHolder.currentState().planetIntranetProbeIp)
    }


    /**
     * 自动路由网络监听是否应启用。
     *
     * 规则：
     * 1. 必须启用自定义 Planet；
     * 2. 必须打开自动路由探测。
     */
    fun isNetworkObserverEnabled(): Boolean {
        val settings = settingsStateHolder.currentState()
        return settings.planetUseCustom && settings.planetAutoRouteCheck
    }

    /**
     * 评估当前网络环境下的路由策略决策。
     */
    fun evaluate(
        reason: String,
        observedTransport: NetworkTransport? = null,
        observedWifiIpv4: String? = null,
        observedWifiPrefixLength: Int? = null,
    ): RoutePolicyDecision {
        val settings = settingsStateHolder.currentState()
        if (!settings.planetUseCustom) {
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
        val configuredProbeIp = normalizeIpv4(settings.planetIntranetProbeIp)
            ?: return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "probe_ip_not_configured",
                enabled = true,
            )
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
                    action = RoutePolicyAction.RESUME_RELAY,
                    inIntranet = false,
                    detail = "observed_transport_none_resume,reason:$reason",
                    enabled = true,
                )
            }

            NetworkTransport.WIFI,
            NetworkTransport.VPN,
            NetworkTransport.UNKNOWN,
            null,
            -> Unit
        }

        val currentWifiIpv4 = normalizeIpv4(observedWifiIpv4)
            ?: return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "current_wifi_ip_unknown",
                enabled = true,
            )
        val prefixLength = observedWifiPrefixLength?.coerceIn(0, IPV4_MAX_PREFIX)
            ?: return RoutePolicyDecision(
                action = RoutePolicyAction.KEEP_RUNNING,
                inIntranet = null,
                detail = "current_wifi_prefix_unknown",
                enabled = true,
            )
        val inIntranet = isInSameSubnet(
            leftIpv4 = configuredProbeIp,
            rightIpv4 = currentWifiIpv4,
            prefixLength = prefixLength,
        )
        return RoutePolicyDecision(
            action = if (inIntranet) RoutePolicyAction.ENTER_MONITOR_ONLY else RoutePolicyAction.RESUME_RELAY,
            inIntranet = inIntranet,
            detail = "ip_match:$inIntranet,current:$currentWifiIpv4,configured:$configuredProbeIp,prefix:$prefixLength,reason:$reason",
            enabled = true,
        )
    }
}

@Singleton
class RoutePolicyService @Inject constructor(
    private val evaluator: RoutePolicyEvaluator,
    private val networkChangeObserver: NetworkChangeObserver,
) {

    private val runtimeDelegateRef: AtomicReference<RoutePolicyRuntimeDelegate?> = AtomicReference(null)
    private val policyMutex: Mutex = Mutex()

    fun bindRuntimeDelegate(delegate: RoutePolicyRuntimeDelegate?) {
        runtimeDelegateRef.set(delegate)
        logChain("策略委托绑定 attached=${delegate != null}")
    }

    /**
     * 自动路由网络监听是否应启用。
     *
     * 规则：
     * 1. 必须启用自定义 Planet；
     * 2. 必须打开自动路由探测。
     */
    fun shouldEnableNetworkObserver(): Boolean {
        return evaluator.isNetworkObserverEnabled()
    }

    fun checkStartPolicy(reason: String): StartPolicyCheckResult {
        logChain("启动策略检查开始 原因=$reason")
        val transport = networkChangeObserver.currentTransport()
        val wifiInfo = networkChangeObserver.currentWifiIpv4Info()
        val decision = evaluator.evaluate(
            reason = reason,
            observedTransport = transport,
            observedWifiIpv4 = wifiInfo?.address,
            observedWifiPrefixLength = wifiInfo?.prefixLength,
        )
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

    suspend fun triggerAutoRoutePolicyCheck(
        reason: String,
        observedTransport: NetworkTransport?,
        observedWifiIpv4: String? = null,
        observedWifiPrefixLength: Int? = null,
    ) {
        policyMutex.withLock {
            // 策略判定优先使用本次网络事件参数，仅在事件缺失时回退到实时查询值。
            val realtimeTransport = networkChangeObserver.currentTransport()
            val realtimeWifiInfo = networkChangeObserver.currentWifiIpv4Info()
            val transport = observedTransport ?: realtimeTransport
            val currentWifiIpv4 = when {
                transport == NetworkTransport.WIFI && !observedWifiIpv4.isNullOrBlank() -> observedWifiIpv4
                transport == NetworkTransport.WIFI -> realtimeWifiInfo?.address
                else -> null
            }
            val currentWifiPrefixLength = when {
                transport == NetworkTransport.WIFI && observedWifiPrefixLength != null -> observedWifiPrefixLength
                transport == NetworkTransport.WIFI -> realtimeWifiInfo?.prefixLength
                else -> null
            }
            val configuredProbeIpv4 = evaluator.currentConfiguredProbeIpv4()
            logChain(
                "自动策略实时网络 原因=$reason 配置IP=${configuredProbeIpv4 ?: "none"} 当前WiFiIP=${currentWifiIpv4 ?: "none"} 前缀=${currentWifiPrefixLength ?: -1} 使用传输=$transport 实时传输=$realtimeTransport 事件传输=${observedTransport ?: "none"} 事件IP=${observedWifiIpv4 ?: "none"} 事件前缀=${observedWifiPrefixLength ?: -1}",
            )
            logChain(
                "自动策略复检开始 原因=$reason 观察传输=$transport 观察IP=${currentWifiIpv4 ?: "none"} 前缀=${currentWifiPrefixLength ?: -1}",
            )
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
                observedTransport = transport,
                observedWifiIpv4 = currentWifiIpv4,
                observedWifiPrefixLength = currentWifiPrefixLength,
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
                    if (!delegate.isMonitorOnlyMode() || !delegate.isServiceRunning()) {
                        return@withLock
                    }
                    delegate.resumeRelay(reason)
                    logChain("自动策略执行 恢复转发 原因=$reason")
                }
            }
        }
    }

    private fun logChain(message: String) {
        ChainLog.i(TAG, message)
    }

    private companion object {
        private const val TAG = "RoutePolicyService"
    }
}

private fun normalizeIpv4(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) {
        return null
    }
    val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
    return if (address is Inet4Address && address.hostAddress == value) value else null
}

private fun isInSameSubnet(
    leftIpv4: String,
    rightIpv4: String,
    prefixLength: Int,
): Boolean {
    val leftBytes = runCatching { InetAddress.getByName(leftIpv4).address }.getOrNull() ?: return false
    val rightBytes = runCatching { InetAddress.getByName(rightIpv4).address }.getOrNull() ?: return false
    if (leftBytes.size != 4 || rightBytes.size != 4) {
        return false
    }
    val normalizedPrefix = prefixLength.coerceIn(0, IPV4_MAX_PREFIX)
    val leftInt = bytesToInt(leftBytes)
    val rightInt = bytesToInt(rightBytes)
    val mask = if (normalizedPrefix == IPV4_MAX_PREFIX) {
        -1
    } else {
        (-1 shl (IPV4_MAX_PREFIX - normalizedPrefix))
    }
    return (leftInt and mask) == (rightInt and mask)
}

private fun bytesToInt(address: ByteArray): Int {
    return ((address[0].toInt() and 0xFF) shl 24) or
        ((address[1].toInt() and 0xFF) shl 16) or
        ((address[2].toInt() and 0xFF) shl 8) or
        (address[3].toInt() and 0xFF)
}

private const val IPV4_MAX_PREFIX = 32
