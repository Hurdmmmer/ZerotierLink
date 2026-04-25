package io.github.jimmy.ztlink.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.data.network.NetworkRepository
import io.github.jimmy.ztlink.data.network.local.MoonOrbitDao
import io.github.jimmy.ztlink.data.settings.WhitelistPackagesProvider
import io.github.jimmy.ztlink.model.network.JoinNetwork
import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.model.network.NetworkEntity
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.runtime.RuntimeMoonOrbit
import io.github.jimmy.ztlink.model.runtime.RuntimeNetworkInfo
import io.github.jimmy.ztlink.model.runtime.RuntimeResult
import io.github.jimmy.ztlink.model.runtime.RuntimeResultCode
import io.github.jimmy.ztlink.model.runtime.RuntimeResultType
import io.github.jimmy.ztlink.model.runtime.VpnTunnelConfig
import io.github.jimmy.ztlink.model.service.ServiceEffect
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceErrorCode
import io.github.jimmy.ztlink.model.service.ServiceState
import io.github.jimmy.ztlink.model.service.ServiceStateType
import io.github.jimmy.ztlink.service.controller.ServiceNetworkController
import io.github.jimmy.ztlink.service.ServiceRuntimeObserverPipeline
import io.github.jimmy.ztlink.service.controller.ServiceStateController
import io.github.jimmy.ztlink.service.controller.ServiceTrafficController
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.notification.ServiceNotificationController
import io.github.jimmy.ztlink.service.policy.IntranetCheckState
import io.github.jimmy.ztlink.service.policy.RoutePolicyCoordinator
import io.github.jimmy.ztlink.service.policy.RoutePolicyRuntimeDelegate
import io.github.jimmy.ztlink.service.policy.ServiceStartNetworkGuard
import io.github.jimmy.ztlink.service.runtime.RuntimeContext
import io.github.jimmy.ztlink.service.runtime.RuntimeService
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ZeroTier VPN 服务（VpnService 核心实现）。
 *
 * 主链路：
 * UI -> ViewModel(ServiceAction) -> Dispatcher -> ZeroTierVpnService -> RuntimeService。
 */
@SuppressLint("VpnServicePolicy")
@AndroidEntryPoint
class ZeroTierVpnService : VpnService(), RoutePolicyRuntimeDelegate {

    /** 状态仓库：管理并分发服务全局状态。 */
    @Inject
    lateinit var stateStore: ServiceStateStore

    /** Runtime 服务：执行 ZeroTier 节点能力。 */
    @Inject
    lateinit var runtimeService: RuntimeService

    /** 网络仓库：持久化网络配置及状态。 */
    @Inject
    lateinit var networkRepository: NetworkRepository

    /** 应用白名单包名提供器。 */
    @Inject
    lateinit var whitelistPackagesProvider: WhitelistPackagesProvider

    /** Persisted moon orbit records. */
    @Inject
    lateinit var moonOrbitDao: MoonOrbitDao

    /** 启动网络环境门禁。 */
    @Inject
    lateinit var startNetworkGuard: ServiceStartNetworkGuard

    /** 通知控制器：管理前台服务通知及流量刷新逻辑。 */
    @Inject
    lateinit var notificationController: ServiceNotificationController

    /** 网络变化观察器：监听系统网络切换。 */
    @Inject
    lateinit var networkChangeObserver: NetworkChangeObserver

    /** 路由策略协调器：根据网络环境决定中转/监控模式。 */
    @Inject
    lateinit var routePolicyCoordinator: RoutePolicyCoordinator

    /** Runtime 上下文：管理底层统计、IO 及配置回调。 */
    @Inject
    lateinit var runtimeContext: RuntimeContext

    /** 服务内部协程作用域：生命周期随 Service 销毁而取消。 */
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 动作执行互斥锁，保证所有动作串行执行。 */
    private val actionMutex: Mutex = Mutex()

    /** 观察管线：统一编排状态/流量/网络观察控制器。 */
    private lateinit var observerPipeline: ServiceRuntimeObserverPipeline

    /** 系统电源管理器，用于判断屏幕交互状态。 */
    private val powerManager: PowerManager by lazy {
        getSystemService(PowerManager::class.java)
    }

    /** 标记前台通知是否已启动。 */
    @Volatile
    private var foregroundStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        logChain("Zertier VPN 服务已创建")
        runtimeService.bindVpnService(this)
        routePolicyCoordinator.bindRuntimeDelegate(this)
        notificationController.initNotification()

