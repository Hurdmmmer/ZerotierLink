package io.github.jimmy.ztlink.app.ui.components.network

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent
import io.github.jimmy.ztlink.data.network.NetworkRepository
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.model.network.NetworkConfig
import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.model.network.NetworkEntity
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceErrorCode
import io.github.jimmy.ztlink.model.service.ServiceEffectType
import io.github.jimmy.ztlink.model.service.ServiceStateType
import io.github.jimmy.ztlink.service.ServiceAction
import io.github.jimmy.ztlink.service.ServiceActionDispatcher
import io.github.jimmy.ztlink.service.ServiceStateStore
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.service.observer.NetworkTransport
import io.github.jimmy.ztlink.service.policy.ServiceStartNetworkGuard
import io.github.jimmy.ztlink.util.ChainLog
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网络列表与加入网络页面共享的状态模型。
 *
 * @property networks 网络列表页展示数据。
 * @property details 网络详情映射（networkId -> detail）。
 * @property processingIds 正在执行开关动作的网络 ID 集合。
 * @property isLoading 当前是否正在加载初始数据。
 */
data class NetworksUiState(
    val networks: List<NetworkListItem> = emptyList(),
    val details: Map<String, NetworkDetail> = emptyMap(),
    val planetRouteType: PlanetRouteType = PlanetRouteType.OFFICIAL,
    val planetRootServerIp: String? = null,
    val planetUseCustom: Boolean = false,
    val planetAutoRouteCheck: Boolean = false,
    val planetIntranetProbeIp: String = "",
    val processingIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)

/**
 * 网络页面一次性事件。
 */
sealed interface NetworksUiEvent : CommonUiEvent {

    /**
     * Toast 提示事件。
     */
    data class ShowToast(
        override val messageRes: Int,
    ) : NetworksUiEvent, CommonUiEvent.ShowToast

    /**
     * 纯文本 Toast 提示事件。
     */
    data class ShowToastText(
        override val messageText: String,
    ) : NetworksUiEvent, CommonUiEvent.ShowToastText

    /**
     * 复制网络 ID 事件。
     */
    data class CopyNetworkId(
        val networkId: String,
        override val successMsgRes: Int = R.string.network_copy_success,
    ) : NetworksUiEvent, CommonUiEvent.CopyToClipboard {
        override val text: String get() = networkId
        override val label: String get() = "network-id"
    }

    /**
     * 开启网络前的内网确认弹窗事件。
     */
    data class ConfirmIntranetEnvironment(
        val networkId: String,
        val currentWifiIpv4: String?,
    ) : NetworksUiEvent

    /** 导航返回事件。 */
    data object NavigateBack : NetworksUiEvent
}

/**
 * 网络页面 ViewModel。
 *
 * 说明：
 * 1. 网络列表数据以 `NetworkRepository.observeAll()` 作为单一事实来源；
 * 2. UI 只负责表达意图（ServiceAction），连接状态流转由 Service 负责；
 * 3. 通过 `processingIds` 约束并发点击，避免开关抖动与重复操作。
 */
