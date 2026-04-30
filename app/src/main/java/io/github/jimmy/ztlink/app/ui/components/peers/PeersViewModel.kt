package io.github.jimmy.ztlink.app.ui.components.peers

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent
import io.github.jimmy.ztlink.model.runtime.RuntimePeerInfo
import io.github.jimmy.ztlink.model.service.ServiceEffectType
import io.github.jimmy.ztlink.model.service.ServiceStateType
import io.github.jimmy.ztlink.service.ServiceAction
import io.github.jimmy.ztlink.service.ServiceActionDispatcher
import io.github.jimmy.ztlink.service.ServiceStateStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PeersUiState(
    val peers: List<PeerListItem> = emptyList(),
    val summary: PeerSummary = PeerSummary(),
    val activeNetworkId: String? = null,
    val serviceStateType: ServiceStateType = ServiceStateType.STOPPED,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
)

sealed interface PeersUiEvent : CommonUiEvent {
    data class ShowToast(
        override val messageRes: Int,
    ) : PeersUiEvent, CommonUiEvent.ShowToast
}

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val actionDispatcher: ServiceActionDispatcher,
    private val serviceStateStore: ServiceStateStore,
) : ViewModel() {

    private val _uiState: MutableStateFlow<PeersUiState> = MutableStateFlow(PeersUiState())
    val uiState: StateFlow<PeersUiState> = _uiState.asStateFlow()

    private val _uiEvents: MutableSharedFlow<PeersUiEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<PeersUiEvent> = _uiEvents.asSharedFlow()

    private var lastQueriedNetworkId: String? = null
    private var queryInFlight: Boolean = false
    private var lastNonUserQueryAtMs: Long = 0L
    private var lastScreenStartAtMs: Long = 0L
    private var lastScreenStartNetworkId: String? = null
    /**
     * 页面前台补刷任务。
     *
     * 目的：
     * 首次连接后 peers 可能分批就绪，若只请求一次会出现“只看到 1 个 peer”的体验。
     * 这里在页面进入前台时做短时间补刷，尽量把快照收敛到稳定数量。
     */
    private var screenBootstrapRefreshJob: Job? = null

    init {
        observeServiceState()
        observeServiceEffects()
    }

    fun refreshPeers() {
        requestPeerSnapshot(
            reason = "ui_peers_manual_refresh",
            userInitiated = true,
        )
    }

    /**
     * 页面进入前台时触发。
     *
     * 说明：
     * 1) 立即请求一次快照；
     * 2) 再做两次短间隔补刷，覆盖连接初期 peers 尚未完全建立的窗口。
     */
    fun onScreenStarted() {
        if (!_uiState.value.serviceStateType.canQueryPeers()) {
            return
        }
        val activeNetworkId = _uiState.value.activeNetworkId ?: return
        val now = System.currentTimeMillis()
        val sameNetworkRapidRestart =
            lastScreenStartNetworkId == activeNetworkId &&
                (now - lastScreenStartAtMs) < SCREEN_START_DEBOUNCE_MS
        if (sameNetworkRapidRestart) {
            return
        }
        lastScreenStartNetworkId = activeNetworkId
        lastScreenStartAtMs = now

        screenBootstrapRefreshJob?.cancel()
        requestPeerSnapshot(
            reason = "ui_peers_screen_started",
            userInitiated = false,
        )

        // 只有列表为空或仅 1 个时才做补刷，避免每次前台恢复都固定触发 3 次查询。
        if (_uiState.value.peers.size > 1) {
            return
        }
        screenBootstrapRefreshJob = viewModelScope.launch {
            repeat(2) { index ->
                delay(1_500L)
                if (_uiState.value.activeNetworkId != activeNetworkId) {
                    return@launch
                }
                if (_uiState.value.peers.size > 1) {
                    return@launch
                }
                requestPeerSnapshot(
                    reason = "ui_peers_bootstrap_retry_${index + 1}",
                    userInitiated = false,
                )
            }
        }
    }

    /**
     * 页面离开前台时触发，取消补刷任务，避免无意义后台请求。
     */
    fun onScreenStopped() {
        screenBootstrapRefreshJob?.cancel()
        screenBootstrapRefreshJob = null
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            serviceStateStore.state.collectLatest { state ->
                val activeNetworkId = when (state.type) {
                    ServiceStateType.CONNECTED,
                    -> state.networkId?.value

                    ServiceStateType.STARTING,
                    ServiceStateType.CONNECTING,
                    ServiceStateType.MONITOR_ONLY,
                    ServiceStateType.STOPPED,
                    ServiceStateType.STOPPING,
                    ServiceStateType.ERROR,
                    -> null
                }

                if (activeNetworkId == null) {
                    screenBootstrapRefreshJob?.cancel()
                    screenBootstrapRefreshJob = null
                    lastQueriedNetworkId = null
                    queryInFlight = false
                    lastNonUserQueryAtMs = 0L
                    lastScreenStartNetworkId = null
                    lastScreenStartAtMs = 0L
                    _uiState.update { old ->
                        old.copy(
                            peers = emptyList(),
                            summary = PeerSummary(),
                            activeNetworkId = null,
                            serviceStateType = state.type,
                            isLoading = false,
                            isRefreshing = false,
                        )
                    }
                    return@collectLatest
                }

                _uiState.update { old ->
                    val networkChanged = old.activeNetworkId != activeNetworkId
                    old.copy(
                        peers = if (networkChanged) emptyList() else old.peers,
                        summary = if (networkChanged) PeerSummary() else old.summary,
                        activeNetworkId = activeNetworkId,
                        serviceStateType = state.type,
                        isLoading = if (networkChanged) true else old.isLoading,
                        isRefreshing = if (networkChanged) false else old.isRefreshing,
                    )
                }

                val shouldAutoRefresh =
                    state.type.canQueryPeers() &&
                        activeNetworkId != lastQueriedNetworkId
                if (shouldAutoRefresh) {
                    requestPeerSnapshot(
                        reason = "ui_peers_auto_refresh",
                        userInitiated = false,
                    )
                }
            }
        }
    }

    private fun observeServiceEffects() {
        viewModelScope.launch {
            serviceStateStore.effects.collectLatest { effect ->
                when (effect.type) {
                    ServiceEffectType.PEER_SNAPSHOT_UPDATED -> {
                        queryInFlight = false
                        val activeNetworkId = _uiState.value.activeNetworkId
                        if (activeNetworkId == null) {
                            return@collectLatest
                        }
                        val peerItems = effect.peers.orEmpty().toPeerListItems()
                        _uiState.update { old ->
                            old.copy(
                                peers = peerItems,
                                summary = peerItems.toPeerSummary(),
                                isLoading = false,
                                isRefreshing = false,
                            )
                        }
                    }

                    ServiceEffectType.ERROR_REPORTED -> {
                        queryInFlight = false
                        _uiState.update { old ->
                            old.copy(
                                isLoading = false,
                                isRefreshing = false,
                            )
                        }
                        emitToast(R.string.network_action_failed)
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun requestPeerSnapshot(
        reason: String,
        userInitiated: Boolean,
    ) {
        val activeNetworkId = _uiState.value.activeNetworkId
        if (activeNetworkId.isNullOrBlank()) {
            _uiState.update { old ->
                old.copy(
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            if (userInitiated) {
                emitToast(R.string.peers_refresh_requires_active_network)
            }
            return
        }
        if (!_uiState.value.serviceStateType.canQueryPeers()) {
            _uiState.update { old ->
                old.copy(
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            if (userInitiated) {
                emitToast(R.string.peers_refresh_requires_active_network)
            }
            return
        }
        if (queryInFlight) {
            return
        }
        val now = System.currentTimeMillis()
        if (!userInitiated && lastQueriedNetworkId == activeNetworkId) {
            val elapsed = now - lastNonUserQueryAtMs
            if (elapsed in 0 until NON_USER_QUERY_MIN_INTERVAL_MS) {
                return
            }
        }
        queryInFlight = true
        lastQueriedNetworkId = activeNetworkId
        if (!userInitiated) {
            lastNonUserQueryAtMs = now
        }
        _uiState.update { old ->
            old.copy(
                isLoading = old.peers.isEmpty(),
                isRefreshing = userInitiated,
            )
        }

        runCatching {
            actionDispatcher.dispatch(
                ServiceAction.QueryPeers(reason = reason),
            )
        }.onFailure {
            queryInFlight = false
            _uiState.update { old ->
                old.copy(
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            emitToast(R.string.network_action_failed)
        }
    }

    private fun emitToast(@StringRes messageRes: Int) {
        _uiEvents.tryEmit(PeersUiEvent.ShowToast(messageRes))
    }

    private companion object {
        private const val NON_USER_QUERY_MIN_INTERVAL_MS: Long = 1_200L
        private const val SCREEN_START_DEBOUNCE_MS: Long = 2_500L
    }
}

private fun ServiceStateType.canQueryPeers(): Boolean {
    return this == ServiceStateType.CONNECTED
}

private fun List<RuntimePeerInfo>.toPeerListItems(): List<PeerListItem> {
    // 排序策略与老项目阅读习惯保持一致：
    // 优先展示根节点（Planet/Moon），再展示 Leaf；同组内优先直连、低延迟。
    return asSequence()
        .map { it.toPeerListItem() }
        .sortedWith(
            compareBy<PeerListItem>(
                { it.roleType.sortOrder() },
                { it.pathType.sortOrder() },
                { it.latencyMs ?: Long.MAX_VALUE },
                { it.peerId.lowercase() },
            ),
        )
        .toList()
}

private fun RuntimePeerInfo.toPeerListItem(): PeerListItem {
    // address 来源于 RuntimeService 的“首选路径优先”结果。
    // 这里统一抽出 endpoint，便于 UI 直接展示与路径判定。
    val roleType = role.toPeerRoleType()
    val endpoint = address
        ?.trim()
        ?.substringAfterLast('/')
        ?.removePrefix("/")
        ?.trim()
        ?.ifEmpty { null }
    val pathType = if (endpoint == null) PeerPathType.RELAY else PeerPathType.DIRECT
    return PeerListItem(
        peerId = peerId,
        roleType = roleType,
        pathType = pathType,
        endpoint = endpoint,
        latencyMs = latencyMs,
        version = version,
    )
}

private fun List<PeerListItem>.toPeerSummary(): PeerSummary {
    if (isEmpty()) {
        return PeerSummary()
    }
    val rootIps = asSequence()
        .filter { it.roleType == PeerRoleType.PLANET }
        .mapNotNull { it.endpoint?.extractPeerHost() }
        .distinct()
        .sorted()
        .toList()
    return PeerSummary(
        totalCount = size,
        directCount = count { it.pathType == PeerPathType.DIRECT },
        relayCount = count { it.pathType == PeerPathType.RELAY },
        planetCount = count { it.roleType == PeerRoleType.PLANET },
        moonCount = count { it.roleType == PeerRoleType.MOON },
        leafCount = count { it.roleType == PeerRoleType.LEAF },
        rootServerIps = rootIps,
    )
}

private fun String.toPeerRoleType(): PeerRoleType {
    return when {
        contains("PLANET", ignoreCase = true) -> PeerRoleType.PLANET
        contains("MOON", ignoreCase = true) -> PeerRoleType.MOON
        contains("LEAF", ignoreCase = true) -> PeerRoleType.LEAF
        else -> PeerRoleType.UNKNOWN
    }
}

private fun PeerRoleType.sortOrder(): Int {
    return when (this) {
        PeerRoleType.PLANET -> 0
        PeerRoleType.MOON -> 1
        PeerRoleType.LEAF -> 2
        PeerRoleType.UNKNOWN -> 3
    }
}

private fun PeerPathType.sortOrder(): Int {
    return when (this) {
        PeerPathType.DIRECT -> 0
        PeerPathType.RELAY -> 1
    }
}

private fun String.extractPeerHost(): String? {
    // 兼容 IPv4/IPv6（含 []）以及 host:port 格式，提取主机部分用于根服务器聚合。
    val endpoint = trim()
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
