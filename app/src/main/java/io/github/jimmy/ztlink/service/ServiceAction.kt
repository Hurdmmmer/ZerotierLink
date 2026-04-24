package io.github.jimmy.ztlink.service

import io.github.jimmy.ztlink.model.network.JoinNetwork
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.runtime.RuntimeMoonOrbit

/**
 * 服务动作模型。
 *
 * 说明：
 * 1. 供 ViewModel/Boot/通知等入口统一表达“要服务做什么”；
 * 2. 动作模型与 Android Intent 解耦，便于上层保持业务语义；
 * 3. 所有动作最终由 ZeroTierVpnService 串行执行。
 */
sealed interface ServiceAction {

    /** 触发原因。 */
    val reason: String

    /**
     * 启动或恢复服务目标网络。
     */
    data class StartOrResume(
        val targetNetworkId: NetworkId? = null,
        val hasExplicitNetworkId: Boolean = false,
        override val reason: String = "start_or_resume",
    ) : ServiceAction

    /**
     * 加入网络。
     */
    data class Join(
        val params: JoinNetwork,
        val hasExplicitNetworkId: Boolean = true,
        override val reason: String = "manual_join",
    ) : ServiceAction

    /**
     * 离开网络。
     */
    data class Leave(
        val networkId: NetworkId,
        override val reason: String = "manual_leave",
    ) : ServiceAction

    /**
     * 停止服务运行。
     */
    data class Stop(
        val keepServiceAlive: Boolean = false,
        override val reason: String = "manual_stop",
    ) : ServiceAction

    /**
     * 进入仅监控模式。
     */
    data class EnterMonitorOnly(
        val networkId: NetworkId? = null,
        val detail: String,
        override val reason: String,
    ) : ServiceAction

    /**
     * 退出仅监控模式并恢复中转。
     */
    data class ResumeRelay(
        override val reason: String = "manual_resume",
    ) : ServiceAction

    /**
     * 同步指定网络配置。
     */
    data class SyncNetworkConfig(
        val networkId: NetworkId,
        val configChanged: Boolean = false,
        override val reason: String = "sync_network_config",
    ) : ServiceAction

    /**
     * 重配置指定网络隧道。
     */
    data class ReconfigureTunnel(
        val networkId: NetworkId,
        val routeViaZeroTier: Boolean,
        val dnsMode: NetworkDnsModeEnum,
        val customDnsServers: List<String>,
        val whitelistPackages: List<String> = emptyList(),
        val includeBuiltInWhitelistPackages: Boolean = true,
        override val reason: String = "network_config_changed",
    ) : ServiceAction

    /**
     * Moon 入轨。
     */
    data class OrbitMoons(
        val moons: List<RuntimeMoonOrbit>,
        override val reason: String = "orbit_moons",
    ) : ServiceAction

    /**
     * Moon 退轨。
     */
    data class DeorbitMoons(
        val moonWorldIds: List<Long>,
        override val reason: String = "deorbit_moons",
    ) : ServiceAction

    /**
     * 查询 Peer 快照。
     */
    data class QueryPeers(
        override val reason: String = "query_peers",
    ) : ServiceAction

    /**
     * 查询节点信息。
     */
    data class QueryNode(
        override val reason: String = "query_node",
    ) : ServiceAction

    /**
     * 查询指定网络配置。
     */
    data class QueryNetworkConfig(
        val networkId: NetworkId,
        override val reason: String = "query_network_config",
    ) : ServiceAction
}
