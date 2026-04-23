package io.github.jimmy.ztlink.model.service

import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.runtime.RuntimeNodeInfo
import io.github.jimmy.ztlink.model.runtime.RuntimePeerInfo

/**
 * 服务一次性副作用（Effect）类型。
 */
enum class ServiceEffectType {
    /** 运行时已恢复（通常指后台服务重启后重新建立状态） */
    RUNTIME_RECOVERED,
    /** 加入网络成功 */
    JOIN_SUCCESS,
    /** 加入网络失败 */
    JOIN_FAILED,
    /** 离开网络完成 */
    LEAVE_DONE,
    /** 已进入仅监听模式 */
    MONITOR_ONLY_ENTERED,
    /** 已退出仅监听模式 */
    MONITOR_ONLY_EXITED,
    /** 网络配置已变更 */
    NETWORK_CONFIG_CHANGED,
    /** 节点快照已更新 */
    PEER_SNAPSHOT_UPDATED,
    /** 节点信息已更新 */
    NODE_INFO_UPDATED,
    /** 内网检查状态已更新 */
    INTRANET_CHECK_STATE_UPDATED,
    /** 报告了错误 */
    ERROR_REPORTED,
}

/**
 * 统一的服务一次性副作用对象。
 *
 * 此设计将所有事件负载保持在一个模型中，同时使用 `type` 将消费者路由到正确的字段集。
 *
 * @property type 副作用类型。
 * @property networkId 关联的网络 ID。
 * @property activeNetworkId 当前活动的（连接中/已连接）网络 ID。
 * @property reason 触发原因。
 * @property detail 详细信息。
 * @property error 错误对象。
 * @property noNetworksLeft 是否没有剩余网络（在离开网络后使用）。
 * @property changed 是否发生了变化。
 * @property peerCount 当前对等节点数量。
 * @property peers 当前对等节点快照。
 * @property nodeInfo 节点信息快照。
 * @property enabled 功能是否启用。
 * @property inIntranet 是否处于内网。
 */
