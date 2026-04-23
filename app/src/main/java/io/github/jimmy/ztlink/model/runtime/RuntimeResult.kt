package io.github.jimmy.ztlink.model.runtime

import io.github.jimmy.ztlink.model.network.NetworkId

/**
 * Runtime 操作结果码。
 * 定义了底层 ZeroTier 内核及隧道操作的所有可能状态。
 */
enum class RuntimeResultCode {
    /** 操作成功 */
    SUCCESS,
    /** 节点已经处于运行状态，无需重复启动 */
    ALREADY_RUNNING,
    /** 运行时未启动，无法执行相关操作 */
    NOT_RUNNING,
    /** 传入的参数（如 NetworkId 格式）无效 */
    INVALID_ARGUMENT,
    /** 权限不足（如 Android 系统未授予 VPN 权限） */
    PERMISSION_DENIED,
    /** 在底层内核中未找到指定的虚拟网络配置 */
    NETWORK_NOT_FOUND,
    /** 访问受限（如 ZeroTier 控制台未授权该节点加入网络） */
    ACCESS_DENIED,
    /** 需要进行身份验证（用于某些私有控制器的特殊校验） */
    AUTH_REQUIRED,
    /** 运行时内部逻辑错误（如 JNI 调用失败、内存溢出等） */
    INTERNAL_ERROR,
    /** 无法归类的未知错误 */
    UNKNOWN_ERROR,
}

/**
 * Runtime 操作类型。
 * 用于标识当前结果对象属于哪种业务路径。
 */
enum class RuntimeResultType {
    /** 启动运行时 */
    START,
    /** 停止运行时 */
    STOP,
    /** 离开虚拟网络 */
    LEAVE,
    /** 建立或重配置 VPN 隧道 */
    ESTABLISH_VPN_TUNNEL,
    /** 通用操作（如入轨 Moon、查询状态等） */
    OPERATION,
}

/**
 * 统一的 Runtime 操作结果包装类。
 *
 * 这是 Runtime 管线（RuntimeService）对外返回的唯一模型。
 * 采用“联合体”设计：通过 [type] 区分业务，不同业务会携带不同的数据字段。
 *
 * @property type 操作类型，决定了哪些可选字段是有意义的。
 * @property resultCode 结果状态码，用于判断是否成功及错误原因。
 * @property message 附带的详细描述或错误信息，用于日志记录。
 */
