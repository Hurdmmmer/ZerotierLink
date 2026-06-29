package io.github.jimmy.ztlink.service.policy

import android.os.SystemClock
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
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
 * 2. 实时链路为 WiFi 且其自身 IP 与配置的「内网探测 IP」同网段时，进入监控模式（省电暂停转发）；
 * 3. 其余一切情况（蜂窝 / 无网 / 模糊不清）恢复中转。
 *
 * 实现要点：
 * - 内网判定**只基于执行时刻的实时链路**（[NetworkChangeObserver.currentTransport] +
 *   [NetworkChangeObserver.currentWifiIpv4Info]，由调用方读取后通过 [evaluate] 参数传入），
 *   本评估器是纯函数，不直接触碰 ConnectivityManager，便于单测；
 * - **设计底线：内网判定只是省电优化，判错只会「该省电时没省电」（继续转发，无害）。**
 *   失败方向一律为 false（保持转发），**绝不返回 null**，保证不会因判定误差而暂停转发导致不通。
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
     *
     * 设计原则（关键）：
     * 内网判定**只是「要不要为省电而暂停转发」的优化**，判错最多是「该暂停时没暂停」——
     * 内核继续转发，完全无害。因此本判定的失败方向必须是「保持转发（RESUME_RELAY）」，
     * 绝不能因判定不准而暂停转发导致 app 完全不通。
     *
     * 判定一律基于**实时链路**（由调用方传入的 [observedTransport]/[observedWifiIpv4]/
     * [observedWifiPrefixLength]，对应 [NetworkChangeObserver.currentTransport] /
     * [NetworkChangeObserver.currentWifiIpv4Info] 的执行时刻读数）：
     * - 仅当「实时传输=WIFI 且该 WiFi 自身 IP 与探测 IP 同网段」→ 命中内网 → ENTER_MONITOR_ONLY；
     * - 其余一切情况（蜂窝 / 无网 / UNKNOWN / VPN / WiFi 但拿不到 IP / 不同网段 / 模糊不清）
     *   → 非内网 → RESUME_RELAY，保持转发。
     *
     * 不再使用滞后的 best-matching 物理默认锚点：抖动期它会把已切到 4G 的设备误读为
     * 滞留 WiFi，从而误判内网、停转发、4G 下彻底不通。
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

        val detection = detectIntranetByRealtimeLink(
            configuredProbeIp = configuredProbeIp,
            transport = observedTransport,
            wifiIpv4 = observedWifiIpv4,
            wifiPrefixLength = observedWifiPrefixLength,
        )
        return RoutePolicyDecision(
            action = if (detection.inIntranet) {
                RoutePolicyAction.ENTER_MONITOR_ONLY
            } else {
                RoutePolicyAction.RESUME_RELAY
            },
            inIntranet = detection.inIntranet,
            detail = "${detection.detail},configured:$configuredProbeIp,reason:$reason" +
                ",transport:${observedTransport ?: "none"}",
            enabled = true,
        )
    }

    /**
     * 实时链路内网探测结果。
     */
    private data class RealtimeIntranetDetection(
        val inIntranet: Boolean,
        val detail: String,
    )

    /**
     * 基于**实时链路**判定是否处于内网。
     *
     * 规则（失败方向一律 false=保持转发）：
     * 1. 实时传输非 WIFI（蜂窝 / 无网 / UNKNOWN / VPN / 以太网等）→ false；
     * 2. WIFI 但拿不到自身 IPv4 / 前缀 → false（模糊不清，保持转发）；
     * 3. WIFI 且自身 IPv4 与探测 IP 同网段 → true（命中内网，进监控）；
     * 4. WIFI 但不同网段 / 前缀非法 → false。
     *
     * **绝不返回 null**：任何不确定都判 false，保证不会因判定误差而暂停转发。
     */
    private fun detectIntranetByRealtimeLink(
        configuredProbeIp: String,
        transport: NetworkTransport?,
        wifiIpv4: String?,
        wifiPrefixLength: Int?,
    ): RealtimeIntranetDetection {
        if (transport != NetworkTransport.WIFI) {
            return RealtimeIntranetDetection(false, "not_wifi:${transport ?: "none"}")
        }
        val localIpv4 = wifiIpv4
            ?: return RealtimeIntranetDetection(false, "wifi_no_ipv4")
        val prefixLength = wifiPrefixLength
            ?: return RealtimeIntranetDetection(false, "wifi_no_prefix")
        return if (isInSameSubnet(
                leftIpv4 = configuredProbeIp,
                rightIpv4 = localIpv4,
                prefixLength = prefixLength,
            )
        ) {
            RealtimeIntranetDetection(
                inIntranet = true,
                detail = "same_subnet_via_wifi,local:$localIpv4/$prefixLength",
            )
        } else {
            RealtimeIntranetDetection(
                inIntranet = false,
                detail = "different_subnet_wifi,local:$localIpv4/$prefixLength",
            )
        }
    }
}

