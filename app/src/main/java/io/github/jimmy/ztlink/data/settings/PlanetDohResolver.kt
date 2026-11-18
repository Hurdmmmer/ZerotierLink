package io.github.jimmy.ztlink.data.settings

import android.net.Network
import android.util.Base64
import io.github.jimmy.ztlink.service.observer.NetworkChangeObserver
import io.github.jimmy.ztlink.util.ChainLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.Inet4Address
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Planet 动态域名 DoH（DNS over HTTPS, RFC 8484 Wire 格式）解析器。
 *
 * 对齐 `Hurdmmmer/ZeroTierHome` 的 `PlanetResolver.cs`：
 * 1. 绕过本机可能被 VPN / 代理污染的系统 DNS，直接向直连 IP 的公网 DoH 服务器发起查询；
 * 2. 优先绑定到 `NetworkChangeObserver` 识别到的真实底层非 VPN 网络（Wi-Fi / 蜂窝）；
 * 3. 自动过滤私网、保留段及 Fake-IP（`198.18.0.0/15`）。
 */
@Singleton
class PlanetDohResolver @Inject constructor(
    private val networkChangeObserver: NetworkChangeObserver,
) {
    /**
     * 解析输入的域名或 IPv4 字面量，返回首个合法的公网 IPv4 地址。
     *
     * @param rawHost 用户配置的域名或 IPv4（允许带端口或协议前缀）。
     * @param perServerTimeoutMs 单个 DoH 节点超时时间（毫秒）。
     * @return 解析成功的公网 IPv4 字符串；失败返回 `null`。
     */
    fun resolveIpv4(
        rawHost: String,
        perServerTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): String? {
        val normalizedHost = extractHost(rawHost) ?: return null

        // 若直接输入了合法 IPv4，校验非保留段后直接返回
        PlanetDocument.parseIpv4Bytes(normalizedHost)?.let { ipBytes ->
            return if (!PlanetDocument.isPrivateOrReservedIpv4(ipBytes)) {
                normalizedHost
            } else {
                ChainLog.w(TAG, "拒绝私网或保留 IPv4 字面量 host=$normalizedHost")
                null
            }
        }

        val asciiHost = runCatching {
            IDN.toASCII(normalizedHost.trimEnd('.'))
        }.getOrElse {
            return null
        }.lowercase()

        if (asciiHost.isBlank()) {
            return null
        }

        val underlyingNetwork = runCatching {
            networkChangeObserver.currentUnderlyingNetwork()
        }.getOrNull()

        val timeoutMs = perServerTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        for (endpoint in DOH_ENDPOINTS) {
            val resolved = runCatching {
                queryWireEndpoint(
                    endpoint = endpoint,
                    asciiHost = asciiHost,
                    underlyingNetwork = underlyingNetwork,
                    timeoutMs = timeoutMs,
                )
            }.onFailure { error ->
                ChainLog.w(TAG, "DoH 节点查询失败 endpoint=$endpoint host=$asciiHost err=${error.message}")
            }.getOrNull()

            if (!resolved.isNullOrBlank()) {
                ChainLog.i(TAG, "DoH 解析成功 host=$asciiHost ip=$resolved via=$endpoint")
                return resolved
            }
        }

        ChainLog.w(TAG, "DoH 全部节点解析失败 host=$asciiHost")
        return resolveViaUnderlyingNetwork(asciiHost, underlyingNetwork)
    }

    private fun resolveViaUnderlyingNetwork(
        asciiHost: String,
        underlyingNetwork: Network?,
    ): String? {
        if (underlyingNetwork == null) {
            ChainLog.w(TAG, "物理网络原生 DNS 兜底跳过：无可用非 VPN 底层网络 host=$asciiHost")
            return null
        }
        return runCatching {
            val addresses = underlyingNetwork.getAllByName(asciiHost)
            for (addr in addresses) {
                if (addr is Inet4Address) {
                    val ipBytes = addr.address
                    if (!PlanetDocument.isPrivateOrReservedIpv4(ipBytes)) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) {
                            ChainLog.i(TAG, "物理网络原生 DNS 兜底解析成功 host=$asciiHost ip=$ip")
                            return@runCatching ip
                        }
                    }
                }
            }
            null
        }.onFailure { error ->
            ChainLog.w(TAG, "物理网络原生 DNS 兜底解析失败 host=$asciiHost err=${error.message}")
        }.getOrNull()
    }

    private fun queryWireEndpoint(
        endpoint: String,
        asciiHost: String,
        underlyingNetwork: Network?,
        timeoutMs: Int,
    ): String? {
        // RFC 8484 Section 4.1 推荐 DoH 请求固定使用 DNS ID 0，由 HTTPS 承担请求关联并提高缓存命中。
        val packet = buildDnsQueryPacket(asciiHost, queryId = 0)
        val dnsParam = Base64.encodeToString(
            packet,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val url = URL("$endpoint?dns=$dnsParam")
        val connection = openHttpConnection(url, underlyingNetwork).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            doInput = true
            setRequestProperty("Accept", "application/dns-message")
        }

        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return null
            }
            val payload = connection.inputStream.use { it.readBytes() }
            parseDnsResponsePacket(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(
        url: URL,
        underlyingNetwork: Network?,
    ): HttpURLConnection {
        if (underlyingNetwork != null) {
            runCatching {
                return underlyingNetwork.openConnection(url) as HttpURLConnection
            }
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun buildDnsQueryPacket(
        asciiHost: String,
        queryId: Int,
    ): ByteArray {
        val labels = asciiHost.split('.')
        require(labels.isNotEmpty()) { "Empty DNS host" }

        val out = ByteArrayOutputStream(64)
        // Header (12 bytes)
        out.write((queryId ushr 8) and 0xFF)
        out.write(queryId and 0xFF)
        out.write(0x01) // RD = 1
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0

        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid DNS label: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root terminator
        out.write(0x00)
        out.write(DNS_TYPE_A) // QTYPE = A (1)
        out.write(0x00)
        out.write(DNS_CLASS_IN) // QCLASS = IN (1)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "PlanetDohResolver"
        private const val DNS_TYPE_A = 1
        private const val DNS_CLASS_IN = 1
        private const val MIN_TIMEOUT_MS = 500
        private const val MAX_TIMEOUT_MS = 8_000
        const val DEFAULT_TIMEOUT_MS = 2_500

        private val DOH_ENDPOINTS = arrayOf(
            "https://223.5.5.5/dns-query",
            "https://223.6.6.6/dns-query",
            "https://1.12.12.12/dns-query",
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
        )

        internal fun parseDnsResponsePacket(
            packet: ByteArray,
        ): String? {
            if (packet.size < 12) {
                return null
            }
            val flags = readUint16(packet, 2)
            val isResponse = (flags and 0x8000) != 0
            val rcode = flags and 0x000F
            if (!isResponse || rcode != 0) {
                return null
            }

            val questionCount = readUint16(packet, 4)
            val answerCount = readUint16(packet, 6)
            if (answerCount == 0) {
                return null
            }

            var offset = 12
            for (i in 0 until questionCount) {
                offset = skipDnsName(packet, offset)
                if (offset < 0 || offset + 4 > packet.size) {
                    return null
                }
                offset += 4
            }

            for (i in 0 until answerCount) {
                offset = skipDnsName(packet, offset)
                if (offset < 0 || offset + 10 > packet.size) {
                    return null
                }
                val type = readUint16(packet, offset)
                val dnsClass = readUint16(packet, offset + 2)
                val dataLength = readUint16(packet, offset + 8)
                offset += 10

                if (offset + dataLength > packet.size) {
                    return null
                }
                if (type == DNS_TYPE_A && dnsClass == DNS_CLASS_IN && dataLength == 4) {
                    val ipBytes = packet.copyOfRange(offset, offset + 4)
                    if (!PlanetDocument.isPrivateOrReservedIpv4(ipBytes)) {
                        return "${ipBytes[0].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}." +
                            "${ipBytes[2].toInt() and 0xFF}.${ipBytes[3].toInt() and 0xFF}"
                    }
                }
                offset += dataLength
            }
            return null
        }

        private fun skipDnsName(
            packet: ByteArray,
            startOffset: Int,
        ): Int {
            var offset = startOffset
            var jumped = 0
            while (offset < packet.size && jumped < 64) {
                val length = packet[offset].toInt() and 0xFF
                if (length == 0) {
                    return offset + 1
                }
                if ((length and 0xC0) == 0xC0) {
                    return if (offset + 1 < packet.size) offset + 2 else -1
                }
                if ((length and 0xC0) != 0) {
                    return -1
                }
                offset += 1 + length
                jumped += 1
            }
            return -1
        }

        private fun readUint16(packet: ByteArray, offset: Int): Int {
            return ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        }

        /**
         * 判断用户输入是否为 HTTP/HTTPS 文件下载链接（包含非根路径或查询参数）。
         *
         * 规则：
         * - 不以 `http://` 或 `https://` 开头（例如 `ddns.example.com`）-> 视为域名/IP 模式（返回 `false`）；
         * - 以 `http://` 或 `https://` 开头，且包含具体文件路径（如 `/planet`）-> 视为 URL 下载模式（返回 `true`）。
         */
        fun isHttpDownloadUrl(rawInput: String): Boolean {
            val trimmed = rawInput.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return false
            }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            if (host.isBlank()) return false
            val path = uri.path.orEmpty()
            return path.isNotEmpty() && path != "/"
        }

        /**
         * 从用户输入（裸域名、带端口域名、或者仅含域名的 URL）中提取主机名。
         */
        fun extractHost(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) {
                return null
            }
            if (trimmed.contains("://")) {
                val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
                return uri.host?.trim()?.takeIf { it.isNotEmpty() }
            }
            val withoutPath = trimmed.substringBefore('/').trim()
            val hostPart = if (withoutPath.count { it == ':' } == 1) {
                withoutPath.substringBefore(':')
            } else {
                withoutPath
            }
            val candidate = hostPart.trim().trimEnd('.')
            if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) {
                return null
            }
            return candidate
        }
    }
}
