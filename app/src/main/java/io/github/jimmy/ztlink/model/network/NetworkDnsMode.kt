package io.github.jimmy.ztlink.model.network

/**
 * 已加入网络使用的 DNS 模式。
 */
enum class NetworkDnsMode(
    /** 持久化使用的整型编码。 */
    val code: Int
) {
    /** 不使用 DNS。 */
    NONE(0),
    /** 使用网络下发的 DNS。 */
    NETWORK(1),
    /** 使用自定义 DNS。 */
    CUSTOM(2);

    companion object {
        fun fromCode(code: Int): NetworkDnsMode = entries.firstOrNull { it.code == code } ?: NONE
    }
}