        observerPipeline = createObserverPipeline()
        observerPipeline.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.toServiceAction()
        if (action == null) {
            logChain("忽略服务命令 原因=intent_action_unresolved startId=$startId")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        logChain("收到服务命令 startId=$startId 动作=${actionSummary(action)}")
        startForegroundForAcceptedCommand(action)
        serviceScope.launch {
            executeAction(action)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    /**
     * 当 VPN 权限被吊销或系统 VPN 断开时触发。
     */
    override fun onRevoke() {
        serviceScope.launch {
            executeAction(
                ServiceAction.Stop(
                    keepServiceAlive = false,
                    reason = "vpn_revoked",
                ),
            )
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        logChain("服务已销毁")
        if (::observerPipeline.isInitialized) {
            observerPipeline.stop()
        }
        routePolicyCoordinator.bindRuntimeDelegate(null)
        runtimeContext.setNetworkConfigCallback(null)
        runtimeService.unbindVpnService()
        serviceScope.cancel()
        notificationController.cancel(ServiceNotificationController.NOTIFICATION_ID)
        stopForegroundCompat(remove = true)
        super.onDestroy()
    }

    /**
     * 进入监控模式（暂停隧道）。
     */
    override suspend fun enterMonitorOnly(
        reason: String,
        detail: String,
    ) {
        executeAction(
            ServiceAction.EnterMonitorOnly(
                detail = detail,
                reason = reason,
            ),
        )
    }

    /**
     * 恢复转发模式（重新建立隧道）。
     */
    override suspend fun resumeRelay(reason: String) {
        executeAction(ServiceAction.ResumeRelay(reason = reason))
    }

    override fun isMonitorOnlyMode(): Boolean = runtimeService.readRuntimeState().monitorOnlyMode

    override fun isServiceRunning(): Boolean {
        return stateStore.currentState().type != ServiceStateType.STOPPED
    }

    /**
     * 分发内网检查结果，通知 UI 展示或记录日志。
     */
    override suspend fun dispatchIntranetState(state: IntranetCheckState) {
        stateStore.emitEffect(
            ServiceEffect.intranetCheckStateUpdated(
                enabled = state.enabled,
                inIntranet = state.inIntranet,
                reason = state.reason,
                detail = state.detail,
            ),
        )
    }

    /**
     * 串行执行服务动作。
     */
    private suspend fun executeAction(action: ServiceAction): ServiceActionResult {
        return actionMutex.withLock {
            logChain("动作开始 ${actionSummary(action)}")
            val result = when (action) {
                is ServiceAction.StartOrResume -> handleStartOrResume(action)
                is ServiceAction.Join -> handleJoin(action)
                is ServiceAction.Leave -> handleLeave(action)
                is ServiceAction.Stop -> handleStop(action)
                is ServiceAction.EnterMonitorOnly -> handleEnterMonitorOnly(action)
                is ServiceAction.ResumeRelay -> handleResumeRelay(action)
                is ServiceAction.SyncNetworkConfig -> handleSyncNetworkConfig(
                    networkId = action.networkId,
                    configChanged = action.configChanged,
                )
                is ServiceAction.ReconfigureTunnel -> handleReconfigureTunnel(action)
                is ServiceAction.OrbitMoons -> handleOrbitMoons(action)
                is ServiceAction.DeorbitMoons -> handleDeorbitMoons(action)
                is ServiceAction.QueryPeers -> handleQueryPeers()
                is ServiceAction.QueryNode -> handleQueryNode()
                is ServiceAction.QueryNetworkConfig -> handleQueryNetworkConfig(action.networkId)
            }
            logChain(
                "动作结束 类型=${action.javaClass.simpleName} 已受理=${result.accepted} 状态=${result.terminalState.type} 错误=${result.error?.code ?: "none"}",
            )
            result
        }
    }

    /**
     * 处理启动命令。
     */
    private suspend fun handleStartOrResume(action: ServiceAction.StartOrResume): ServiceActionResult {
        if (action.hasExplicitNetworkId && action.targetNetworkId == null) {
            return terminalFailure(
                ServiceError(
                    code = ServiceErrorCode.VALIDATION_FAILED,
                    message = getString(R.string.error_invalid_target_network_id),
                    recoverable = true,
                ),
            )
        }

        val targetNetworkId = resolveStartOrResumeTargetNetworkId(action)
        if (targetNetworkId == null) {
            val stoppedState = ServiceState.stopped()
            stateStore.setState(stoppedState)
            logChain("启动或恢复忽略 原因=no_target_network 触发=${action.reason}")
            stopForegroundAndReset()
            stopSelf()
            return ServiceActionResult(
                accepted = true,
                terminalState = stoppedState,
            )
        }

        stateStore.setState(
            ServiceState.starting(
                reason = action.reason,
                targetNetworkId = targetNetworkId,
            ),
        )

        val targetEntity = networkRepository.findById(targetNetworkId)
            ?: return terminalFailure(
                ServiceError(
                    code = ServiceErrorCode.VALIDATION_FAILED,
                    message = getString(R.string.error_target_network_config_not_found),
                    recoverable = true,
                ),
            )

        return handleJoin(
            ServiceAction.Join(
                params = targetEntity.toJoinParams(),
                hasExplicitNetworkId = action.hasExplicitNetworkId,
                reason = action.reason,
            ),
        )
    }

    /**
     * 处理加入网络动作。
     *
     * 执行完整的网络加入流程：环境验证 -> 运行时启动 -> 网络加入 -> VPN隧道建立 -> 配置同步。
     * 如果任何步骤失败，会发射相应的失败效果并返回终端失败状态。
     * 成功后会更新网络状态为已连接，并发射成功效果。
     */
    private suspend fun handleJoin(action: ServiceAction.Join): ServiceActionResult {
        val networkId = action.params.networkId

        // 验证启动/加入环境条件（如网络权限、VPN准备状态等）
        val environmentError = startNetworkGuard.validateStartOrJoin()
        if (environmentError != null) {
            logChain(
                "加入网络门禁拦截 networkId=${networkId.value} 原因=${environmentError.message} code=${environmentError.code}",
            )
            val failedEffect = ServiceEffect.joinFailed(networkId, action.reason, environmentError)
            stateStore.emitEffect(failedEffect)
            return terminalFailure(environmentError, failedEffect).also {
                updateNetworkStatus(networkId, NetworkStatusEnum.DISCONNECTED)
            }
        }

        // 启动前策略检查：
        // 若当前命中“内网 SSID 自动暂停”条件，则直接进入仅监控模式，不启动内核。
        val startPolicy = routePolicyCoordinator.checkStartPolicy(
            reason = action.reason,
        )
        if (startPolicy.shouldEnterMonitorOnly) {
            logChain(
                "启动策略命中内网，加入链路直接进入仅监听 networkId=${networkId.value} 详情=${startPolicy.detail}",
            )
            return handleEnterMonitorOnly(
                ServiceAction.EnterMonitorOnly(
                    networkId = networkId,
                    detail = startPolicy.detail,
                    reason = action.reason,
                ),
            )
        }

        // 进入正常转发链路前，显式清理仅监听标记，避免旧状态残留。
        runtimeService.updateMonitorOnlyMode(false)

        // 更新服务状态为连接中，并标记网络状态为请求配置
        stateStore.setState(ServiceState.connecting(networkId, action.reason))
        updateNetworkStatus(networkId, NetworkStatusEnum.REQUESTING_CONFIGURATION)

        // 确保 ZeroTier 运行时已启动
        val startResult = runtimeService.startRuntime(networkId)
        if (!startResult.isRuntimeSuccess()) {
            val error = startResult.toServiceError(ServiceErrorCode.RUNTIME_START_FAILED)
            val effect = ServiceEffect.joinFailed(networkId, action.reason, error)
            stateStore.emitEffect(effect)
            return terminalFailure(error, effect).also {
                updateNetworkStatus(networkId, NetworkStatusEnum.DISCONNECTED)
            }
        }

        // 执行网络加入操作
        val joinResult = runtimeService.joinNetwork(networkId)
        if (!joinResult.isRuntimeSuccess()) {
            val error = joinResult.toServiceError(ServiceErrorCode.JOIN_FAILED)
            val effect = ServiceEffect.joinFailed(networkId, action.reason, error)
            stateStore.emitEffect(effect)
            return terminalFailure(error, effect).also {
                updateNetworkStatus(networkId, NetworkStatusEnum.DISCONNECTED)
            }
        }
        // join 成功后立即记录最近激活网络：
        // 即使当前仍在等待控制器授权（还不能建隧道），后续 StartOrResume 也能恢复到该目标网络。
        networkRepository.setLastActivated(networkId)
        applyPersistedMoonOrbits(networkId)

        // 建立 VPN 隧道，使用用户配置的路由、DNS 和白名单参数
        val tunnelResult = runtimeService.establishVpnTunnel(
            VpnTunnelConfig(
                networkId = networkId,
                routeViaZeroTier = action.params.routeViaZeroTier,
                dnsMode = action.params.dnsMode,
                customDnsServers = normalizeDnsServers(action.params.customDns.parseDnsServers()),
                whitelistPackages = normalizePackages(whitelistPackagesProvider.listWhitelistPackages()),
                includeBuiltInWhitelistPackages = true,
                reason = action.reason,
            ),
        )
        if (!tunnelResult.isRuntimeSuccess()) {
            if (tunnelResult.requiresConnectingStateOnTunnelFailure()) {
                return holdConnectingState(
                    networkId = networkId,
                    reason = action.reason,
                    runtimeNetwork = runtimeService.getNetworkConfig(networkId),
                )
            }
            val error = tunnelResult.toServiceError(ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED)
            val effect = ServiceEffect.joinFailed(networkId, action.reason, error)
            stateStore.emitEffect(effect)
            return terminalFailure(error, effect).also {
                updateNetworkStatus(networkId, NetworkStatusEnum.DISCONNECTED)
            }
        }

        // 同步运行时网络配置到本地仓库
        val runtimeNetwork = runtimeService.getNetworkConfig(networkId)
        if (runtimeNetwork != null) {
            applyRuntimeNetworkToRepository(runtimeNetwork)
        } else {
            updateNetworkStatus(networkId, NetworkStatusEnum.OK)
        }

        // 加入成功：更新状态为已连接并发射成功效果
        val connectedState = ServiceState.connected(networkId, System.currentTimeMillis())
        val successEffect = ServiceEffect.joinSuccess(networkId, action.reason)
        stateStore.setState(connectedState)
        stateStore.emitEffect(successEffect)

        return ServiceActionResult(
            accepted = true,
            terminalState = connectedState,
            effect = successEffect,
        )
    }

    /**
     * Apply persisted moon orbits after a successful join.
     *
     * This keeps behavior aligned with the legacy app:
     * all configured moons are re-applied whenever a network join succeeds.
     */
    private suspend fun applyPersistedMoonOrbits(networkId: NetworkId) {
        val moonConfigs = moonOrbitDao.listAll()
        if (moonConfigs.isEmpty()) {
            return
        }
        val specs = moonConfigs.map { config ->
            RuntimeMoonOrbit(
                moonWorldId = config.moonWorldId,
                moonSeed = config.moonSeed,
            )
        }
        val result = runtimeService.orbitMoons(specs)
        if (result.isRuntimeSuccess()) {
            logChain(
                "加入网络后自动应用 Moon 入轨 networkId=${networkId.value} 数量=${specs.size}",
            )
        } else {
            logChain(
                "加入网络后自动应用 Moon 入轨失败 networkId=${networkId.value} 数量=${specs.size} 结果=${result.resultCode}",
            )
        }
    }

    /**
     * 处理离开网络动作。
     */
    private suspend fun handleLeave(action: ServiceAction.Leave): ServiceActionResult {
        // 主动离网时清理仅监听标记，避免后续状态恢复误判。
        runtimeService.updateMonitorOnlyMode(false)
        val leaveResult = runtimeService.leaveNetwork(action.networkId)
        val leaveNotRunning = leaveResult.resultCode == RuntimeResultCode.NOT_RUNNING
        // 关键逻辑：
        // 仅监听模式下 runtime 可能已停止，此时 leave 返回 NOT_RUNNING 属于预期路径，
        // 不应映射为错误终态。
        if (!leaveResult.isRuntimeSuccess() && !leaveNotRunning) {
            val error = leaveResult.toServiceError(ServiceErrorCode.LEAVE_FAILED)
            return terminalFailure(error)
        }
        if (leaveNotRunning) {
            logChain("离开网络命中仅监听态容错 networkId=${action.networkId.value} 结果=${leaveResult.resultCode}")
        }

        updateNetworkStatus(
            networkId = action.networkId,
            status = NetworkStatusEnum.DISCONNECTED,
            clearRuntimeInfo = true,
        )

        val noNetworksLeft = requireNotNull(leaveResult.noNetworksLeft) {
            "LEAVE result must carry noNetworksLeft."
        }

        // 对齐老项目行为：
        // 当最后一个网络离开后，必须立即停止 runtime（不保活），
        // 避免节点进程继续存活导致管理端长时间显示在线。
        if (noNetworksLeft) {
            networkRepository.clearLastActivated()
            logChain("离网后无剩余网络，已清理最近激活网络")
            logChain("离网后无剩余网络，执行 Runtime 停止")
            val stopResult = runtimeService.stopRuntime(keepServiceAlive = false)
            if (!stopResult.isRuntimeSuccess() && stopResult.resultCode != RuntimeResultCode.NOT_RUNNING) {
                return terminalFailure(stopResult.toServiceError(ServiceErrorCode.INTERNAL_ERROR))
            }
        }

        val effect = ServiceEffect.leaveDone(
            networkId = action.networkId,
            noNetworksLeft = noNetworksLeft,
            reason = action.reason,
        )
        stateStore.emitEffect(effect)

        val activeNetworkAfterLeave = runtimeService.readRuntimeState().activeNetworkId
        val nextState = if (noNetworksLeft || activeNetworkAfterLeave == null) {
            ServiceState.stopped()
        } else {
            ServiceState.connected(activeNetworkAfterLeave, System.currentTimeMillis())
        }
        stateStore.setState(nextState)
        if (nextState.type == ServiceStateType.STOPPED) {
            stopSelf()
        }

        return ServiceActionResult(
            accepted = true,
            terminalState = nextState,
            effect = effect,
        )
    }

    /**
     * 处理进入监控模式。
     */
    private suspend fun handleEnterMonitorOnly(action: ServiceAction.EnterMonitorOnly): ServiceActionResult {
        val currentState = stateStore.currentState()
        val runtimeState = runtimeService.readRuntimeState()
        val currentNetwork = action.networkId
            ?: currentState.networkId
            ?: runtimeState.activeNetworkId
        if (currentState.type == ServiceStateType.STOPPED && currentNetwork == null) {
            logChain("进入仅监听忽略 原因=service_stopped_no_active_network 触发原因=${action.reason}")
            return terminalSuccess(effect = null)
        }

        runtimeService.updateMonitorOnlyMode(true)
        runtimeService.stopRuntime(keepServiceAlive = true)

        val state = ServiceState.monitorOnly(
            networkId = currentNetwork,
            reason = action.reason,
            detail = action.detail,
            enteredAtMs = System.currentTimeMillis(),
        )
        val effect = ServiceEffect.monitorOnlyEntered(currentNetwork, action.reason, action.detail)
        stateStore.setState(state)
        stateStore.emitEffect(effect)

        return ServiceActionResult(
            accepted = true,
            terminalState = state,
            effect = effect,
        )
    }

    /**
     * 处理恢复中转动作。
     */
    private suspend fun handleResumeRelay(action: ServiceAction.ResumeRelay): ServiceActionResult {
        val activeNetwork = stateStore.currentState().networkId ?: runtimeService.readRuntimeState().activeNetworkId
            ?: return terminalFailure(
                ServiceError(
                    code = ServiceErrorCode.VALIDATION_FAILED,
                    message = "No active network to resume.",
                    recoverable = true,
                ),
            )

        // 恢复前执行与 Join 一致的门禁检查，避免在蜂窝禁用等场景被自动恢复绕过设置。
        val environmentError = startNetworkGuard.validateStartOrJoin()
        if (environmentError != null) {
            logChain(
                "恢复转发门禁拦截 networkId=${activeNetwork.value} 原因=${environmentError.message} code=${environmentError.code}",
            )
            runtimeService.updateMonitorOnlyMode(true)
            val currentState = stateStore.currentState()
            val monitorState = if (
                currentState.type == ServiceStateType.MONITOR_ONLY &&
                currentState.networkId == activeNetwork
            ) {
                currentState
            } else {
                ServiceState.monitorOnly(
                    networkId = activeNetwork,
                    reason = action.reason,
                    detail = "resume_blocked:${environmentError.code}:${environmentError.message}",
                    enteredAtMs = System.currentTimeMillis(),
                )
            }
            stateStore.setState(monitorState)
            return ServiceActionResult(
                accepted = true,
                terminalState = monitorState,
                error = environmentError,
            )
        }

        runtimeService.updateMonitorOnlyMode(false)

        val startResult = runtimeService.startRuntime(activeNetwork)
        if (!startResult.isRuntimeSuccess()) {
            return terminalFailure(startResult.toServiceError(ServiceErrorCode.RUNTIME_START_FAILED))
        }

        val joinResult = runtimeService.joinNetwork(activeNetwork)
        if (!joinResult.isRuntimeSuccess()) {
            return terminalFailure(joinResult.toServiceError(ServiceErrorCode.JOIN_FAILED))
        }

        val entity = networkRepository.findById(activeNetwork)
        if (entity != null) {
            val reconfigureResult = runtimeService.establishVpnTunnel(
                VpnTunnelConfig(
                    networkId = entity.networkId,
                    routeViaZeroTier = entity.config.routeViaZeroTier,
                    dnsMode = entity.config.dnsMode,
                    customDnsServers = normalizeDnsServers(entity.resolveCustomDnsServers()),
                    whitelistPackages = normalizePackages(whitelistPackagesProvider.listWhitelistPackages()),
                    includeBuiltInWhitelistPackages = true,
                    reason = action.reason,
                ),
            )
            if (!reconfigureResult.isRuntimeSuccess()) {
                if (reconfigureResult.requiresConnectingStateOnTunnelFailure()) {
                    return holdConnectingState(
                        networkId = entity.networkId,
                        reason = action.reason,
                        runtimeNetwork = runtimeService.getNetworkConfig(entity.networkId),
                    )
                }
                return terminalFailure(reconfigureResult.toServiceError(ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED))
            }
        }

        val effect = ServiceEffect.monitorOnlyExited(activeNetwork, action.reason)
        val state = ServiceState.connected(activeNetwork, System.currentTimeMillis())
        stateStore.setState(state)
        stateStore.emitEffect(effect)

        return ServiceActionResult(
            accepted = true,
            terminalState = state,
            effect = effect,
        )
    }

    /**
     * 处理停止动作。
     */
    private suspend fun handleStop(action: ServiceAction.Stop): ServiceActionResult {
        // 完全停止服务时同步清理仅监听标记。
        runtimeService.updateMonitorOnlyMode(false)
        stateStore.setState(ServiceState.stopping(action.reason))
        val stopResult = runtimeService.stopRuntime(keepServiceAlive = action.keepServiceAlive)
        if (!stopResult.isRuntimeSuccess() && stopResult.resultCode != RuntimeResultCode.NOT_RUNNING) {
            val error = stopResult.toServiceError(ServiceErrorCode.INTERNAL_ERROR)
            return terminalFailure(error)
        }
        stateStore.setState(ServiceState.stopped())
        if (!action.keepServiceAlive) {
            stopSelf()
        }
        return terminalSuccess(effect = null)
    }

    /**
     * 处理同步网络配置动作。
     */
    private suspend fun handleSyncNetworkConfig(
        networkId: NetworkId,
        configChanged: Boolean,
    ): ServiceActionResult {
        val runtimeNetwork = runtimeService.getNetworkConfig(networkId)
        if (runtimeNetwork != null) {
            applyRuntimeNetworkToRepository(runtimeNetwork)
        }
        val effect = ServiceEffect.networkConfigChanged(
            networkId = networkId,
            changed = configChanged && runtimeNetwork != null,
        )
        stateStore.emitEffect(effect)
        return terminalSuccess(effect)
    }

    /**
     * 处理隧道重配置动作。
     */
    private suspend fun handleReconfigureTunnel(action: ServiceAction.ReconfigureTunnel): ServiceActionResult {
        val result = runtimeService.establishVpnTunnel(
            VpnTunnelConfig(
                networkId = action.networkId,
                routeViaZeroTier = action.routeViaZeroTier,
                dnsMode = action.dnsMode,
                customDnsServers = action.customDnsServers,
                whitelistPackages = action.whitelistPackages,
                includeBuiltInWhitelistPackages = action.includeBuiltInWhitelistPackages,
                reason = action.reason,
            ),
        )
        if (!result.isRuntimeSuccess()) {
            if (result.requiresConnectingStateOnTunnelFailure()) {
                return holdConnectingState(
                    networkId = action.networkId,
                    reason = action.reason,
                    runtimeNetwork = runtimeService.getNetworkConfig(action.networkId),
                )
            }
            return terminalFailure(result.toServiceError(ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED))
        }
        // 重建隧道成功后进入已连接态，确保状态机与真实隧道状态一致。
        stateStore.setState(ServiceState.connected(action.networkId, System.currentTimeMillis()))
        val effect = ServiceEffect.networkConfigChanged(action.networkId, changed = true)
        stateStore.emitEffect(effect)
        return terminalSuccess(effect)
    }

    /**
     * 处理 Moon 入轨动作。
     */
    private suspend fun handleOrbitMoons(action: ServiceAction.OrbitMoons): ServiceActionResult {
        val result = runtimeService.orbitMoons(action.moons)
        if (!result.isRuntimeSuccess()) {
            return terminalFailure(result.toServiceError(ServiceErrorCode.INTERNAL_ERROR))
        }
        return terminalSuccess(effect = null)
    }

    /**
     * 处理 Moon 退轨动作。
     */
    private suspend fun handleDeorbitMoons(action: ServiceAction.DeorbitMoons): ServiceActionResult {
        val result = runtimeService.deorbitMoons(action.moonWorldIds)
        if (!result.isRuntimeSuccess()) {
            return terminalFailure(result.toServiceError(ServiceErrorCode.INTERNAL_ERROR))
        }
        return terminalSuccess(effect = null)
    }

    /**
     * 处理 Peer 查询动作。
     */
    private suspend fun handleQueryPeers(): ServiceActionResult {
        val peers = runtimeService.listPeers()
        logChain("查询节点 peers 完成 数量=${peers.size}")
        val effect = ServiceEffect.peerSnapshotUpdated(
            peerCount = peers.size,
            peers = peers,
        )
        stateStore.emitEffect(effect)
        return terminalSuccess(effect)
    }

    /**
     * 处理节点查询动作。
     */
    private suspend fun handleQueryNode(): ServiceActionResult {
        val nodeInfo = runtimeService.getNode()
        val effect = ServiceEffect.nodeInfoUpdated(nodeInfo)
        stateStore.emitEffect(effect)
        return terminalSuccess(effect)
    }

    /**
     * 处理网络配置查询动作。
     */
    private suspend fun handleQueryNetworkConfig(networkId: NetworkId): ServiceActionResult {
        val runtimeNetwork = runtimeService.getNetworkConfig(networkId)
        val effect = ServiceEffect.networkConfigChanged(networkId, changed = runtimeNetwork != null)
        stateStore.emitEffect(effect)
        return terminalSuccess(effect)
    }

    /**
     * 解析 StartOrResume 目标网络。
     */
    private suspend fun resolveStartOrResumeTargetNetworkId(action: ServiceAction.StartOrResume): NetworkId? {
        action.targetNetworkId?.let { return it }
        if (action.hasExplicitNetworkId) {
            return null
        }
        return networkRepository.findLastActivated()?.networkId
    }

    /**
     * 更新网络状态。
     */
    private suspend fun updateNetworkStatus(
        networkId: NetworkId,
        status: NetworkStatusEnum,
        clearRuntimeInfo: Boolean = false,
    ) {
        val current = networkRepository.findById(networkId) ?: return
        val next = if (clearRuntimeInfo) {
            current.copy(
                status = status,
                assignedIps = emptyList(),
                dnsServers = emptyList(),
                mac = "",
                mtu = null,
                broadcastEnabled = false,
                bridgingEnabled = false,
            )
        } else {
            current.copy(status = status)
        }
        networkRepository.upsert(next)
    }

    /**
     * 把 runtime 网络模型写回仓库。
     */
    private suspend fun applyRuntimeNetworkToRepository(
        runtimeNetworkInfo: RuntimeNetworkInfo,
    ) {
        val current = networkRepository.findById(runtimeNetworkInfo.networkId) ?: return
        // 名称更新策略：
        // 1. 仅在内核下发了“非空且不等于 networkId”的名称时覆盖；
        // 2. 避免把用户已有名称被回写成纯 networkId；
        // 3. 若本地为空，兜底使用 networkId。
        val runtimeDisplayName = runtimeNetworkInfo.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != runtimeNetworkInfo.networkId.value }
        val nextDisplayName = runtimeDisplayName
            ?: current.displayName.ifBlank { runtimeNetworkInfo.networkId.value }
        val next = current.copy(
            displayName = nextDisplayName,
            status = runtimeNetworkInfo.status,
            assignedIps = runtimeNetworkInfo.assignedIps,
            dnsServers = runtimeNetworkInfo.dnsServers,
            mac = runtimeNetworkInfo.mac,
            mtu = runtimeNetworkInfo.mtu,
            broadcastEnabled = runtimeNetworkInfo.broadcastEnabled,
            bridgingEnabled = runtimeNetworkInfo.bridgingEnabled,
        )
        networkRepository.upsert(next)
    }

    /**
     * 网络仍在“等待控制器授权/配置”阶段时，保持连接中状态，不上报失败。
     */
    private suspend fun holdConnectingState(
        networkId: NetworkId,
        reason: String,
        runtimeNetwork: RuntimeNetworkInfo?,
    ): ServiceActionResult {
        logChain(
            "等待控制器授权 networkId=${networkId.value} 原因=$reason 当前状态=${runtimeNetwork?.status ?: "UNKNOWN"}",
        )
        if (runtimeNetwork != null) {
            applyRuntimeNetworkToRepository(runtimeNetwork)
        } else {
            updateNetworkStatus(networkId, NetworkStatusEnum.REQUESTING_CONFIGURATION)
        }
        val waitingState = ServiceState.connecting(networkId, reason)
        stateStore.setState(waitingState)
        val effect = ServiceEffect.networkConfigChanged(
            networkId = networkId,
            changed = runtimeNetwork != null,
        )
        stateStore.emitEffect(effect)
        return ServiceActionResult(
            accepted = true,
            terminalState = waitingState,
            effect = effect,
            error = null,
        )
    }

    /**
     * 组装成功终态。
     */
    private suspend fun terminalSuccess(effect: ServiceEffect?): ServiceActionResult {
        val terminalState = stateStore.currentState()
        return ServiceActionResult(
            accepted = true,
            terminalState = terminalState,
            effect = effect,
            error = null,
        )
    }

    /**
     * 组装失败终态。
     */
    private suspend fun terminalFailure(
        error: ServiceError,
        effect: ServiceEffect? = null,
    ): ServiceActionResult {
        val errorState = ServiceState.error(error)
        stateStore.setState(errorState)
        stateStore.emitEffect(ServiceEffect.errorReported(error))
        return ServiceActionResult(
            accepted = true,
            terminalState = errorState,
            effect = effect,
            error = error,
        )
    }

    /**
     * 规范化 DNS 列表，避免无效参数进入 runtime 层。
     */
    private fun normalizeDnsServers(values: List<String>): List<String> {
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * 规范化包名列表，仅保留合法 Android 包名。
     */
    private fun normalizePackages(values: List<String>): List<String> {
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { PACKAGE_NAME_REGEX.matches(it) }
            .distinct()
            .toList()
    }

    /**
     * 构建观察管线。
     */
    private fun createObserverPipeline(): ServiceRuntimeObserverPipeline {
        val stateController = ServiceStateController(
            serviceScope = serviceScope,
            stateFlow = stateStore.state,
            notificationController = notificationController,
            resolveNetworkDisplayName = ::resolveNetworkDisplayName,
            idleNetworkLabelProvider = { getString(R.string.service_notification_idle_network) },
            monitorOnlyLabelProvider = { getString(R.string.service_notification_monitor_only_label) },
            monitorOnlyContentProvider = { getString(R.string.service_notification_monitor_only_content) },
            stoppingLabelProvider = { getString(R.string.service_notification_stopping_label) },
            errorLabelProvider = { getString(R.string.service_notification_error_label) },
            resolveErrorContent = ::resolveServiceErrorNotificationContent,
            onEnterForeground = ::startForegroundNotification,
            onExitForeground = { stopForegroundAndReset() },
        )
        val trafficController = ServiceTrafficController(
            serviceScope = serviceScope,
            stateFlow = stateStore.state,
            notificationController = notificationController,
            txBytesProvider = { runtimeContext.txBytes() },
            rxBytesProvider = { runtimeContext.rxBytes() },
            isForegroundStarted = { foregroundStarted },
            isScreenInteractive = { powerManager.isInteractive },
        )
        val networkController = ServiceNetworkController(
            serviceScope = serviceScope,
            networkChangeObserver = networkChangeObserver,
            routePolicyCoordinator = routePolicyCoordinator,
            runtimeContext = runtimeContext,
            dispatchAction = { next -> executeAction(next) },
            networkRepository = networkRepository,
        )
        return ServiceRuntimeObserverPipeline(
            stateController = stateController,
            trafficController = trafficController,
            networkController = networkController,
        )
    }

    /**
     * 启动前台通知（幂等）。
     */
    private fun startForegroundNotification() {
        val notificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        logChain("前台通知启动 通知权限可用=$notificationEnabled 已启动=$foregroundStarted")
        val notification = notificationController.buildForegroundNotification()
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ServiceNotificationController.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(ServiceNotificationController.NOTIFICATION_ID, notification)
            }
            return
        }
        foregroundStarted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ServiceNotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ServiceNotificationController.NOTIFICATION_ID, notification)
        }
    }

    /**
     * 对需要长时间运行的用户命令立即进入前台服务。
     *
     * 设计原因：
     * - Android 要求 `startForegroundService` 后尽快调用 `startForeground`；
     * - 通知应表达“用户已开启网络”，后续连接/仅监听/已连接状态再由 `ServiceStateController` 覆盖。
     */
    private fun startForegroundForAcceptedCommand(action: ServiceAction) {
        when (action) {
            is ServiceAction.Join -> {
                val networkId = action.params.networkId
                notificationController.bindConnectingNetwork(
                    networkName = action.params.displayName.ifBlank { networkId.value },
                    networkIdText = networkId.value,
                )
                startForegroundNotification()
            }

            is ServiceAction.ResumeRelay -> {
                notificationController.bindStatus(
                    title = getString(R.string.service_notification_connecting_title, getString(R.string.app_name)),
                    content = getString(R.string.service_notification_connecting_content),
                )
                startForegroundNotification()
            }

            is ServiceAction.StartOrResume -> {
                notificationController.bindStatus(
                    title = getString(R.string.service_notification_connecting_title, getString(R.string.app_name)),
                    content = getString(R.string.service_notification_connecting_content),
                )
                startForegroundNotification()
            }

            is ServiceAction.EnterMonitorOnly,
            is ServiceAction.Leave,
            is ServiceAction.Stop,
            is ServiceAction.SyncNetworkConfig,
            is ServiceAction.ReconfigureTunnel,
            is ServiceAction.OrbitMoons,
            is ServiceAction.DeorbitMoons,
            is ServiceAction.QueryPeers,
            is ServiceAction.QueryNode,
            is ServiceAction.QueryNetworkConfig,
            -> Unit
        }
    }

    /**
     * 兼容不同 Android 版本停止前台通知。
     */
    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(remove)
        }
    }

    /**
     * 停止前台通知并重置标记。
     */
    private fun stopForegroundAndReset() {
        if (!foregroundStarted) {
            return
        }
        logChain("前台通知退出 remove=true")
        foregroundStarted = false
        stopForegroundCompat(remove = true)
    }

    /**
     * 根据网络 ID 获取其可读名称。
     */
    private suspend fun resolveNetworkDisplayName(networkId: NetworkId): String {
        val entity = networkRepository.findById(networkId)
        return entity?.displayName?.ifBlank { networkId.value } ?: networkId.value
    }

    private fun logChain(message: String) {
        Log.i(TAG, "[$LOG_KEY] $message")
    }

    /**
     * 将服务错误映射为通知文案（本地化）。
     *
     * 说明：
     * 1. 通知属于用户可见内容，统一按错误码输出中英文资源文本；
     * 2. 避免直接暴露底层英文异常信息导致体验不一致；
     * 3. 仅在未知错误时兜底使用原始 message。
     */
    private fun resolveServiceErrorNotificationContent(error: ServiceError): String {
        return when (error.code) {
            ServiceErrorCode.VALIDATION_FAILED -> getString(R.string.service_error_validation_failed)
            ServiceErrorCode.RUNTIME_START_FAILED -> getString(R.string.service_error_runtime_start_failed)
            ServiceErrorCode.JOIN_FAILED -> getString(R.string.service_error_join_failed)
            ServiceErrorCode.LEAVE_FAILED -> getString(R.string.service_error_leave_failed)
            ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED -> getString(R.string.service_error_establish_tunnel_failed)
            ServiceErrorCode.POLICY_REJECTED -> getString(R.string.service_error_policy_rejected)
            ServiceErrorCode.PERMISSION_DENIED -> getString(R.string.service_error_permission_denied)
            ServiceErrorCode.INTERNAL_ERROR -> getString(R.string.service_error_internal)
            ServiceErrorCode.UNKNOWN -> error.message.ifBlank {
                getString(R.string.service_error_unknown)
            }
        }
    }

    private fun actionSummary(action: ServiceAction): String {
        return when (action) {
            is ServiceAction.StartOrResume -> "启动或恢复 原因=${action.reason} 目标=${action.targetNetworkId?.value ?: "none"} 显式网络=${action.hasExplicitNetworkId}"
            is ServiceAction.Join -> "加入网络 原因=${action.reason} networkId=${action.params.networkId.value} 显式网络=${action.hasExplicitNetworkId}"
            is ServiceAction.Leave -> "离开网络 原因=${action.reason} networkId=${action.networkId.value}"
            is ServiceAction.Stop -> "停止服务 原因=${action.reason} 保活=${action.keepServiceAlive}"
            is ServiceAction.EnterMonitorOnly -> "进入仅监听 原因=${action.reason} networkId=${action.networkId?.value ?: "none"}"
            is ServiceAction.ResumeRelay -> "恢复转发 原因=${action.reason}"
            is ServiceAction.SyncNetworkConfig ->
                "同步网络配置 原因=${action.reason} networkId=${action.networkId.value} 配置变化=${action.configChanged}"
            is ServiceAction.ReconfigureTunnel -> "重建隧道 原因=${action.reason} networkId=${action.networkId.value}"
            is ServiceAction.OrbitMoons -> "Moon 入轨 原因=${action.reason} 数量=${action.moons.size}"
            is ServiceAction.DeorbitMoons -> "Moon 退轨 原因=${action.reason} 数量=${action.moonWorldIds.size}"
            is ServiceAction.QueryPeers -> "查询节点 peers 原因=${action.reason}"
            is ServiceAction.QueryNode -> "查询节点信息 原因=${action.reason}"
            is ServiceAction.QueryNetworkConfig -> "查询网络配置 原因=${action.reason} networkId=${action.networkId.value}"
        }
    }

    companion object {
        private const val TAG = "ZeroTierVpnService"
        const val LOG_KEY: String = "ZTL_CHAIN"

        /** 包名格式校验器。 */
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /** 启动或恢复连接。 */
        const val ACTION_START_OR_RESUME: String = "io.github.jimmy.ztlink.action.START_OR_RESUME"

        /** 加入新网络。 */
        const val ACTION_JOIN: String = "io.github.jimmy.ztlink.action.JOIN"

        /** 离开已加网络。 */
        const val ACTION_LEAVE: String = "io.github.jimmy.ztlink.action.LEAVE"

        /** 完全停止服务及所有网络。 */
        const val ACTION_STOP: String = "io.github.jimmy.ztlink.action.STOP"

        /** 进入仅监控模式。 */
        const val ACTION_ENTER_MONITOR_ONLY: String = "io.github.jimmy.ztlink.action.ENTER_MONITOR_ONLY"

        /** 恢复中转模式。 */
        const val ACTION_RESUME_RELAY: String = "io.github.jimmy.ztlink.action.RESUME_RELAY"

        /** 同步网络配置。 */
        const val ACTION_SYNC_NETWORK_CONFIG: String = "io.github.jimmy.ztlink.action.SYNC_NETWORK_CONFIG"

        /** 重配置隧道。 */
        const val ACTION_RECONFIGURE_TUNNEL: String = "io.github.jimmy.ztlink.action.RECONFIGURE_TUNNEL"

        /** Moon 入轨。 */
        const val ACTION_ORBIT_MOONS: String = "io.github.jimmy.ztlink.action.ORBIT_MOONS"

        /** Moon 退轨。 */
        const val ACTION_DEORBIT_MOONS: String = "io.github.jimmy.ztlink.action.DEORBIT_MOONS"

        /** 查询 Peer。 */
        const val ACTION_QUERY_PEERS: String = "io.github.jimmy.ztlink.action.QUERY_PEERS"

        /** 查询节点信息。 */
        const val ACTION_QUERY_NODE: String = "io.github.jimmy.ztlink.action.QUERY_NODE"

        /** 查询网络配置。 */
        const val ACTION_QUERY_NETWORK_CONFIG: String = "io.github.jimmy.ztlink.action.QUERY_NETWORK_CONFIG"

        /** 意图传参：网络 ID。 */
        const val EXTRA_NETWORK_ID: String = "extra_network_id"
        /** 意图传参：配置是否发生变化。 */
        const val EXTRA_NETWORK_CONFIG_CHANGED: String = "extra_network_config_changed"

        /** 意图传参：是否通过 ZeroTier 路由。 */
        const val EXTRA_ROUTE_VIA_ZERO_TIER: String = "extra_route_via_zero_tier"

        /** 意图传参：DNS 模式代码。 */
        const val EXTRA_DNS_MODE_CODE: String = "extra_dns_mode_code"

        /** 意图传参：自定义 DNS 字符串。 */
        const val EXTRA_CUSTOM_DNS: String = "extra_custom_dns"

        /** 意图传参：自定义 DNS 列表。 */
        const val EXTRA_CUSTOM_DNS_SERVERS: String = "extra_custom_dns_servers"

        /** 意图传参：应用白名单列表。 */
        const val EXTRA_WHITELIST_PACKAGES: String = "extra_whitelist_packages"

        /** 意图传参：是否包含内置白名单。 */
        const val EXTRA_INCLUDE_BUILT_IN_WHITELIST: String = "extra_include_built_in_whitelist"

        /** 意图传参：详细信息。 */
        const val EXTRA_DETAIL: String = "extra_detail"

        /** 意图传参：Moon WorldId 列表。 */
        const val EXTRA_MOON_WORLD_IDS: String = "extra_moon_world_ids"

        /** 意图传参：Moon Seed 列表。 */
        const val EXTRA_MOON_SEEDS: String = "extra_moon_seeds"

        /** 意图传参：触发原因。 */
        const val EXTRA_REASON: String = "extra_reason"

        /** 意图传参：是否显式指定 networkId。 */
        const val EXTRA_HAS_EXPLICIT_NETWORK_ID: String = "extra_has_explicit_network_id"

        /** 意图传参：停止后是否保持服务存活。 */
        const val EXTRA_KEEP_SERVICE_ALIVE: String = "extra_keep_service_alive"
    }
}

