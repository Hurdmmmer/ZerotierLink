package io.github.jimmy.ztlink.service.policy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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
 * 2. 用户配置了“内网探测 IP”，且**某个真实物理网卡**与其同网段时，进入监控模式；
 * 3. 不在任何物理网卡同网段（含蜂窝、无网络）时恢复中转。
 *
 * 实现要点（对齐原型，避免“切到蜂窝仍误判内网”）：
 * - 内网判定**直接遍历系统所有物理网卡**（跳过 VPN/蜂窝接口），用网卡真实 IP 比对同网段；
 * - **不依赖**切网事件携带的 transport、不依赖 `currentTransport()`/`activeNetworkInfo`、
 *   不依赖缓存的 lastTransport——这些单一来源在 VPN 隧道开关、切网过渡瞬间易失真，
 *   会导致蜂窝下被误判为内网而不恢复转发；
 * - 判定结果只有 `true`（命中物理内网）或 `false`（未命中/无物理网卡），**绝不返回 null**，
 *   保证蜂窝场景一定走 RESUME_RELAY 恢复转发。
 */
@Singleton
class RoutePolicyEvaluator @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsStateHolder: SettingsStateHolder,
) {
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

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
     * 注意：参数 [observedTransport]/[observedWifiIpv4]/[observedWifiPrefixLength] 仅作日志参考，
     * **不再参与内网判定**。内网判定一律以系统真实物理网卡为准（见 [detectIntranetByPhysicalInterfaces]），
     * 以彻底避免“切到蜂窝后仍被误判为内网、不恢复转发”的问题。
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

        // 直接遍历真实物理网卡判定，不依赖事件/实时 transport 单一来源。
        val detection = detectIntranetByPhysicalInterfaces(configuredProbeIp)
        return RoutePolicyDecision(
            action = if (detection.inIntranet) {
                RoutePolicyAction.ENTER_MONITOR_ONLY
            } else {
                RoutePolicyAction.RESUME_RELAY
            },
            inIntranet = detection.inIntranet,
            detail = "${detection.detail},configured:$configuredProbeIp,reason:$reason" +
                ",event_transport:${observedTransport ?: "none"}",
            enabled = true,
        )
    }

    /**
     * 物理网卡内网探测结果。
     */
    private data class PhysicalIntranetDetection(
        val inIntranet: Boolean,
        val detail: String,
    )

    /**
     * 遍历系统全部网络，仅在**真实物理网卡**（跳过 VPN/蜂窝）上比对同网段。
     *
     * 规则（对齐原型 `AutoConnectPolicy.detectIntranetState`）：
     * 1. 跳过 VPN 接口（避免把 ZeroTier/TUN 的地址误判为物理网卡）；
     * 2. 跳过蜂窝接口（运营商私网段易引入误判，且蜂窝下本就应恢复转发）；
     * 3. 任一物理网卡 IP 与探测 IP 同网段 → 命中内网（true）；
     * 4. 遍历完未命中、无网络、或异常 → 一律返回 false（恢复转发），**绝不返回 null**。
     */
    @Suppress("DEPRECATION")
    private fun detectIntranetByPhysicalInterfaces(configuredProbeIp: String): PhysicalIntranetDetection {
        val targetAddress = runCatching { InetAddress.getByName(configuredProbeIp) }.getOrNull()
            ?: return PhysicalIntranetDetection(false, "probe_ip_invalid")
        val manager = connectivityManager
            ?: return PhysicalIntranetDetection(false, "connectivity_manager_null")

        val allNetworks = manager.allNetworks
        if (allNetworks.isEmpty()) {
            return PhysicalIntranetDetection(false, "no_active_network")
        }

        for (network in allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                continue
            }
            val linkProperties = manager.getLinkProperties(network) ?: continue
            val interfaceName = linkProperties.interfaceName?.takeIf { it.isNotEmpty() } ?: continue
            // 校验该接口确实存在（与原型一致，进一步排除已失效的网络快照）。
            NetworkInterface.getByName(interfaceName) ?: continue

            for (linkAddress in linkProperties.linkAddresses) {
                val localAddress = linkAddress.address ?: continue
                if (localAddress.isLoopbackAddress) {
                    continue
                }
                if (localAddress !is Inet4Address) {
                    continue
                }
                val localIpv4 = localAddress.hostAddress ?: continue
                if (isInSameSubnet(
                        leftIpv4 = configuredProbeIp,
                        rightIpv4 = localIpv4,
                        prefixLength = linkAddress.prefixLength,
                    )
                ) {
                    return PhysicalIntranetDetection(
                        inIntranet = true,
                        detail = "same_subnet_via_$interfaceName,local:$localIpv4/${linkAddress.prefixLength}",
                    )
                }
            }
        }
        return PhysicalIntranetDetection(false, "different_subnet_physical_networks")
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
