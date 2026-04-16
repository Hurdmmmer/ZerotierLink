package io.github.jimmy.ztlink.model.network

/**
 * ZeroTier 网络 ID 强类型封装。
 *
 * 约束：
 * - 必须是 16 位小写十六进制字符串。
 */
@JvmInline
value class NetworkId private constructor(
    /** 规范化后的网络 ID 字符串（16 位小写十六进制）。 */
    val value: String
) {

    companion object {
        /** 网络 ID 格式校验规则（16 位小写十六进制）。 */
        private val NETWORK_ID_REGEX = Regex("^[0-9a-f]{16}$")

        /**
         * 解析并规范化用户输入。
         */
        fun parse(raw: String): NetworkId? {
            val normalized = raw.trim().lowercase()
            if (!NETWORK_ID_REGEX.matches(normalized)) {
                return null
            }
            return NetworkId(normalized)
        }

        fun isValid(raw: String): Boolean = parse(raw) != null
    }
}
