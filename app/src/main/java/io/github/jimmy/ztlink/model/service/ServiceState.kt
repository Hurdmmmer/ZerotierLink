package io.github.jimmy.ztlink.model.service

import io.github.jimmy.ztlink.model.network.NetworkId

/**
 * 服务生命周期状态类型。
 */
enum class ServiceStateType {
    /** 服务已停止 */
    STOPPED,
    /** 服务启动中 */
    STARTING,
    /** 正在连接网络 */
    CONNECTING,
    /** 已连接并处于活动状态 */
    CONNECTED,
    /** 仅监听模式（如已连接但被系统 VPN 接管或处于非活动状态） */
    MONITOR_ONLY,
    /** 服务停止中 */
    STOPPING,
    /** 发生错误 */
    ERROR,
}

/**
 * 统一的服务生命周期状态对象。
 *
 * 设计理念：
 * 1. 保持单一对象模型，用于事件/状态总线。
 * 2. 使用 `type` 配合特定可选字段来承载上下文数据。
 * 3. 在 `validateByType` 中强制执行形状约束，以避免非法组合。
 *
 * @property type 状态类型。
 * @property networkId 当前关联的网络 ID（可空）。
 * @property reason 状态转换的原因（如 "User request"）。
 * @property detail 额外详情（如连接日志）。
 * @property error 错误信息（仅在 ERROR 状态下有效）。
 * @property occurredAtMs 状态发生的时间戳。
 */
@ConsistentCopyVisibility
data class ServiceState private constructor(
    val type: ServiceStateType,
    val networkId: NetworkId? = null,
    val reason: String? = null,
    val detail: String? = null,
    val error: ServiceError? = null,
    val occurredAtMs: Long = 0L,
) {

    init {
        validateByType()
    }

    companion object {

        /** 创建停止状态 */
        fun stopped(): ServiceState = ServiceState(
            type = ServiceStateType.STOPPED,
            occurredAtMs = System.currentTimeMillis(),
        )

        /** 创建启动中状态 */
        fun starting(
            reason: String,
            targetNetworkId: NetworkId?,
        ): ServiceState = ServiceState(
            type = ServiceStateType.STARTING,
            reason = reason,
            networkId = targetNetworkId,
            occurredAtMs = System.currentTimeMillis(),
        )

        /** 创建连接中状态 */
        fun connecting(
            networkId: NetworkId,
            reason: String,
        ): ServiceState = ServiceState(
            type = ServiceStateType.CONNECTING,
            networkId = networkId,
            reason = reason,
            occurredAtMs = System.currentTimeMillis(),
        )

        /** 创建已连接状态 */
        fun connected(
            networkId: NetworkId,
            connectedAtMs: Long,
        ): ServiceState = ServiceState(
            type = ServiceStateType.CONNECTED,
            networkId = networkId,
            occurredAtMs = connectedAtMs,
        )

        /** 创建仅监听模式状态 */
        fun monitorOnly(
            networkId: NetworkId?,
            reason: String,
            detail: String,
            enteredAtMs: Long,
        ): ServiceState = ServiceState(
            type = ServiceStateType.MONITOR_ONLY,
            networkId = networkId,
            reason = reason,
            detail = detail,
            occurredAtMs = enteredAtMs,
        )

        /** 创建停止中状态 */
        fun stopping(
            reason: String,
        ): ServiceState = ServiceState(
            type = ServiceStateType.STOPPING,
            reason = reason,
            occurredAtMs = System.currentTimeMillis(),
        )

        /** 创建错误状态 */
        fun error(
            error: ServiceError,
        ): ServiceState = ServiceState(
            type = ServiceStateType.ERROR,
            error = error,
            occurredAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * 根据类型验证字段合法性。
     */
    private fun validateByType() {
        when (type) {
            ServiceStateType.STOPPED -> Unit
            ServiceStateType.STARTING -> require(!reason.isNullOrBlank()) {
                "STARTING 状态需要非空的原因 (reason)。"
            }

            ServiceStateType.CONNECTING -> {
                require(networkId != null) { "CONNECTING 状态需要 networkId。" }
                require(!reason.isNullOrBlank()) { "CONNECTING 状态需要非空的原因 (reason)。" }
            }

            ServiceStateType.CONNECTED -> require(networkId != null) {
                "CONNECTED 状态需要 networkId。"
            }

            ServiceStateType.MONITOR_ONLY -> {
                require(!reason.isNullOrBlank()) { "MONITOR_ONLY 状态需要非空的原因 (reason)。" }
                require(!detail.isNullOrBlank()) { "MONITOR_ONLY 状态需要非空的详情 (detail)。" }
            }

            ServiceStateType.STOPPING -> require(!reason.isNullOrBlank()) {
                "STOPPING 状态需要非空的原因 (reason)。"
            }

            ServiceStateType.ERROR -> require(error != null) {
                "ERROR 状态需要错误负载 (error)。"
            }
        }
    }
}
