package io.github.jimmy.ztlink.model.service

/**
 * 服务或运行时错误的分类枚举。
 */
enum class ServiceErrorCode {
    /** 验证失败（如无效的网络 ID 格式） */
    VALIDATION_FAILED,
    /** 运行时启动失败（通常是 native 层初始化失败） */
    RUNTIME_START_FAILED,
    /** 加入网络失败 */
    JOIN_FAILED,
    /** 离开网络失败 */
    LEAVE_FAILED,
    /** 隧道重新配置失败（如 VPN 网卡配置异常） */
    ESTABLISH_VPN_TUNNEL_FAILED,
    /** 策略拒绝（如权限或安全限制） */
    POLICY_REJECTED,
    /** 权限被拒绝（如未授予 VPN 权限） */
    PERMISSION_DENIED,
    /** 内部逻辑错误 */
    INTERNAL_ERROR,
    /** 未知错误 */
    UNKNOWN,
}
