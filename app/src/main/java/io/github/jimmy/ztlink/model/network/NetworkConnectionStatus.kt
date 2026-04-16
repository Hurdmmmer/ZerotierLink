package io.github.jimmy.ztlink.model.network

/**
 * 运行时网络状态，语义与 ZeroTier 状态对齐。
 */
enum class NetworkConnectionStatus {
    /** 已发起入网，等待控制器下发配置。 */
    REQUESTING_CONFIGURATION,
    /** 入网成功且可正常转发。 */
    OK,
    /** 控制器拒绝当前节点加入该网络。 */
    ACCESS_DENIED,
    /** 网络不存在或 ID 无效。 */
    NOT_FOUND,
    /** 端口相关错误（通常为网络环境限制）。 */
    PORT_ERROR,
    /** 客户端版本过旧，无法被当前网络接受。 */
    CLIENT_TOO_OLD,
    /** 需要控制器审批授权。 */
    AUTHENTICATION_REQUIRED,
    /** 当前未连接该网络。 */
    DISCONNECTED,
    /** 未知状态或未收到状态回调。 */
    UNKNOWN;

    /**
     * 当前状态是否可视为“已连接”。
     */
    val isConnected: Boolean
        get() = this == OK

    /**
     * 当前状态是否处于“等待控制器审批授权”阶段。
     */
    val isAwaitingControllerAuthorization: Boolean
        get() = this == AUTHENTICATION_REQUIRED
}
