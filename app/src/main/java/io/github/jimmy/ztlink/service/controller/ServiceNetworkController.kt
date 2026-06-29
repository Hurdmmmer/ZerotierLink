package io.github.jimmy.ztlink.service.controller

import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkStatus
import io.github.jimmy.ztlink.data.network.NetworkRepository
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.policy.RoutePolicyService
import io.github.jimmy.ztlink.service.runtime.RuntimeContext
import io.github.jimmy.ztlink.service.ServiceAction
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 服务网络观察控制器。
 *
 * 职责：
 * 1. 监听系统网络变化，触发自动路由策略复检；
 * 2. 监听内核网络配置回调，触发配置同步与隧道重配置；
 * 3. 仅做“事件到动作”的转换，不承载 runtime 启停编排。
 */
class ServiceNetworkController(
    private val serviceScope: CoroutineScope,
    private val networkChangeObserver: NetworkChangeObserver,
    private val routePolicyService: RoutePolicyService,
    private val runtimeContext: RuntimeContext,
    private val dispatchAction: suspend (ServiceAction) -> Unit,
    private val networkRepository: NetworkRepository,
) {

    /** 网络切换监听是否已启动。 */
    private var networkObserverStarted: Boolean = false

    /** 内核网络配置回调是否已注册。 */
    private var runtimeConfigCallbackStarted: Boolean = false

    /**
     * 本控制器实例注册到 [RuntimeContext] 的内核配置回调引用。
     *
     * 持有它是为了「按身份解绑」：停止时只清除自己注册的那个回调，避免 Service
     * 销毁/重建竞态下踩掉新实例刚注册的回调（详见 [RuntimeContext.clearNetworkConfigCallback]）。
     */
    private var registeredConfigCallback:
        ((Long, VirtualNetworkConfigOperation, VirtualNetworkConfig?) -> Unit)? = null

    /**
     * 最近一次已处理的网络配置摘要（按 networkId 维度缓存）。
     *
     * 目的：
     * 1. 对齐老项目 `isChanged` 语义；
     * 2. 仅在 CONFIG_UPDATE 且配置确实变化时才触发重建隧道；
     * 3. 避免每次心跳 CONFIG_UPDATE 都重复重建隧道。
     */
    private val lastConfigFingerprintByNetworkId: MutableMap<NetworkId, String> = mutableMapOf()

    /** 配置摘要缓存锁，保证并发回调下读写一致。 */
    private val configFingerprintLock: Any = Any()

    /**
     * 启动网络切换监听。
     *
     * 说明：
     * 1. 该监听只服务自动路由策略复检；
     * 2. 与内核配置回调解耦，避免关闭自动路由时误关核心配置回调。
     */
    fun startNetworkObserver() {
        if (networkObserverStarted) {
            return
        }
        networkObserverStarted = true
        logChain("网络切换监听已启动")
        startObserveNetworkChanges()
    }

    /**
     * 停止网络切换监听。
     */
    fun stopNetworkObserver() {
        if (!networkObserverStarted) {
            return
        }
        networkObserverStarted = false
        logChain("网络切换监听已停止")
        networkChangeObserver.stop()
    }

    /**
     * 启动内核网络配置回调监听。
     *
     * 说明：
     * 1. 该回调负责把内核配置变更同步到服务链路；
     * 2. 必须在 Join/Leave 全流程中始终可用，不能受自动路由开关影响。
     */
    fun startRuntimeConfigCallback() {
        if (runtimeConfigCallbackStarted) {
            return
        }
        runtimeConfigCallbackStarted = true
        logChain("内核配置回调已启动")
        startObserveRuntimeNetworkConfigUpdates()
    }

    /**
     * 停止内核网络配置回调监听。
     */
    fun stopRuntimeConfigCallback() {
        if (!runtimeConfigCallbackStarted) {
            return
        }
        runtimeConfigCallbackStarted = false
        logChain("内核配置回调已停止")
        // 仅清除本实例注册的回调，避免踩掉新实例刚注册的回调。
        registeredConfigCallback?.let { runtimeContext.clearNetworkConfigCallback(it) }
        registeredConfigCallback = null
        clearAllConfigFingerprints()
    }

    /**
     * 停止全部网络相关监听。
     */
    fun stopAll() {
        stopNetworkObserver()
        stopRuntimeConfigCallback()
    }

    /**
     * 监听系统网络变化，触发 UDP Socket 重建与路由策略复检。
     *
     * 事件分类：
     * 1. 接口切换事件（from≠to）：重建 UDP Socket + 策略复检；
     * 2. WiFi IP 分配补偿事件（from==to==WIFI, reason=wifi_ip_assigned）：
     *    仅策略复检，不重建 Socket（接口未变，只是 DHCP 刚完成）。
     */
    private fun startObserveNetworkChanges() {
        networkChangeObserver.start { event ->
            logChain("检测到网络变化 原因=${event.reason} from=${event.from} to=${event.to} ip=${event.wifiIpv4 ?: "none"}")

            val isWifiIpCompensation = event.reason == "wifi_ip_assigned"

            if (!isWifiIpCompensation) {
                // 接口切换：仅当物理网络切换到 WiFi 或蜂窝时处理。
                val hasNewNetwork = event.to == NetworkTransport.WIFI || event.to == NetworkTransport.CELLULAR
                if (!hasNewNetwork) {
                    return@start
                }
            }

            // 收敛切网链路：单协程内“先重建 UDP Socket（A），再策略复检（B）”。
            // 二者都是 suspend，顺序 await 即严格定序，消除原先两个独立 launch 并发抢占
            // actionMutex 导致的 runtime/状态错配与 ERROR 死结；B 此时观察到的运行/监听状态
            // 已是 A 提交后的真实结果。wifi_ip_assigned 补偿事件不重建 Socket，仅走 B。
            serviceScope.launch {
                if (!isWifiIpCompensation) {
                    dispatchAction(ServiceAction.PhysicalNetworkChanged(reason = event.reason))
                }
                routePolicyService.triggerAutoRoutePolicyCheck(
                    reason = event.reason,
                    observedTransport = event.to,
                    observedWifiIpv4 = event.wifiIpv4,
                    observedWifiPrefixLength = event.wifiPrefixLength,
                )
            }
        }
    }

    /**
     * 监听 ZeroTier 内核网络配置回调并分发业务动作。
     */
    private fun startObserveRuntimeNetworkConfigUpdates() {
        // 用具名匿名函数而非内联 lambda，便于在停止时按身份精确解绑（见 stopRuntimeConfigCallback）。
        val callback = fun(nwid: Long, op: VirtualNetworkConfigOperation, config: VirtualNetworkConfig?) {
            val networkId = nwid.toNetworkIdOrNull() ?: return
            val status = config?.status
            val addressCount = config?.assignedAddresses?.size ?: 0
            val routeCount = config?.routes?.size ?: 0
            logChain("收到内核网络回调 networkId=${networkId.value} 操作=$op 状态=${status ?: "null"} 地址数=$addressCount 路由数=$routeCount")
            serviceScope.launch {
                // 与老项目对齐：
                // 1. OP_UP 只做同步，不触发重建；
                // 2. 仅当 OP_CONFIG_UPDATE 且配置发生变化时才允许重建；
                // 3. 状态不是 NETWORK_STATUS_OK 时，即使配置变化也只同步不重建。
                val configChanged = when (op) {
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_UP -> {
                        // 关键修复：
                        // 某些场景下 OP_UP 已经携带完整 OK 配置（地址/路由均已就绪），
                        // 若不在这里预热指纹缓存，下一条 CONFIG_UPDATE 会因 oldFingerprint=null
                        // 被误判为“变化”，从而触发一次不必要的隧道重建。
                        cacheConfigFingerprint(networkId, config)
                        false
                    }

                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE ->
                        markAndCheckConfigChanged(networkId, config)

                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN,
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY,
                    -> {
                        clearConfigFingerprint(networkId)
                        false
                    }
                }
                // 同步动作派发收敛：
                // 1) OP_UP 需要同步一次，确保首轮配置可回写；
                // 2) OP_CONFIG_UPDATE 仅在“配置确实变化”时同步；
                // 3) DOWN/DESTROY 不再派发，避免离网/抖动阶段产生无意义动作噪声。
                val shouldDispatchSync = when (op) {
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_UP -> true
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE -> configChanged
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN,
                    VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY,
                    -> false
                }
                if (shouldDispatchSync) {
                    dispatchAction(
                        ServiceAction.SyncNetworkConfig(
                            networkId = networkId,
                            configChanged = configChanged,
                            reason = "network_config_callback",
                        ),
                    )
                } else {
                    logChain(
                        "跳过配置同步 networkId=${networkId.value} 操作=$op 原因=无需同步或配置未变化",
                    )
                }
                val shouldReconfigure =
                    op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE &&
                        configChanged &&
                        status == VirtualNetworkStatus.NETWORK_STATUS_OK
                if (shouldReconfigure) {
                    val entity = networkRepository.findById(networkId) ?: return@launch
                    logChain("触发隧道重配置 networkId=${networkId.value} 操作=$op 配置变化=$configChanged")
                    dispatchAction(
                        ServiceAction.ReconfigureTunnel(
                            networkId = networkId,
                            routeViaZeroTier = entity.config.routeViaZeroTier,
                            dnsMode = entity.config.dnsMode,
                            customDnsServers = entity.dnsServers.ifEmpty {
                                entity.config.customDns
                                    .split('\n', ',', ';')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                            },
                            reason = "network_config_callback",
                        ),
                    )
                } else {
                    val skipReason = when {
                        op != VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE -> "非配置更新操作"
                        !configChanged -> "配置未变化"
                        status != VirtualNetworkStatus.NETWORK_STATUS_OK -> "状态未就绪:${status ?: "null"}"
                        else -> "未命中重配置条件"
                    }
                    logChain(
                        "跳过隧道重配置 networkId=${networkId.value} 操作=$op 原因=$skipReason",
                    )
                }
            }
        }
        registeredConfigCallback = callback
        runtimeContext.setNetworkConfigCallback(callback)
    }

    /**
     * 更新并判断网络配置是否变化。
     *
     * 说明：
     * 1. 首次收到 CONFIG_UPDATE 视为“变化”，确保能完成第一次建隧道；
     * 2. 后续摘要一致则视为“未变化”，直接跳过重建；
     * 3. 摘要为空（例如 config 为 null）时判定未变化，避免误触发。
     */
    private fun markAndCheckConfigChanged(networkId: NetworkId, config: VirtualNetworkConfig?): Boolean {
        val newFingerprint = config?.buildConfigFingerprint() ?: return false
        return synchronized(configFingerprintLock) {
            val oldFingerprint = lastConfigFingerprintByNetworkId[networkId]
            lastConfigFingerprintByNetworkId[networkId] = newFingerprint
            oldFingerprint != newFingerprint
        }
    }

    /**
     * 仅写入当前配置摘要，不判定变化。
     *
     * 使用场景：
     * - OP_UP 阶段先预热缓存，避免后续第一条 CONFIG_UPDATE 因“旧值为空”误判为变化。
     */
    private fun cacheConfigFingerprint(networkId: NetworkId, config: VirtualNetworkConfig?) {
        val newFingerprint = config?.buildConfigFingerprint() ?: return
        synchronized(configFingerprintLock) {
            lastConfigFingerprintByNetworkId[networkId] = newFingerprint
        }
    }

    /** 清理单个网络的配置摘要缓存。 */
    private fun clearConfigFingerprint(networkId: NetworkId) {
        synchronized(configFingerprintLock) {
            lastConfigFingerprintByNetworkId.remove(networkId)
        }
    }

    /** 清理全部配置摘要缓存。 */
    private fun clearAllConfigFingerprints() {
        synchronized(configFingerprintLock) {
            lastConfigFingerprintByNetworkId.clear()
        }
    }

    private fun logChain(message: String) {
        ChainLog.i(TAG, message)
    }

    private companion object {
        private const val TAG = "ServiceNetworkController"
    }
}