@ConsistentCopyVisibility
data class RuntimeResult private constructor(
    val type: RuntimeResultType,
    val resultCode: RuntimeResultCode,
    val message: String = "",

    // --- 以下为特定业务的数据字段 ---

    /** [START] 路径：当前激活的网络 ID。 */
    val activeNetworkId: NetworkId? = null,
    /** [START] 路径：ZeroTier 节点内核是否初始化成功。 */
    val nodeReady: Boolean? = null,
    /** [START] 路径：VPN 隧道接口是否已经准备就绪。 */
    val tunnelReady: Boolean? = null,
    /** [STOP] 路径：节点内核是否已彻底关闭。 */
    val nodeClosed: Boolean? = null,
    /** [STOP] 路径：VPN 隧道是否已彻底销毁。 */
    val tunnelClosed: Boolean? = null,
    /** [LEAVE] 路径：执行离开操作后，当前是否已无任何已加入的网络。 */
    val noNetworksLeft: Boolean? = null,
    /** [TUNNEL_RECONFIGURE] 路径：本次隧道配置实际接管的路由条数。 */
    val managedRouteCount: Int? = null,
    /**
     * [TUNNEL_RECONFIGURE] 路径：隧道失败后是否应保持 CONNECTING 并等待重试。
     *
     * 说明：
     * - true 代表“暂不可用但可重试”（例如系统侧短暂拒绝、授权尚未完成）；
     * - false 代表“应进入失败终态”。
     */
    val keepConnectingOnTunnelFailure: Boolean? = null,
) {

    init {
        // 确保结果对象符合业务约束，防止出现字段缺失的无效结果
        validateByType()
    }

    companion object {
        /**
         * 构造启动操作的结果。
         */
        fun start(
            activeNetworkId: NetworkId?,
            nodeReady: Boolean,
            tunnelReady: Boolean,
            resultCode: RuntimeResultCode,
            message: String = "",
        ): RuntimeResult = RuntimeResult(
            type = RuntimeResultType.START,
            resultCode = resultCode,
            message = message,
            activeNetworkId = activeNetworkId,
            nodeReady = nodeReady,
            tunnelReady = tunnelReady,
        )

        /**
         * 构造停止操作的结果。
         */
        fun stop(
            nodeClosed: Boolean,
            tunnelClosed: Boolean,
            resultCode: RuntimeResultCode,
            message: String = "",
        ): RuntimeResult = RuntimeResult(
            type = RuntimeResultType.STOP,
            resultCode = resultCode,
            message = message,
            nodeClosed = nodeClosed,
            tunnelClosed = tunnelClosed,
        )

        /**
         * 构造离开网络操作的结果。
         */
        fun leave(
            noNetworksLeft: Boolean,
            resultCode: RuntimeResultCode,
            message: String = "",
        ): RuntimeResult = RuntimeResult(
            type = RuntimeResultType.LEAVE,
            resultCode = resultCode,
            message = message,
            noNetworksLeft = noNetworksLeft,
        )

        /**
         * 构造建立/重配置 VPN 隧道的结果。
         */
        fun establishVpnTunnel(
            managedRouteCount: Int,
            resultCode: RuntimeResultCode,
            message: String = "",
            keepConnectingOnTunnelFailure: Boolean = false,
        ): RuntimeResult = RuntimeResult(
            type = RuntimeResultType.ESTABLISH_VPN_TUNNEL,
            resultCode = resultCode,
            message = message,
            managedRouteCount = managedRouteCount,
            keepConnectingOnTunnelFailure = keepConnectingOnTunnelFailure,
        )

        /**
         * 构造通用业务操作的结果。
         */
        fun operation(
            resultCode: RuntimeResultCode,
            message: String = "",
        ): RuntimeResult = RuntimeResult(
            type = RuntimeResultType.OPERATION,
            resultCode = resultCode,
            message = message,
        )
    }

    /**
     * 便捷属性：判断操作是否属于逻辑上的成功状态。
     */
    val isSuccess: Boolean
        get() = resultCode == RuntimeResultCode.SUCCESS

    /**
     * 根据业务类型校验必要的字段。
     * 若开发过程中漏传了关键字段，此方法会抛出异常以便尽早发现。
     */
    private fun validateByType() {
        when (type) {
            RuntimeResultType.START -> {
                require(nodeReady != null) { "START 结果必须包含 nodeReady 状态。" }
                require(tunnelReady != null) { "START 结果必须包含 tunnelReady 状态。" }
            }

            RuntimeResultType.STOP -> {
                require(nodeClosed != null) { "STOP 结果必须包含 nodeClosed 状态。" }
                require(tunnelClosed != null) { "STOP 结果必须包含 tunnelClosed 状态。" }
            }

            RuntimeResultType.LEAVE -> {
                require(noNetworksLeft != null) { "LEAVE 结果必须携带 noNetworksLeft 标记。" }
            }

            RuntimeResultType.ESTABLISH_VPN_TUNNEL -> {
                require(managedRouteCount != null) {
                    "TUNNEL_RECONFIGURE 结果必须包含 managedRouteCount。"
                }
                require(managedRouteCount >= 0) {
                    "managedRouteCount 不能为负数。"
                }
                require(keepConnectingOnTunnelFailure != null) {
                    "TUNNEL_RECONFIGURE 结果必须包含 keepConnectingOnTunnelFailure。"
                }
            }

            RuntimeResultType.OPERATION -> Unit
        }
    }
}

