package io.github.jimmy.ztlink.data.settings

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ZeroTier `planet` 二进制文件解析器与 IPv4 端点就地重写器。
 *
 * 对齐桌面端 `Hurdmmmer/ZeroTierHome` 的 `PlanetDocument` 实现与 C++ `World::serialize` 规范：
 * - 完整校验 `TYPE_PLANET(0x01)`、`WorldId`、`Timestamp`、公钥、签名、Root 节点身份与稳定端点列表；
 * - 在不破坏 `WorldId`、`Timestamp`、椭圆曲线签名与端口的前提下，仅就地替换 4 字节 IPv4 地址。
 */
class PlanetDocument private constructor(
    private val rawBytes: ByteArray,
    private val ipv4TargetOffsets: IntArray,
    val worldId: Long,
    val timestamp: Long,
    val primaryIpv4: String,
    val primaryPort: Int,
) {
    /**
     * 获取当前二进制内容的拷贝。
     */
    fun toByteArray(): ByteArray = rawBytes.clone()

    /**
     * 计算当前 `planet` 二进制内容的 SHA-256 摘要（十六进制小写），用于检测内容变更。
     */
    fun sha256Hex(): String = sha256Hex(rawBytes)

    /**
     * 将目标 IPv4 端点全部替换为 [newIpv4]，保持原有端口、签名与时间戳不变。
     *
     * @param newIpv4 新的 IPv4 点分十进制字符串。
     * @return 替换并复核通过后的新 `planet` 字节数组。
     */
    fun withIpv4Endpoints(newIpv4: String): ByteArray {
        val addressBytes = parseIpv4Bytes(newIpv4)
            ?: throw IllegalArgumentException("Invalid IPv4 address: $newIpv4")

        val updated = rawBytes.clone()
        for (offset in ipv4TargetOffsets) {
            System.arraycopy(addressBytes, 0, updated, offset, IPV4_BYTE_LENGTH)
        }
        return parse(updated).toByteArray()
    }

    private data class Ipv4Endpoint(
        val address: String,
        val port: Int,
        val ipOffset: Int,
        val isPrivateOrReserved: Boolean,
    )

    companion object {
        private const val WORLD_TYPE_PLANET: Byte = 0x01
        private const val WORLD_MAX_ROOTS: Int = 4
        private const val WORLD_MAX_STABLE_ENDPOINTS_PER_ROOT: Int = 32
        private const val MIN_PLANET_BYTES: Int = 178
        private const val MAX_PLANET_BYTES: Int = 1024 * 1024
        private const val ADDRESS_LENGTH: Int = 5
        private const val IDENTITY_PUBLIC_KEY_BYTES: Int = 64
        private const val WORLD_SIGNATURE_BYTES: Int = 96
        private const val IDENTITY_BINARY_LENGTH: Int = ADDRESS_LENGTH + 1 + IDENTITY_PUBLIC_KEY_BYTES + 1
        private const val INET_ADDR_TYPE_NULL: Int = 0
        private const val INET_ADDR_TYPE_IPV4: Int = 4
        private const val INET_ADDR_TYPE_IPV6: Int = 6
        private const val IPV4_BYTE_LENGTH: Int = 4
        private const val PORT_BYTE_LENGTH: Int = 2
        private const val IPV6_ENDPOINT_REMAINING_BYTES: Int = 16 + PORT_BYTE_LENGTH

        /**
         * 解析并校验 `planet` 二进制数据。
         *
         * @throws IllegalArgumentException 当二进制结构不符合 ZeroTier `World` 规范时抛出。
         */
        fun parse(bytes: ByteArray): PlanetDocument {
            require(bytes.size in MIN_PLANET_BYTES..MAX_PLANET_BYTES) {
                "Invalid planet size: ${bytes.size}"
            }
            require(bytes[0] == WORLD_TYPE_PLANET) {
                "Invalid world type: ${bytes[0]}"
            }

            var offset = 1
            val worldId = readUint64BigEndian(bytes, offset)
            offset += 8

            val timestamp = readUint64BigEndian(bytes, offset)
            offset += 8

            require(worldId != 0L && timestamp != 0L) {
                "Invalid worldId or timestamp in planet"
            }

            // _updatesMustBeSignedBy (64 bytes) + _signature (96 bytes)
            offset += IDENTITY_PUBLIC_KEY_BYTES + WORLD_SIGNATURE_BYTES

            val rootCount = bytes[offset].toInt() and 0xFF
            offset += 1
            require(rootCount in 1..WORLD_MAX_ROOTS) {
                "Invalid root count: $rootCount"
            }

            val ipv4Endpoints = ArrayList<Ipv4Endpoint>()
            for (rootIndex in 0 until rootCount) {
                skipRootIdentity(bytes, offset)
                offset += IDENTITY_BINARY_LENGTH

                require(offset < bytes.size) {
                    "Unexpected EOF reading endpoint count for root $rootIndex"
                }
                val endpointCount = bytes[offset].toInt() and 0xFF
                offset += 1
                require(endpointCount <= WORLD_MAX_STABLE_ENDPOINTS_PER_ROOT) {
                    "Too many endpoints ($endpointCount) in root $rootIndex"
                }

                for (endpointIndex in 0 until endpointCount) {
                    require(offset < bytes.size) {
                        "Unexpected EOF reading endpoint type"
                    }
                    when (val addressType = bytes[offset].toInt() and 0xFF) {
                        INET_ADDR_TYPE_NULL -> {
                            offset += 1
                        }

                        INET_ADDR_TYPE_IPV4 -> {
                            require(offset + 1 + IPV4_BYTE_LENGTH + PORT_BYTE_LENGTH <= bytes.size) {
                                "Unexpected EOF reading IPv4 endpoint"
                            }
                            val ipOffset = offset + 1
                            val ipBytes = bytes.copyOfRange(ipOffset, ipOffset + IPV4_BYTE_LENGTH)
                            val port = readUint16BigEndian(bytes, ipOffset + IPV4_BYTE_LENGTH)
                            require(port > 0) {
                                "Invalid IPv4 port: 0"
                            }
                            val ipStr = formatIpv4Bytes(ipBytes)
                            ipv4Endpoints.add(
                                Ipv4Endpoint(
                                    address = ipStr,
                                    port = port,
                                    ipOffset = ipOffset,
                                    isPrivateOrReserved = isPrivateOrReservedIpv4(ipBytes),
                                ),
                            )
                            offset += 1 + IPV4_BYTE_LENGTH + PORT_BYTE_LENGTH
                        }

                        INET_ADDR_TYPE_IPV6 -> {
                            require(offset + 1 + IPV6_ENDPOINT_REMAINING_BYTES <= bytes.size) {
                                "Unexpected EOF reading IPv6 endpoint"
                            }
                            val port = readUint16BigEndian(bytes, offset + 1 + 16)
                            require(port > 0) {
                                "Invalid IPv6 port: 0"
                            }
                            offset += 1 + IPV6_ENDPOINT_REMAINING_BYTES
                        }

                        else -> throw IllegalArgumentException("Unsupported InetAddress type: $addressType")
                    }
                }
            }

            require(offset == bytes.size) {
                "Trailing bytes in planet file: parsed=$offset total=${bytes.size}"
            }

            val preferredTargets = ipv4Endpoints
                .filter { !it.isPrivateOrReserved }
                .ifEmpty { ipv4Endpoints }

            require(preferredTargets.isNotEmpty()) {
                "Planet file contains no IPv4 endpoints"
            }

            val primary = preferredTargets.first()
            return PlanetDocument(
                rawBytes = bytes.clone(),
                ipv4TargetOffsets = preferredTargets.map { it.ipOffset }.toIntArray(),
                worldId = worldId,
                timestamp = timestamp,
                primaryIpv4 = primary.address,
                primaryPort = primary.port,
            )
        }

        /**
         * 判断字节数组是否为合法的 `planet` 文件。
         */
        fun isValid(bytes: ByteArray): Boolean = runCatching { parse(bytes) }.isSuccess

        /**
         * 计算任意字节数组的 SHA-256 摘要。
         */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(Character.forDigit(v ushr 4, 16))
                sb.append(Character.forDigit(v and 0x0F, 16))
            }
            return sb.toString()
        }

        /**
         * 将点分十进制 IPv4 解析为 4 字节数组；格式非法时返回 `null`。
         */
        fun parseIpv4Bytes(raw: String): ByteArray? {
            val parts = raw.trim().split('.')
            if (parts.size != IPV4_BYTE_LENGTH) {
                return null
            }
            val result = ByteArray(IPV4_BYTE_LENGTH)
            for (i in 0 until IPV4_BYTE_LENGTH) {
                val part = parts[i]
                if (part.isEmpty() || part.length > 3) {
                    return null
                }
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255) {
                    return null
                }
                result[i] = value.toByte()
            }
            return result
        }

        /**
         * 判断 4 字节 IPv4 是否属于私网、回环、链路本地、文档保留或 Fake-IP 地址段。
         */
        fun isPrivateOrReservedIpv4(bytes: ByteArray): Boolean {
            if (bytes.size != IPV4_BYTE_LENGTH) return true
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            return b0 == 0 ||
                b0 == 10 ||
                b0 == 127 ||
                (b0 == 100 && b1 in 64..127) ||
                (b0 == 169 && b1 == 254) ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 0 && b2 == 0) ||
                (b0 == 192 && b1 == 0 && b2 == 2) ||
                (b0 == 192 && b1 == 168) ||
                (b0 == 198 && (b1 == 18 || b1 == 19)) ||
                (b0 == 198 && b1 == 51 && b2 == 100) ||
                (b0 == 203 && b1 == 0 && b2 == 113) ||
                b0 >= 224
        }

        private fun skipRootIdentity(bytes: ByteArray, offset: Int) {
            require(offset + IDENTITY_BINARY_LENGTH <= bytes.size) {
                "Unexpected EOF reading root identity"
            }
            val identityType = bytes[offset + ADDRESS_LENGTH].toInt() and 0xFF
            require(identityType == 0) {
                "Unsupported identity type: $identityType"
            }
            val privateKeyLength =
                bytes[offset + ADDRESS_LENGTH + 1 + IDENTITY_PUBLIC_KEY_BYTES].toInt() and 0xFF
            require(privateKeyLength == 0) {
                "Root identity must not contain private key ($privateKeyLength)"
            }
        }

        private fun readUint64BigEndian(bytes: ByteArray, offset: Int): Long {
            return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).long
        }

        private fun readUint16BigEndian(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
        }

        private fun formatIpv4Bytes(bytes: ByteArray): String {
            return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
                "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
        }
    }
}
