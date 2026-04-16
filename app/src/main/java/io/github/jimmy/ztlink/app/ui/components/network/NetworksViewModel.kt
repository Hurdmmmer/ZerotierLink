package io.github.jimmy.ztlink.app.ui.components.network

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.data.network.NetworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent
import io.github.jimmy.ztlink.model.network.NetworkConfigEntity
import io.github.jimmy.ztlink.model.network.NetworkConnectionStatus
import io.github.jimmy.ztlink.model.network.NetworkDnsMode
import io.github.jimmy.ztlink.model.network.NetworkEntity
import io.github.jimmy.ztlink.model.network.NetworkId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 网络列表与加入网络页面共享的状态模型。
 *
 * @property networks 网络列表页展示数据。
 * @property details 网络详情映射（networkId -> detail）。
 * @property isLoading 当前是否正在进行异步加载。
 */
data class NetworksUiState(
    val networks: List<NetworkListItem> = emptyList(),
    val details: Map<String, NetworkDetail> = emptyMap(),
    val isLoading: Boolean = false,
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
     * 复制网络 ID 事件。
     */
    data class CopyNetworkId(
        val networkId: String,
        override val successMsgRes: Int = R.string.network_copy_success,
    ) : NetworksUiEvent, CommonUiEvent.CopyToClipboard {
        override val text: String get() = networkId
        override val label: String get() = "network-id"
    }
    
    /** 导航返回事件。 */
    data object NavigateBack : NetworksUiEvent
}

/**
 * 网络页面 ViewModel。
 *
 * 说明：
 * - 负责网络列表读取、加入网络落库、开关与删除等入库操作。
 * - 通过事件流向 UI 派发 Toast、复制、导航等一次性动作。
 */
@HiltViewModel
class NetworksViewModel @Inject constructor(
    /** 网络聚合仓库。 */
    private val networkRepository: NetworkRepository,
) : ViewModel() {

    /** 页面状态流。 */
    private val _uiState: MutableStateFlow<NetworksUiState> = MutableStateFlow(NetworksUiState())
    /** 页面状态流（只读）。 */
    val uiState: StateFlow<NetworksUiState> = _uiState.asStateFlow()

    /** 一次性事件流。 */
    private val _uiEvents: MutableSharedFlow<NetworksUiEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    /** 一次性事件流（只读）。 */
    val uiEvents: SharedFlow<NetworksUiEvent> = _uiEvents.asSharedFlow()

    init {
        loadNetworks()
    }

    /**
     * 加载网络列表并刷新状态。
     */
    fun loadNetworks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val entities = networkRepository.listAll()
            _uiState.value = NetworksUiState(
                networks = entities.map { it.toListItem() },
                details = entities.associate { it.networkId.value to it.toDetail() },
                isLoading = false,
            )
        }
    }

    /**
     * 执行加入网络入库操作。
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

            // Key logic:
            // 1) Persist join configuration first.
            // 2) Mark status as requesting configuration to align with authorization flow.
            // 3) Set the newly joined network as last activated.
            val normalizedDnsList = if (dnsMode == DnsMode.CUSTOM) {
                customDnsList.map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val customDnsValue = normalizedDnsList.firstOrNull().orEmpty()
            networkRepository.upsert(
                NetworkEntity(
                    networkId = parsedId,
                    displayName = parsedId.value,
                    isEnabled = true,
                    lastActivated = true,
                    config = NetworkConfigEntity(
                        routeViaZeroTier = defaultRoute,
                        dnsMode = dnsMode.toModelDnsMode(),
                        customDns = customDnsValue,
                    ),
                    status = NetworkConnectionStatus.REQUESTING_CONFIGURATION,
                    assignedIps = emptyList(),
                    dnsServers = normalizedDnsList,
                    mac = "",
                    mtu = null,
                    broadcastEnabled = false,
                    bridgingEnabled = false,
                ),
            )
            networkRepository.setLastActivated(parsedId)
            loadNetworks()
            _uiEvents.tryEmit(NetworksUiEvent.NavigateBack)
        }
    }

    /**
     * 更新网络启用状态。
     *
     * @param networkId 网络 ID 文本。
     * @param enabled 目标启用状态。
     */
    fun toggleNetwork(
        networkId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val parsedId = NetworkId.parse(networkId) ?: return@launch
            val current = networkRepository.findById(parsedId) ?: return@launch
            networkRepository.upsert(current.copy(isEnabled = enabled))
            loadNetworks()
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
            loadNetworks()
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
     * 派发 Toast 事件。
     *
     * @param messageRes 文案资源 ID。
     */
    private fun emitToast(
        @StringRes messageRes: Int,
    ) {
        _uiEvents.tryEmit(NetworksUiEvent.ShowToast(messageRes))
    }
}

/**
 * 将 UI DNS 模式映射为模型层 DNS 模式。
 *
 * @return 模型层 DNS 模式。
 */
private fun DnsMode.toModelDnsMode(): NetworkDnsMode {
    return when (this) {
        DnsMode.NONE -> NetworkDnsMode.NONE
        DnsMode.NETWORK -> NetworkDnsMode.NETWORK
        DnsMode.CUSTOM -> NetworkDnsMode.CUSTOM
    }
}

/**
 * 将模型层实体映射为列表页项。
 *
 * @return 列表页项。
 */
private fun NetworkEntity.toListItem(): NetworkListItem {
    return NetworkListItem(
        networkId = networkId.value,
        name = displayName.ifBlank { networkId.value },
        status = toUiStatus(),
        isEnabled = isEnabled,
        isLan = false,
        p2pSummary = "",
        assignedIps = assignedIps,
    )
}

/**
 * 将模型层实体映射为详情页数据。
 *
 * @return 详情页数据。
 */
private fun NetworkEntity.toDetail(): NetworkDetail {
    return NetworkDetail(
        networkId = networkId.value,
        name = displayName.ifBlank { networkId.value },
        status = toUiStatus(),
        type = "Private",
        mac = mac.ifBlank { "-" },
        mtu = mtu ?: 2800,
        broadcastEnabled = broadcastEnabled,
        bridgingEnabled = bridgingEnabled,
        assignedIps = assignedIps,
        dnsServers = dnsServers,
        dnsMode = when (config.dnsMode) {
            NetworkDnsMode.NONE -> DnsMode.NONE
            NetworkDnsMode.NETWORK -> NetworkDnsMode.NETWORK
            NetworkDnsMode.CUSTOM -> DnsMode.CUSTOM
        },
        defaultRoute = config.routeViaZeroTier,
        isLan = false,
    )
}

/**
 * 将模型层连接状态映射为 UI 状态。
 *
 * @return UI 状态。
 */
private fun NetworkEntity.toUiStatus(): NetworkStatus {
    return when (status) {
        NetworkConnectionStatus.OK -> NetworkStatus.CONNECTED
        NetworkConnectionStatus.REQUESTING_CONFIGURATION -> NetworkStatus.REQUESTING_CONFIGURATION
        NetworkConnectionStatus.AUTHENTICATION_REQUIRED -> NetworkStatus.AUTHENTICATION_REQUIRED
        NetworkConnectionStatus.ACCESS_DENIED -> NetworkStatus.ACCESS_DENIED
        NetworkConnectionStatus.NOT_FOUND -> NetworkStatus.NOT_FOUND
        NetworkConnectionStatus.DISCONNECTED,
        NetworkConnectionStatus.PORT_ERROR,
        NetworkConnectionStatus.CLIENT_TOO_OLD,
        NetworkConnectionStatus.UNKNOWN -> NetworkStatus.DISCONNECTED
    }
}