/**
 * 服务动作执行结果。
 */
private data class ServiceActionResult(
    val accepted: Boolean,
    val terminalState: ServiceState,
    val effect: ServiceEffect? = null,
    val error: ServiceError? = null,
)

/**
 * 将启动 Service 的 Intent 转换为结构化的服务动作。
 */
private fun Intent.toServiceAction(): ServiceAction? {
    return when (action) {
        ZeroTierVpnService.ACTION_START_OR_RESUME -> {
            val hasExplicitNetworkId = if (hasExtra(ZeroTierVpnService.EXTRA_HAS_EXPLICIT_NETWORK_ID)) {
                getBooleanExtra(ZeroTierVpnService.EXTRA_HAS_EXPLICIT_NETWORK_ID, false)
            } else {
                hasExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)
            }
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
            ServiceAction.StartOrResume(
                targetNetworkId = networkId,
                hasExplicitNetworkId = hasExplicitNetworkId,
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "start_or_resume",
            )
        }

        ZeroTierVpnService.ACTION_JOIN -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
                ?: return null
            val dnsModeCode = max(getIntExtra(ZeroTierVpnService.EXTRA_DNS_MODE_CODE, 0), 0)
            val dnsMode = NetworkDnsModeEnum.fromCode(dnsModeCode)
            val params = JoinNetwork(
                networkId = networkId,
                routeViaZeroTier = getBooleanExtra(ZeroTierVpnService.EXTRA_ROUTE_VIA_ZERO_TIER, false),
                dnsMode = dnsMode,
                customDns = getStringExtra(ZeroTierVpnService.EXTRA_CUSTOM_DNS).orEmpty(),
                displayName = networkId.value,
            )
            ServiceAction.Join(
                params = params,
                hasExplicitNetworkId = true,
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "manual_join",
            )
        }

        ZeroTierVpnService.ACTION_LEAVE -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
                ?: return null
            ServiceAction.Leave(
                networkId = networkId,
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "manual_leave",
            )
        }

        ZeroTierVpnService.ACTION_STOP -> {
            ServiceAction.Stop(
                keepServiceAlive = getBooleanExtra(ZeroTierVpnService.EXTRA_KEEP_SERVICE_ALIVE, false),
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "manual_stop",
            )
        }

        ZeroTierVpnService.ACTION_ENTER_MONITOR_ONLY -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
            ServiceAction.EnterMonitorOnly(
                networkId = networkId,
                detail = getStringExtra(ZeroTierVpnService.EXTRA_DETAIL).orEmpty(),
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "manual_monitor_only",
            )
        }

        ZeroTierVpnService.ACTION_RESUME_RELAY -> {
            ServiceAction.ResumeRelay(
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "manual_resume",
            )
        }

        ZeroTierVpnService.ACTION_SYNC_NETWORK_CONFIG -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
                ?: return null
            ServiceAction.SyncNetworkConfig(
                networkId = networkId,
                configChanged = getBooleanExtra(ZeroTierVpnService.EXTRA_NETWORK_CONFIG_CHANGED, false),
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "sync_network_config",
            )
        }

        ZeroTierVpnService.ACTION_RECONFIGURE_TUNNEL -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
                ?: return null
            val dnsModeCode = max(getIntExtra(ZeroTierVpnService.EXTRA_DNS_MODE_CODE, 0), 0)
            val dnsMode = NetworkDnsModeEnum.fromCode(dnsModeCode)
            val customDnsServers = getStringArrayListExtra(ZeroTierVpnService.EXTRA_CUSTOM_DNS_SERVERS)
                ?.toList()
                ?.filter { it.isNotBlank() }
                ?: getStringExtra(ZeroTierVpnService.EXTRA_CUSTOM_DNS)
                    .orEmpty()
                    .split('\n', ',', ';', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val whitelistPackages =
                getStringArrayListExtra(ZeroTierVpnService.EXTRA_WHITELIST_PACKAGES)?.toList().orEmpty()
            ServiceAction.ReconfigureTunnel(
                networkId = networkId,
                routeViaZeroTier = getBooleanExtra(ZeroTierVpnService.EXTRA_ROUTE_VIA_ZERO_TIER, false),
                dnsMode = dnsMode,
                customDnsServers = customDnsServers,
                whitelistPackages = whitelistPackages,
                includeBuiltInWhitelistPackages = getBooleanExtra(
                    ZeroTierVpnService.EXTRA_INCLUDE_BUILT_IN_WHITELIST,
                    true,
                ),
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "network_config_changed",
            )
        }

        ZeroTierVpnService.ACTION_ORBIT_MOONS -> {
            val moons = parseMoonOrbitSpecs(
                moonWorldIds = getLongArrayExtra(ZeroTierVpnService.EXTRA_MOON_WORLD_IDS),
                moonSeeds = getLongArrayExtra(ZeroTierVpnService.EXTRA_MOON_SEEDS),
            )
            ServiceAction.OrbitMoons(
                moons = moons,
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "orbit_moons",
            )
        }

        ZeroTierVpnService.ACTION_DEORBIT_MOONS -> {
            ServiceAction.DeorbitMoons(
                moonWorldIds = getLongArrayExtra(ZeroTierVpnService.EXTRA_MOON_WORLD_IDS)?.toList().orEmpty(),
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "deorbit_moons",
            )
        }

        ZeroTierVpnService.ACTION_QUERY_PEERS -> {
            ServiceAction.QueryPeers(
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "query_peers",
            )
        }

        ZeroTierVpnService.ACTION_QUERY_NODE -> {
            ServiceAction.QueryNode(
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "query_node",
            )
        }

        ZeroTierVpnService.ACTION_QUERY_NETWORK_CONFIG -> {
            val networkId = getStringExtra(ZeroTierVpnService.EXTRA_NETWORK_ID)?.let(NetworkId::parse)
                ?: return null
            ServiceAction.QueryNetworkConfig(
                networkId = networkId,
                reason = getStringExtra(ZeroTierVpnService.EXTRA_REASON) ?: "query_network_config",
            )
        }

        else -> null
    }
}

