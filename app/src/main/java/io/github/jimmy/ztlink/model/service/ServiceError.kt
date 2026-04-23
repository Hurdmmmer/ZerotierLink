package io.github.jimmy.ztlink.model.service

/**
 * 结构化服务错误。
 *
 * @property code 错误分类。
 * @property message 错误描述。
 * @property recoverable 是否可恢复。
 * @property causeType 原始异常类型名（可空）。
 */
data class ServiceError(
    val code: ServiceErrorCode,
    val message: String,
    val recoverable: Boolean,
    val causeType: String? = null,
)