@ConsistentCopyVisibility
data class ServiceEffect private constructor(
    val type: ServiceEffectType,
    val networkId: NetworkId? = null,
    val activeNetworkId: NetworkId? = null,
    val reason: String? = null,
    val detail: String? = null,
    val error: ServiceError? = null,
    val noNetworksLeft: Boolean? = null,
    val changed: Boolean? = null,
    val peerCount: Int? = null,
    val peers: List<RuntimePeerInfo>? = null,
    val nodeInfo: RuntimeNodeInfo? = null,
    val enabled: Boolean? = null,
    val inIntranet: Boolean? = null,
) {

    init {
        validateByType()
    }

    companion object {

        /** 创建运行时恢复副作用 */
        fun runtimeRecovered(
            activeNetworkId: NetworkId?,
            reason: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.RUNTIME_RECOVERED,
            activeNetworkId = activeNetworkId,
            reason = reason,
        )

        /** 创建加入成功副作用 */
        fun joinSuccess(
            networkId: NetworkId,
            reason: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.JOIN_SUCCESS,
            networkId = networkId,
            reason = reason,
        )

        /** 创建加入失败副作用 */
        fun joinFailed(
            networkId: NetworkId,
            reason: String,
            error: ServiceError,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.JOIN_FAILED,
            networkId = networkId,
            reason = reason,
            error = error,
        )

        /** 创建离开完成副作用 */
        fun leaveDone(
            networkId: NetworkId,
            noNetworksLeft: Boolean,
            reason: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.LEAVE_DONE,
            networkId = networkId,
            noNetworksLeft = noNetworksLeft,
            reason = reason,
        )

        /** 创建进入仅监听模式副作用 */
        fun monitorOnlyEntered(
            networkId: NetworkId?,
            reason: String,
            detail: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.MONITOR_ONLY_ENTERED,
            networkId = networkId,
            reason = reason,
            detail = detail,
        )

        /** 创建退出仅监听模式副作用 */
        fun monitorOnlyExited(
            networkId: NetworkId?,
            reason: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.MONITOR_ONLY_EXITED,
            networkId = networkId,
            reason = reason,
        )

        /** 创建网络配置变更副作用 */
        fun networkConfigChanged(
            networkId: NetworkId,
            changed: Boolean,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.NETWORK_CONFIG_CHANGED,
            networkId = networkId,
            changed = changed,
        )

        /** 创建节点快照更新副作用 */
        fun peerSnapshotUpdated(
            peerCount: Int,
            peers: List<RuntimePeerInfo>,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.PEER_SNAPSHOT_UPDATED,
            peerCount = peerCount,
            peers = peers,
        )

        /** 创建节点信息更新副作用 */
        fun nodeInfoUpdated(
            nodeInfo: RuntimeNodeInfo?,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.NODE_INFO_UPDATED,
            nodeInfo = nodeInfo,
        )

        /** 创建内网检查状态更新副作用 */
        fun intranetCheckStateUpdated(
            enabled: Boolean,
            inIntranet: Boolean?,
            reason: String,
            detail: String,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.INTRANET_CHECK_STATE_UPDATED,
            enabled = enabled,
            inIntranet = inIntranet,
            reason = reason,
            detail = detail,
        )

        /** 创建错误报告副作用 */
        fun errorReported(
            error: ServiceError,
        ): ServiceEffect = ServiceEffect(
            type = ServiceEffectType.ERROR_REPORTED,
            error = error,
        )
    }

    /**
     * 根据类型验证字段合法性。
     */
    private fun validateByType() {
        when (type) {
            ServiceEffectType.RUNTIME_RECOVERED -> require(!reason.isNullOrBlank()) {
                "RUNTIME_RECOVERED 非空的原因 (reason)。"
            }

            ServiceEffectType.JOIN_SUCCESS -> {
                require(networkId != null) { "JOIN_SUCCESS  networkId 不可为空。" }
                require(!reason.isNullOrBlank()) { "JOIN_SUCCESS 非空的原因 (reason)。" }
            }

            ServiceEffectType.JOIN_FAILED -> {
                require(networkId != null) { "JOIN_FAILED  networkId 不可为空。" }
                require(!reason.isNullOrBlank()) { "JOIN_FAILED 非空的原因 (reason)。" }
                require(error != null) { "JOIN_FAILED 错误负载 (error)。" }
            }

            ServiceEffectType.LEAVE_DONE -> {
                require(networkId != null) { "LEAVE_DONE  networkId 不可为空。" }
                require(noNetworksLeft != null) { "LEAVE_DONE  noNetworksLeft 标志。" }
                require(!reason.isNullOrBlank()) { "LEAVE_DONE 非空的原因 (reason)。" }
            }

            ServiceEffectType.MONITOR_ONLY_ENTERED -> {
                require(!reason.isNullOrBlank()) { "MONITOR_ONLY_ENTERED 非空的原因 (reason)。" }
                require(!detail.isNullOrBlank()) { "MONITOR_ONLY_ENTERED 非空的详情 (detail)。" }
            }

            ServiceEffectType.MONITOR_ONLY_EXITED -> require(!reason.isNullOrBlank()) {
                "MONITOR_ONLY_EXITED 非空的原因 (reason)。"
            }

            ServiceEffectType.NETWORK_CONFIG_CHANGED -> {
                require(networkId != null) { "NETWORK_CONFIG_CHANGED  networkId 不可为空。" }
                require(changed != null) { "NETWORK_CONFIG_CHANGED  changed 标志。" }
            }

            ServiceEffectType.PEER_SNAPSHOT_UPDATED -> {
                require(peerCount != null) { "PEER_SNAPSHOT_UPDATED  peerCount。" }
                require(peers != null) { "PEER_SNAPSHOT_UPDATED  peers 快照。" }
            }

            ServiceEffectType.NODE_INFO_UPDATED -> Unit

            ServiceEffectType.INTRANET_CHECK_STATE_UPDATED -> {
                require(enabled != null) { "INTRANET_CHECK_STATE_UPDATED  enabled 标志。" }
                require(!reason.isNullOrBlank()) { "INTRANET_CHECK_STATE_UPDATED 非空的原因 (reason)。" }
                require(!detail.isNullOrBlank()) { "INTRANET_CHECK_STATE_UPDATED 非空的详情 (detail)。" }
            }

            ServiceEffectType.ERROR_REPORTED -> require(error != null) {
                "ERROR_REPORTED 错误负载 (error)。"
            }
        }
    }
}