/**
 * 解析 Moon 入轨参数。
 */
private fun parseMoonOrbitSpecs(
    moonWorldIds: LongArray?,
    moonSeeds: LongArray?,
): List<RuntimeMoonOrbit> {
    val ids = moonWorldIds ?: return emptyList()
    val seeds = moonSeeds ?: return emptyList()
    if (ids.size != seeds.size) {
        return emptyList()
    }
    return ids.indices.map { index ->
        RuntimeMoonOrbit(
            moonWorldId = ids[index],
            moonSeed = seeds[index],
        )
    }
}

/**
 * 判断 Runtime 结果是否可作为成功。
 */
private fun RuntimeResult.isRuntimeSuccess(): Boolean {
    return resultCode == RuntimeResultCode.SUCCESS || resultCode == RuntimeResultCode.ALREADY_RUNNING
}

/**
 * 判定“隧道暂不可用但应继续保持连接中”场景。
 *
 * 设计说明：
 * 1. PERMISSION_DENIED 表示系统侧暂时拒绝建立隧道；
 * 2. INTERNAL_ERROR + establish 返回 null（且已授权）也属于可重试场景；
 * 3. 以上场景都不应直接打 ERROR 终态。
 */
private fun RuntimeResult.requiresConnectingStateOnTunnelFailure(): Boolean {
    return type == RuntimeResultType.ESTABLISH_VPN_TUNNEL &&
        keepConnectingOnTunnelFailure == true
}