@Singleton
class RoutePolicyService @Inject constructor(
    private val evaluator: RoutePolicyEvaluator,
    private val networkChangeObserver: NetworkChangeObserver,
) {

    private val runtimeDelegateRef: AtomicReference<RoutePolicyRuntimeDelegate?> = AtomicReference(null)
    private val policyMutex: Mutex = Mutex()

    /**
     * 手动连接保护窗口截止时间（基于 [SystemClock.elapsedRealtime]）。
     *
     * 用途：用户显式手动连接后的一段时间内，禁止自动路由策略把会话拉入仅监听模式，
     * 避免“刚连上就被同网段误判打断”的体验问题（对齐原型行为）。
     * 仅拦截“进入仅监听”，不影响“恢复转发”。
     */
    private val manualConnectProtectionDeadlineMs: AtomicLong = AtomicLong(0L)

    fun bindRuntimeDelegate(delegate: RoutePolicyRuntimeDelegate?) {
        runtimeDelegateRef.set(delegate)
        logChain("策略委托绑定 attached=${delegate != null}")
    }

    /**
     * 按身份解绑策略委托。
     *
     * 与 [bindRuntimeDelegate] 配对：Service 销毁/重建竞态下，旧实例的 onDestroy 可能
     * 晚于新实例 onCreate 执行；无条件置空会踩掉新实例刚绑定的委托，使内网/恢复决策被
     * 静默丢弃。因此仅当当前委托确实是调用方自己时才清空。
     *
     * @param delegate 申请解绑的委托实例。
     */
    fun unbindRuntimeDelegate(delegate: RoutePolicyRuntimeDelegate) {
        if (runtimeDelegateRef.compareAndSet(delegate, null)) {
            logChain("策略委托解绑 detached=true")
        } else {
            logChain("策略委托解绑跳过：已被新实例接管")
        }
    }

    /**
     * 设置/清除手动连接保护窗口。
     *
     * @param hasExplicitNetworkId 是否为用户显式发起的连接；仅显式连接才开启保护窗口。
     * @param protectionMs 保护时长（毫秒）。
     */
    fun updateManualProtectionDeadline(hasExplicitNetworkId: Boolean, protectionMs: Long) {
        if (!hasExplicitNetworkId) {
            manualConnectProtectionDeadlineMs.set(0L)
            return
        }
        val deadline = SystemClock.elapsedRealtime() + protectionMs
        manualConnectProtectionDeadlineMs.set(deadline)
        logChain("手动连接保护窗口设置 截止=$deadline 时长=${protectionMs}ms")
    }

    /**
     * 清除手动连接保护窗口（离网/停止时调用，避免窗口跨会话残留）。
     */
    fun clearManualProtectionDeadline() {
        manualConnectProtectionDeadlineMs.set(0L)
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
        logChain("启动策略实时链路 原因=$reason 传输=$transport WiFiIP=${wifiInfo?.address ?: "none"} 前缀=${wifiInfo?.prefixLength ?: -1}")
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
            // 内网判定一律以**执行时刻的实时链路**为准（执行可能被 mutex 延迟，
            // 执行时的真实网络比入队时的事件快照更可信）。事件值仅留作日志对照。
            val realtimeTransport = networkChangeObserver.currentTransport()
            val realtimeWifiInfo = networkChangeObserver.currentWifiIpv4Info()
            val transport = realtimeTransport
            val currentWifiIpv4 = if (transport == NetworkTransport.WIFI) realtimeWifiInfo?.address else null
            val currentWifiPrefixLength = if (transport == NetworkTransport.WIFI) realtimeWifiInfo?.prefixLength else null
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
                    val now = SystemClock.elapsedRealtime()
                    val deadline = manualConnectProtectionDeadlineMs.get()
                    if (now < deadline) {
                        logChain(
                            "自动策略跳过进入仅监听 原因=$reason 详情=manual_connect_protection now=$now 截止=$deadline",
                        )
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
    // 边界守卫（对齐原型 AutoConnectPolicy）：
    // prefixLength<=0 或 >32 均视为无效前缀，直接判否，避免：
    // 1. prefix=0 时 `-1 shl 32` 因 JVM 移位 mod 32 退化为 `-1`（全 1 掩码），
    //    使比较错误地退化为“完全相等才匹配”，语义与 /0 含义相反；
    // 2. 把无效前缀的物理网卡误判为同网段内网。
    if (prefixLength <= 0 || prefixLength > IPV4_MAX_PREFIX) {
        return false
    }
    val leftInt = bytesToInt(leftBytes)
    val rightInt = bytesToInt(rightBytes)
    val mask = if (prefixLength == IPV4_MAX_PREFIX) {
        -1
    } else {
        (-1 shl (IPV4_MAX_PREFIX - prefixLength))
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