/**
 * 构建用于“配置变化判定”的稳定摘要字符串。
 *
 * 说明：
 * 1. 只提取影响隧道行为的关键字段（状态、地址、路由、DNS、MTU 等）；
 * 2. 对集合字段排序后再拼接，避免仅因顺序变化导致误判；
 * 3. 摘要仅用于本地变化检测，不参与持久化与对外协议。
 */
private fun VirtualNetworkConfig.buildConfigFingerprint(): String {
    val addressFingerprint = assignedAddresses.orEmpty()
        .mapNotNull { assigned ->
            val host = assigned.address?.hostAddress ?: return@mapNotNull null
            "$host/${assigned.port}"
        }
        .sorted()
        .joinToString(separator = "|")

    val routeFingerprint = routes.orEmpty()
        .map { route ->
            val targetHost = route.target?.address?.hostAddress ?: "null"
            val targetPrefix = route.target?.port ?: -1
            val viaHost = route.via?.address?.hostAddress ?: "null"
            "$targetHost/$targetPrefix->$viaHost"
        }
        .sorted()
        .joinToString(separator = "|")

    val dnsFingerprint = dns?.servers.orEmpty()
        .mapNotNull { server -> server.address?.hostAddress }
        .sorted()
        .joinToString(separator = "|")

    val domain = dns?.domain?.trim().orEmpty()

    return buildString {
        append("status=").append(status).append(';')
        append("type=").append(type).append(';')
        append("name=").append(name ?: "").append(';')
        append("mtu=").append(mtu).append(';')
        append("mac=").append(mac).append(';')
        append("broadcast=").append(isBroadcastEnabled).append(';')
        append("bridge=").append(isBridge).append(';')
        append("addresses=").append(addressFingerprint).append(';')
        append("routes=").append(routeFingerprint).append(';')
        append("dnsDomain=").append(domain).append(';')
        append("dnsServers=").append(dnsFingerprint)
    }
}

/**
 * 将 ZeroTier 无符号 Long 网络 ID 转换为业务 NetworkId。
 */
private fun Long.toNetworkIdOrNull(): NetworkId? {
    val hex = java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')
    return NetworkId.parse(hex)
}