@HiltViewModel
class NetworksViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    /** 网络聚合仓库。 */
    private val networkRepository: NetworkRepository,
    /** 服务动作派发器。 */
    private val actionDispatcher: ServiceActionDispatcher,
    /** 服务状态与副作用仓库。 */
    private val serviceStateStore: ServiceStateStore,
    /** 服务启动前置门禁。 */
    private val startNetworkGuard: ServiceStartNetworkGuard,
    /** 网络状态观察器。 */
    private val networkChangeObserver: NetworkChangeObserver,
    /** 设置状态单例持有器。 */
    private val settingsStateHolder: SettingsStateHolder,
) : ViewModel() {

    /**
     * 最新网络实体状态。
     *
     * 说明：
     * - 网络列表 UI 由“数据库快照 + 运行态开关网络 ID”共同决定；
     * - 这里缓存数据库快照，便于服务状态变化时直接重绘，不必等待 DB 再发一轮。
     */
    private var latestEntities: List<NetworkEntity> = emptyList()

    /**
     * 当前处于“开关开启”态的网络 ID（仅内存态）。
     *
     * 说明：
     * - 对齐老项目：开关状态是运行态，不落库；
     * - 进程重启后该值自然丢失，列表默认全关。
     */
    private var activeSwitchNetworkId: String? = null

    /**
     * 当前真正已连通的网络 ID（服务状态为 CONNECTED 时才会更新）。
     *
     * 说明：
     * - 用于区分“控制器已授权（内核 OK）”和“隧道已建立（服务 CONNECTED）”；
     * - 只有后者才在 UI 呈现“已连接”。
     */
    private var activeConnectedNetworkId: String? = null

    /**
     * 当前处于“仅监控模式”的网络 ID（服务态 MONITOR_ONLY）。
     *
     * 关键逻辑：
     * - 该字段只用于 UI 展示“监控中”，不参与连接动作决策；
     * - 与 activeConnectedNetworkId 互斥。
     */
    private var activeMonitorOnlyNetworkId: String? = null

    /** 当前已连接网络的 Peer 分类状态。 */
    private var peerSnapshot: PeerSnapshot = PeerSnapshot()

    /** 最近一次请求 Peer 状态的网络 ID，用于避免重复派发。 */
    private var lastPeerQueryNetworkId: String? = null

    /** 页面状态流。 */
    private val _uiState: MutableStateFlow<NetworksUiState> = MutableStateFlow(NetworksUiState())
    /** 页面状态流（只读）。 */
    val uiState: StateFlow<NetworksUiState> = _uiState.asStateFlow()

    /** 一次性事件流。 */
    private val _uiEvents: MutableSharedFlow<NetworksUiEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    /** 一次性事件流（只读）。 */
    val uiEvents: SharedFlow<NetworksUiEvent> = _uiEvents.asSharedFlow()

    /** 开关动作互斥锁，避免快速连点导致本地并发覆盖。 */
    private val toggleMutex: Mutex = Mutex()

    init {
        startObserveNetworks()
        startObserveServiceState()
        startObserveServiceEffects()
        startObserveSettingsState()
    }

    /**
     * 执行加入网络入库操作。
     *
     * 说明：
     * 1. Join 页面只负责保存网络配置，不直接触发连接；
     * 2. 真正连接由网络列表开关触发（toggle -> ServiceAction.Join）。
     *
     * @param networkId 网络 ID 文本。
     * @param defaultRoute 是否启用默认路由。
     * @param dnsMode DNS 模式。
     * @param customDnsList 自定义 DNS 文本列表（最多 4 项，支持 IPv4/IPv6）。
     */
    fun joinNetwork(
        networkId: String,
        defaultRoute: Boolean,
        dnsMode: DnsMode,
        customDnsList: List<String>,
    ) {
        viewModelScope.launch {
            val parsedId = NetworkId.parse(networkId)
            if (parsedId == null) {
                emitToast(R.string.network_id_error)
                return@launch
            }
            if (networkRepository.findById(parsedId) != null) {
                emitToast(R.string.network_exists_already)
                _uiEvents.tryEmit(NetworksUiEvent.NavigateBack)
                return@launch
            }

            val normalizedDnsList = if (dnsMode == DnsMode.CUSTOM) {
                customDnsList.map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val customDnsValue = normalizedDnsList.firstOrNull().orEmpty()
            val entity = NetworkEntity(
                networkId = parsedId,
                displayName = parsedId.value,
                isEnabled = false,
                lastActivated = false,
                config = NetworkConfig(
                    routeViaZeroTier = defaultRoute,
                    dnsMode = dnsMode.toModelDnsMode(),
                    customDns = customDnsValue,
                ),
                status = NetworkStatusEnum.DISCONNECTED,
                assignedIps = emptyList(),
                dnsServers = normalizedDnsList,
                mac = "",
                mtu = null,
                broadcastEnabled = false,
                bridgingEnabled = false,
            )
            networkRepository.upsert(entity)
            logChain("仅保存加入配置 networkId=${parsedId.value} 启用=false 状态=DISCONNECTED")
            _uiEvents.tryEmit(NetworksUiEvent.NavigateBack)
        }
    }

    /**
     * 更新网络启用状态。
     *
     * 关键逻辑：
     * 1. 开关状态仅保存在内存（activeSwitchNetworkId），不写数据库；
     * 2. 连接状态（REQUESTING/CONNECTED/DISCONNECTED）统一由 Service 写回；
     * 3. 开关中的异步阶段由 processingIds 标记，防止重复点击。
     *
     * @param networkId 网络 ID 文本。
     * @param enabled 目标启用状态。
     */
    fun toggleNetwork(
        networkId: String,
        enabled: Boolean,
    ) {
        if (enabled) {
            requestEnableNetwork(networkId)
            return
        }
        viewModelScope.launch {
            toggleMutex.withLock {
                val parsedId = NetworkId.parse(networkId) ?: return@withLock
                if (_uiState.value.processingIds.contains(parsedId.value)) {
                    return@withLock
                }
                networkRepository.findById(parsedId) ?: return@withLock

                markProcessing(parsedId.value, processing = true)
                runCatching {
                    actionDispatcher.dispatch(
                        ServiceAction.Leave(
                            networkId = parsedId,
                            reason = "ui_toggle_disable",
                        ),
                    )
                }.onFailure {
                    markProcessing(parsedId.value, processing = false)
                    emitToast(R.string.network_action_failed)
                }
            }
        }
    }

    /**
     * 请求开启网络（含前置检查）。
     *
     * 交互约束：
     * 1. 前置检查失败时不派发 Join，开关保持当前状态；
     * 2. Join 派发后不做本地乐观开关，等待服务状态回流后再更新 UI；
     * 3. 仅在派发失败时回退 processing 标记。
     */
    fun requestEnableNetwork(networkId: String) {
        requestEnableNetworkInternal(
            networkId = networkId,
            skipIntranetPrompt = false,
        )
    }

    /**
     * 处理内网确认弹窗后的用户选择。
     *
     * @param networkId 网络 ID。
     * @param rememberCurrentIp 是否记录当前 Wi-Fi IP 作为内网探测 IP。
     * @param currentWifiIpv4 当前 Wi-Fi IP。
     */
    fun confirmIntranetPromptAndEnable(
        networkId: String,
        rememberCurrentIp: Boolean,
        currentWifiIpv4: String?,
    ) {
        viewModelScope.launch {
            if (rememberCurrentIp) {
                val normalizedIp = currentWifiIpv4?.trim().orEmpty()
                if (normalizedIp.isNotBlank()) {
                    settingsStateHolder.updateState { old ->
                        if (!old.planetUseCustom || !old.planetAutoRouteCheck) {
                            old
                        } else {
                            old.copy(planetIntranetProbeIp = normalizedIp)
                        }
                    }
                }
            }
            requestEnableNetworkInternal(
                networkId = networkId,
                skipIntranetPrompt = true,
            )
        }
    }

    private fun requestEnableNetworkInternal(
        networkId: String,
        skipIntranetPrompt: Boolean,
    ) {
        viewModelScope.launch {
            toggleMutex.withLock {
                val parsedId = NetworkId.parse(networkId) ?: return@withLock
                if (_uiState.value.processingIds.contains(parsedId.value)) {
                    return@withLock
                }
                val current = networkRepository.findById(parsedId) ?: return@withLock
                val hasOtherEnabledNetwork = _uiState.value.networks.any {
                    it.isEnabled && it.networkId != parsedId.value
                }
                val hasOtherNetworkProcessing = _uiState.value.processingIds.any {
                    it != parsedId.value
                }
                if (hasOtherEnabledNetwork || hasOtherNetworkProcessing) {
                    emitToast(R.string.network_only_one_active)
                    return@withLock
                }
                if (!skipIntranetPrompt && shouldShowIntranetPrompt()) {
                    _uiEvents.tryEmit(
                        NetworksUiEvent.ConfirmIntranetEnvironment(
                            networkId = parsedId.value,
                            currentWifiIpv4 = networkChangeObserver.currentWifiIpv4Address(),
                        ),
                    )
                    return@withLock
                }

                val guardError = startNetworkGuard.validateStartOrJoin()
                if (guardError != null) {
                    logChain(
                        "前置检查拦截 networkId=${parsedId.value} code=${guardError.code} reason=${guardError.message}",
                    )
                    emitToastText(resolveServiceErrorToastText(guardError))
                    return@withLock
                }

                markProcessing(parsedId.value, processing = true)
                runCatching {
                    actionDispatcher.dispatch(
                        ServiceAction.Join(
                            params = current.toJoinParams(),
                            reason = "ui_toggle_enable",
                        ),
                    )
                }.onFailure {
                    markProcessing(parsedId.value, processing = false)
                    emitToast(R.string.network_action_failed)
                }
            }
        }
    }

    /**
     * 提示用户需要先完成系统 VPN 授权。
     *
     * 说明：
     * - 该提示由列表页在“点击开启但尚未授权”时触发；
     * - 不改变开关状态，仅给出明确引导。
     */
    fun notifyVpnAuthorizationRequired() {
        emitToast(R.string.network_vpn_permission_required)
    }

    /**
     * 更新网络是否走 ZeroTier 默认路由。
     *
     * @param networkId 网络 ID 文本。
     * @param routeViaZeroTier 目标路由开关。
     */
    fun toggleRouteViaZeroTier(
        networkId: String,
        routeViaZeroTier: Boolean,
    ) {
        viewModelScope.launch {
            val parsedId = NetworkId.parse(networkId) ?: return@launch
            val current = networkRepository.findById(parsedId) ?: return@launch
            logChain(
                "详情页切换默认路由 networkId=${parsedId.value} old=${current.config.routeViaZeroTier} new=$routeViaZeroTier activeSwitch=${activeSwitchNetworkId ?: "none"}",
            )
            if (current.config.routeViaZeroTier == routeViaZeroTier) {
                logChain("默认路由未变化，忽略后续处理 networkId=${parsedId.value}")
                return@launch
            }
            val nextEntity = current.copy(
                config = current.config.copy(routeViaZeroTier = routeViaZeroTier),
            )
            runCatching {
                // 第一步：先持久化配置，保证 UI 与后续服务动作都基于同一份最新配置。
                networkRepository.upsert(nextEntity)
                logChain(
                    "更新默认路由 networkId=${parsedId.value} 启用=$routeViaZeroTier",
                )

                // 第二步：对齐老项目行为。
                // 若当前网络正在运行（开关打开），用户改 default route 后立即重建隧道，
                // 让新路由策略立刻生效，而不是等待下一次内核配置更新回调。
                if (activeSwitchNetworkId == parsedId.value) {
                    actionDispatcher.dispatch(
                        ServiceAction.ReconfigureTunnel(
                            networkId = parsedId,
                            routeViaZeroTier = routeViaZeroTier,
                            dnsMode = nextEntity.config.dnsMode,
                            customDnsServers = nextEntity.resolveCustomDnsServers(),
                            reason = "ui_network_detail_default_route_changed",
                        ),
                    )
                    logChain(
                        "默认路由变更已触发隧道重建 networkId=${parsedId.value}",
                    )
                } else {
                    logChain(
                        "默认路由变更仅保存配置（网络未运行） networkId=${parsedId.value}",
                    )
                }
            }.onFailure {
                logChain(
                    "默认路由更新失败 networkId=${parsedId.value} 目标值=$routeViaZeroTier",
                )
                emitToast(R.string.network_action_failed)
            }
        }
    }

    /**
     * 删除网络及其关联数据。
     *
     * @param networkId 网络 ID 文本。
     */
    fun deleteNetwork(
        networkId: String,
    ) {
        viewModelScope.launch {
            val parsedId = NetworkId.parse(networkId) ?: return@launch
            networkRepository.deleteById(parsedId)
        }
    }

    /**
     * 请求复制网络 ID。
     *
     * @param networkId 网络 ID 文本。
     */
    fun requestCopyNetworkId(
        networkId: String,
    ) {
        _uiEvents.tryEmit(NetworksUiEvent.CopyNetworkId(networkId, R.string.network_copy_success))
    }

    /**
     * 查询网络详情。
     *
     * @param networkId 网络 ID 文本。
     * @return 网络详情，未命中返回 null。
     */
    fun findNetworkDetail(
        networkId: String,
    ): NetworkDetail? {
        return _uiState.value.details[networkId]
    }

    /**
     * 订阅网络仓库的响应式数据流。
     */
    private fun startObserveNetworks() {
        viewModelScope.launch {
            networkRepository.observeAll().collectLatest { entities ->
                latestEntities = entities
                applyEntitiesToUiState(entities)
            }
        }
    }

    /**
     * 订阅服务状态流，同步运行态开关网络。
     */
    private fun startObserveServiceState() {
        viewModelScope.launch {
            serviceStateStore.state.collectLatest { state ->
                val previousConnectedNetworkId = activeConnectedNetworkId
                activeSwitchNetworkId = when (state.type) {
                    ServiceStateType.STARTING -> state.networkId?.value ?: activeSwitchNetworkId
                    ServiceStateType.CONNECTING,
                    ServiceStateType.CONNECTED,
                    ServiceStateType.MONITOR_ONLY,
                    -> state.networkId?.value

                    ServiceStateType.STOPPED,
                    ServiceStateType.STOPPING,
                    ServiceStateType.ERROR,
                    -> null
                }
                activeConnectedNetworkId = if (state.type == ServiceStateType.CONNECTED) {
                    state.networkId?.value
                } else {
                    null
                }
                activeMonitorOnlyNetworkId = if (state.type == ServiceStateType.MONITOR_ONLY) {
                    state.networkId?.value
                } else {
                    null
                }
                if (state.type == ServiceStateType.MONITOR_ONLY) {
                    logChain("服务状态进入监控态 networkId=${state.networkId?.value ?: "none"} detail=${state.detail ?: "none"}")
                }
                val connectedNetworkId = activeConnectedNetworkId
                if (connectedNetworkId != previousConnectedNetworkId) {
                    if (connectedNetworkId == null) {
                        peerSnapshot = PeerSnapshot()
                        lastPeerQueryNetworkId = null
                    } else {
                        persistLastActivated(connectedNetworkId)
                        requestPeerSnapshot(connectedNetworkId, force = true)
                    }
                }
                applyEntitiesToUiState(latestEntities)
            }
        }
    }

    /**
     * 订阅服务副作用，完成开关处理闭环。
     */
    private fun startObserveServiceEffects() {
        viewModelScope.launch {
            serviceStateStore.effects.collectLatest { effect ->
                when (effect.type) {
                    ServiceEffectType.JOIN_SUCCESS,
                    ServiceEffectType.JOIN_FAILED,
                    ServiceEffectType.LEAVE_DONE,
                    ServiceEffectType.NETWORK_CONFIG_CHANGED,
                    ServiceEffectType.MONITOR_ONLY_ENTERED,
                    ServiceEffectType.MONITOR_ONLY_EXITED,
                    -> {
                        // 关键点：
                        // 当链路进入“等待控制器授权/配置中”时，Service 会发 NETWORK_CONFIG_CHANGED，
                        // 这里需要及时解除 processing，避免 UI 开关长期显示“处理中”。
                        // 同理，进入/退出仅监听也属于“本次开关动作已完成”，必须解除 processing。
                        effect.networkId?.value?.let { id ->
                            val shouldClearProcessing = _uiState.value.processingIds.contains(id)
                            if (shouldClearProcessing) {
                                logChain("效果回调解除处理中 type=${effect.type} networkId=$id")
                                markProcessing(id, processing = false)
                            }
                        }
                        if (effect.type == ServiceEffectType.JOIN_FAILED) {
                            activeSwitchNetworkId = null
                            applyEntitiesToUiState(latestEntities)
                            val error = effect.error
                            if (error != null) {
                                emitToastText(resolveServiceErrorToastText(error))
                            } else {
                                emitToast(R.string.network_action_failed)
                            }
                        }
                    }

                    ServiceEffectType.PEER_SNAPSHOT_UPDATED -> {
                        val peers = effect.peers.orEmpty()
                        peerSnapshot = peers.toPeerSnapshot()
                        applyEntitiesToUiState(latestEntities)
                    }

                    ServiceEffectType.ERROR_REPORTED -> {
                        clearAllProcessing()
                        peerSnapshot = PeerSnapshot()
                        lastPeerQueryNetworkId = null
                        emitToast(R.string.network_action_failed)
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * 订阅设置状态，实时同步 Planet 链路来源展示。
     */
    private fun startObserveSettingsState() {
        viewModelScope.launch {
            settingsStateHolder.state.collectLatest { settings ->
                _uiState.update { old ->
                    old.copy(
                        planetRouteType = settings.toPlanetRouteType(),
                        planetUseCustom = settings.planetUseCustom,
                        planetAutoRouteCheck = settings.planetAutoRouteCheck,
                        planetIntranetProbeIp = settings.planetIntranetProbeIp,
                    )
                }
            }
        }
    }

    private fun shouldShowIntranetPrompt(): Boolean {
        val state = _uiState.value
        if (!state.planetUseCustom) {
            return false
        }
        if (!state.planetAutoRouteCheck) {
            return false
        }
        if (state.planetIntranetProbeIp.isNotBlank()) {
            return false
        }
        return networkChangeObserver.currentTransport() == NetworkTransport.WIFI
    }

    /**
     * 应用网络实体列表到页面状态。
     */
    private fun applyEntitiesToUiState(entities: List<NetworkEntity>) {
        val enabledNetworkId = activeSwitchNetworkId
        val connectedNetworkId = activeConnectedNetworkId
        val monitorOnlyNetworkId = activeMonitorOnlyNetworkId
        val p2pSummary = peerSnapshot.toSummaryText(appContext)
        _uiState.update { old ->
            old.copy(
                networks = entities.map { entity ->
                    val isSwitchEnabled = entity.networkId.value == enabledNetworkId
                    val isConnected = entity.networkId.value == connectedNetworkId
                    val isMonitorOnly = entity.networkId.value == monitorOnlyNetworkId
                    val isLanVisible = isConnected && entity.isLanNetwork()
                    entity.toListItem(
                        isSwitchEnabled = isSwitchEnabled,
                        isConnected = isConnected,
                        isMonitorOnly = isMonitorOnly,
                        isLan = isLanVisible,
                        p2pSummary = if (isConnected) p2pSummary else "",
                    )
                },
                details = entities.associate { entity ->
                    val isSwitchEnabled = entity.networkId.value == enabledNetworkId
                    val isConnected = entity.networkId.value == connectedNetworkId
                    val isMonitorOnly = entity.networkId.value == monitorOnlyNetworkId
                    val isLanVisible = isConnected && entity.isLanNetwork()
                    entity.networkId.value to entity.toDetail(
                        isSwitchEnabled = isSwitchEnabled,
                        isConnected = isConnected,
                        isMonitorOnly = isMonitorOnly,
                        isLan = isLanVisible,
                    )
                },
                planetRootServerIp = peerSnapshot.primaryRootServerIp,
                isLoading = false,
            )
        }
    }

    /**
     * 标记某个网络是否处理中。
     */
    private fun markProcessing(
        networkId: String,
        processing: Boolean,
    ) {
        _uiState.update { old ->
            val next = old.processingIds.toMutableSet()
            if (processing) {
                next.add(networkId)
            } else {
                next.remove(networkId)
            }
            old.copy(processingIds = next)
        }
    }

    /**
     * 清空全部处理中标记。
     */
    private fun clearAllProcessing() {
        _uiState.update { old ->
            old.copy(processingIds = emptySet())
        }
    }

    /**
     * 派发 Toast 事件。
     *
     * @param messageRes 文案资源 ID。
     */
    private fun emitToast(
        @StringRes messageRes: Int,
    ) {
        _uiEvents.tryEmit(NetworksUiEvent.ShowToast(messageRes))
    }

    /**
     * 派发纯文本 Toast 事件。
     */
    private fun emitToastText(message: String) {
        if (message.isBlank()) {
            return
        }
        _uiEvents.tryEmit(NetworksUiEvent.ShowToastText(message))
    }

    /**
     * 将服务错误转换为用户可读提示。
     */
    private fun resolveServiceErrorToastText(error: ServiceError): String {
        if (error.code == ServiceErrorCode.VALIDATION_FAILED && error.message.isNotBlank()) {
            return error.message
        }
        val messageRes = when (error.code) {
            ServiceErrorCode.VALIDATION_FAILED -> R.string.service_error_validation_failed
            ServiceErrorCode.RUNTIME_START_FAILED -> R.string.service_error_runtime_start_failed
            ServiceErrorCode.JOIN_FAILED -> R.string.service_error_join_failed
            ServiceErrorCode.LEAVE_FAILED -> R.string.service_error_leave_failed
            ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED -> R.string.service_error_establish_tunnel_failed
            ServiceErrorCode.POLICY_REJECTED -> R.string.service_error_policy_rejected
            ServiceErrorCode.PERMISSION_DENIED -> R.string.service_error_permission_denied
            ServiceErrorCode.INTERNAL_ERROR -> R.string.service_error_internal
            ServiceErrorCode.UNKNOWN -> R.string.service_error_unknown
        }
        return appContext.getString(messageRes)
    }

    private fun requestPeerSnapshot(networkId: String, force: Boolean) {
        if (!force && lastPeerQueryNetworkId == networkId) {
            return
        }
        lastPeerQueryNetworkId = networkId
        runCatching {
            actionDispatcher.dispatch(
                ServiceAction.QueryPeers(
                    reason = "ui_network_list_peer_snapshot",
                ),
            )
        }.onFailure {
            logChain("peer snapshot request failed networkId=$networkId")
        }
    }

    /**
     * 连接成功后持久化最近激活网络。
     *
     * 设计原因：
     * - 避免“点击开关瞬间写 lastActivated”导致列表重排，造成目标漂移；
     * - 仅在服务进入 CONNECTED 后再写入，既保留恢复语义，又不破坏交互稳定性。
     */
    private fun persistLastActivated(networkId: String) {
        val parsedId = NetworkId.parse(networkId) ?: return
        viewModelScope.launch {
            runCatching {
                networkRepository.setLastActivated(parsedId)
            }.onFailure {
                logChain("persist lastActivated failed networkId=$networkId")
            }
        }
    }

    private fun logChain(message: String) {
        ChainLog.i(TAG, message)
    }

    private companion object {
        private const val TAG = "NetworksViewModel"
    }
}

/**
 * 将 UI DNS 模式映射为模型层 DNS 模式。
 *
 * @return 模型层 DNS 模式。
 */
private fun DnsMode.toModelDnsMode(): NetworkDnsModeEnum {
    return when (this) {
        DnsMode.NONE -> NetworkDnsModeEnum.NONE
        DnsMode.NETWORK -> NetworkDnsModeEnum.NETWORK
        DnsMode.CUSTOM -> NetworkDnsModeEnum.CUSTOM
    }
}

/**
 * 将模型层实体映射为列表页项。
 *
 * @return 列表页项。
 */
private fun NetworkEntity.toListItem(
    isSwitchEnabled: Boolean,
    isConnected: Boolean,
    isMonitorOnly: Boolean,
    isLan: Boolean,
    p2pSummary: String,
): NetworkListItem {
    return NetworkListItem(
        networkId = networkId.value,
        name = displayName.ifBlank { networkId.value },
        status = toUiStatus(
            isSwitchEnabled = isSwitchEnabled,
            isConnected = isConnected,
            isMonitorOnly = isMonitorOnly,
        ),
        isEnabled = isSwitchEnabled,
        isLan = isLan,
        p2pSummary = p2pSummary,
        assignedIps = assignedIps,
    )
}

/**
 * 将模型层实体映射为详情页数据。
 *
 * @return 详情页数据。
 */
private fun NetworkEntity.toDetail(
    isSwitchEnabled: Boolean,
    isConnected: Boolean,
    isMonitorOnly: Boolean,
    isLan: Boolean,
): NetworkDetail {
    return NetworkDetail(
        networkId = networkId.value,
        name = displayName.ifBlank { networkId.value },
        status = toUiStatus(
            isSwitchEnabled = isSwitchEnabled,
            isConnected = isConnected,
            isMonitorOnly = isMonitorOnly,
        ),
        type = "Private",
        mac = mac.ifBlank { "-" },
        mtu = mtu ?: 2800,
        broadcastEnabled = broadcastEnabled,
        bridgingEnabled = bridgingEnabled,
        assignedIps = assignedIps,
        dnsServers = dnsServers,
        dnsMode = when (config.dnsMode) {
            NetworkDnsModeEnum.NONE -> DnsMode.NONE
            NetworkDnsModeEnum.NETWORK -> DnsMode.NETWORK
            NetworkDnsModeEnum.CUSTOM -> DnsMode.CUSTOM
        },
        defaultRoute = config.routeViaZeroTier,
        isLan = isLan,
    )
}

/**
 * 将模型层连接状态映射为 UI 状态。
 *
 * @return UI 状态。
 */
private fun NetworkEntity.toUiStatus(
    isSwitchEnabled: Boolean,
    isConnected: Boolean,
    isMonitorOnly: Boolean,
): NetworkStatus {
    // 交互策略（按需求）：
    // 1) 开关关闭：状态固定显示断开；
    // 2) 开关打开但服务未进入 CONNECTED：统一显示等待；
    // 3) 只有“开关开启 + 服务 CONNECTED + 内核 OK”才显示已连接。
    if (!isSwitchEnabled) {
        return NetworkStatus.DISCONNECTED
    }
    if (isMonitorOnly) {
        return NetworkStatus.MONITORING
    }
    if (isConnected && status == NetworkStatusEnum.OK) {
        return NetworkStatus.CONNECTED
    }
    return when (status) {
        NetworkStatusEnum.AUTHENTICATION_REQUIRED -> NetworkStatus.AUTHENTICATION_REQUIRED
        NetworkStatusEnum.ACCESS_DENIED -> NetworkStatus.ACCESS_DENIED
        NetworkStatusEnum.NOT_FOUND -> NetworkStatus.NOT_FOUND
        NetworkStatusEnum.DISCONNECTED,
        NetworkStatusEnum.PORT_ERROR,
        NetworkStatusEnum.CLIENT_TOO_OLD,
        NetworkStatusEnum.UNKNOWN,
        -> NetworkStatus.NO_CONNECTION
        else -> NetworkStatus.REQUESTING_CONFIGURATION
    }
}

/**
 * 领域实体转换为 Join 参数。
 *
 * @return Join 参数。
 */
private fun NetworkEntity.toJoinParams(): io.github.jimmy.ztlink.model.network.JoinNetwork {
    val customDnsValue = if (dnsServers.isNotEmpty()) {
        dnsServers.joinToString(separator = "\n")
    } else {
        config.customDns
    }
    return io.github.jimmy.ztlink.model.network.JoinNetwork(
        networkId = networkId,
        routeViaZeroTier = config.routeViaZeroTier,
        dnsMode = config.dnsMode,
        customDns = customDnsValue,
        displayName = displayName,
    )
}

/**
 * 解析网络实体中的自定义 DNS 列表。
 *
 * 说明：
 * 1. 优先使用结构化列表 `dnsServers`；
 * 2. 若为空，则回退解析 `config.customDns` 文本；
 * 3. 结果会去掉空白项，避免把无效值下发到服务层。
 */
private fun NetworkEntity.resolveCustomDnsServers(): List<String> {
    if (dnsServers.isNotEmpty()) {
        return dnsServers
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    return config.customDns
        .split('\n', ',', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun io.github.jimmy.ztlink.app.ui.components.settings.SettingsUiState.toPlanetRouteType(): PlanetRouteType {
    return if (planetUseCustom) PlanetRouteType.NON_OFFICIAL else PlanetRouteType.OFFICIAL
}

private data class PeerSnapshot(
    val directCount: Int = 0,
    val relayCount: Int = 0,
    val rootServerIps: List<String> = emptyList(),
) {
    val primaryRootServerIp: String?
        get() = rootServerIps.firstOrNull()
}

private fun List<io.github.jimmy.ztlink.model.runtime.RuntimePeerInfo>.toPeerSnapshot(): PeerSnapshot {
    if (isEmpty()) {
        return PeerSnapshot()
    }
    val directCount = count { !it.address.isNullOrBlank() }
    val relayCount = size - directCount
    val rootServerIps = asSequence()
        .filter { it.role.contains("PLANET", ignoreCase = true) }
        .mapNotNull { it.address?.extractRootServerIp() }
        .distinct()
        .sorted()
        .toList()
    return PeerSnapshot(
        directCount = directCount,
        relayCount = relayCount,
        rootServerIps = rootServerIps,
    )
}

/**
 * 从 Peer 地址字符串提取 Root Server IP。
 *
 * 常见输入示例：
 * 1. "/52.80.168.91:9993"
 * 2. "host/52.80.168.91:9993"
 * 3. "/[2408:xxxx::1]:9993"
 */
private fun String.extractRootServerIp(): String? {
    val raw = trim()
    if (raw.isEmpty()) {
        return null
    }
    val endpoint = raw.substringAfterLast('/').removePrefix("/").trim()
    if (endpoint.isEmpty()) {
        return null
    }
    if (endpoint.startsWith("[") && endpoint.contains("]")) {
        return endpoint.substringAfter("[").substringBefore("]").trim().ifEmpty { null }
    }
    if (endpoint.count { it == ':' } == 1) {
        return endpoint.substringBefore(':').trim().ifEmpty { null }
    }
    return endpoint
}

private fun PeerSnapshot.toSummaryText(context: Context): String {
    if (directCount == 0 && relayCount == 0) {
        return ""
    }
    return when {
        directCount > 0 && relayCount > 0 -> {
            context.getString(
                R.string.network_p2p_summary_mixed,
                directCount,
                relayCount,
            )
        }

        directCount > 0 -> {
            context.getString(
                R.string.network_p2p_summary_direct_only,
                directCount,
            )
        }

        else -> {
            context.getString(
                R.string.network_p2p_summary_relay_only,
                relayCount,
            )
        }
    }
}

private fun NetworkEntity.isLanNetwork(): Boolean {
    return assignedIps.any { it.isPrivateOrUlaAddress() }
}

private fun String.isPrivateOrUlaAddress(): Boolean {
    val host = substringBefore('/').trim()
    if (host.isEmpty()) {
        return false
    }
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return when (address) {
        is Inet4Address -> {
            val b = address.address
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }

        is Inet6Address -> {
            val first = address.address[0].toInt() and 0xFF
            (first and 0xFE) == 0xFC
        }

        else -> false
    }
}