/**
 * Runtime 结果映射为 ServiceError。
 */
private fun RuntimeResult.toServiceError(defaultCode: ServiceErrorCode): ServiceError {
    val mappedCode = when (resultCode) {
        RuntimeResultCode.PERMISSION_DENIED -> ServiceErrorCode.PERMISSION_DENIED
        RuntimeResultCode.NETWORK_NOT_FOUND -> ServiceErrorCode.VALIDATION_FAILED
        RuntimeResultCode.INVALID_ARGUMENT -> ServiceErrorCode.VALIDATION_FAILED
        RuntimeResultCode.INTERNAL_ERROR -> ServiceErrorCode.INTERNAL_ERROR
        RuntimeResultCode.UNKNOWN_ERROR -> ServiceErrorCode.UNKNOWN
        RuntimeResultCode.NOT_RUNNING -> ServiceErrorCode.RUNTIME_START_FAILED
        else -> defaultCode
    }
    return ServiceError(
        code = mappedCode,
        message = message.ifBlank { resultCode.name },
        recoverable = true,
    )
}

/**
 * 自定义 DNS 字符串解析为服务器列表。
 */
private fun String.parseDnsServers(): List<String> {
    if (isBlank()) {
        return emptyList()
    }
    return split('\n', ',', ' ', ';')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

/**
 * 从网络实体解析自定义 DNS 列表。
 */
private fun NetworkEntity.resolveCustomDnsServers(): List<String> {
    val fromDnsServers = dnsServers
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (fromDnsServers.isNotEmpty()) {
        return fromDnsServers
    }
    return config.customDns.split('\n', ',', ';', ' ')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

/**
 * 网络实体转换为 Join 参数。
 */
private fun NetworkEntity.toJoinParams(): JoinNetwork {
    val customDnsValue = if (dnsServers.isNotEmpty()) {
        dnsServers.joinToString(separator = "\n")
    } else {
        config.customDns
    }
    return JoinNetwork(
        networkId = networkId,
        routeViaZeroTier = config.routeViaZeroTier,
        dnsMode = config.dnsMode,
        customDns = customDnsValue,
        displayName = displayName,
    )
}
