package io.github.jimmy.ztlink.data.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.app.ui.components.settings.PlanetSourceType
import io.github.jimmy.ztlink.util.ChainLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责 Planet 文件的统一落盘、二进制校验、动态域名 DoH 端点同步与 Peer 缓存清理。
 *
 * 设计目标：
 * 1. 不让 UI 或 ViewModel 直接操作文件细节，避免职责混乱；
 * 2. 同时支持“本地文件导入”、“完整 URL 下载”与“方案 A：基于已有 planet.custom 的动态域名 DoH 解析重写”；
 * 3. 在每次 `planet.custom` 发生变更时清理 `peers.d` 缓存，并提供内容指纹供 `RuntimeService` 判断是否需要冷重启。
 */
@Singleton
class PlanetFileStore @Inject constructor(
    /** 应用级 Context，用于访问 filesDir / cacheDir / ContentResolver。 */
    @param:ApplicationContext private val context: Context,
    /** 动态域名 DoH 解析器。 */
    private val planetDohResolver: PlanetDohResolver,
) {
    /**
     * planet 导入结果。
     */
    sealed interface ImportResult {
        /** 导入成功。 */
        data object Success : ImportResult

        /**
         * 导入失败。
         *
         * @param reason 失败原因枚举。
         */
        data class Failure(val reason: FailureReason) : ImportResult
    }

    /**
     * planet 导入失败原因。
     */
    enum class FailureReason {
        /** 无法读取来源文件（Uri 无效、权限不足等）。 */
        CANNOT_READ_SOURCE,

        /** URL 或域名格式不合法。 */
        INVALID_URL,

        /** 下载失败或 DoH 域名解析失败（网络异常、超时、状态码异常或缺少基准 planet.custom）。 */
        DOWNLOAD_FAILED,

        /** 文件不是合法 planet（World 结构校验不通过）。 */
        INVALID_PLANET_FILE,

        /** 文件写入失败。 */
        WRITE_FAILED,
    }

    companion object {
        private const val TAG = "PlanetFileStore"

        /** ZeroTier 原生默认读取的 planet 文件名。 */
        const val FILE_PLANET = "planet"

        /** 自定义 planet 文件名。DataStore hook 时会重定向到这个文件。 */
        const val FILE_CUSTOM_PLANET = "planet.custom"

        /** ZeroTier 节点 Peer 缓存目录名。 */
        private const val DIR_PEERS_CACHE = "peers.d"

        /** 临时下载文件名，避免未校验文件直接覆盖正式文件。 */
        private const val FILE_TEMP_PLANET = "planet.tmp"

        /** URL 下载连接超时（毫秒）。 */
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** URL 下载读取超时（毫秒）。 */
        private const val READ_TIMEOUT_MS = 10_000
    }

    /**
     * 判断当前是否已有有效的自定义 planet 文件。
     */
    fun hasCustomPlanetFile(): Boolean {
        val file = customPlanetFile()
        return file.exists() && isValidPlanetFile(file)
    }

    /**
     * 读取当前 `planet.custom` 的主端点摘要（例如 `101.81.76.191:9994`），用于 UI 展示。
     */
    fun currentCustomPlanetEndpoint(): String? {
        val file = customPlanetFile()
        if (!file.exists()) return null
        return runCatching {
            val doc = PlanetDocument.parse(file.readBytes())
            "${doc.primaryIpv4}:${doc.primaryPort}"
        }.getOrNull()
    }

    /**
     * 读取当前 `planet.custom` 的 SHA-256 指纹与主端点摘要。
     *
     * 用于 `RuntimeService` 判断运行期间 `planet.custom` 内容是否发生过变更。
     */
    fun customPlanetFingerprint(): String? {
        val file = customPlanetFile()
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            val doc = PlanetDocument.parse(bytes)
            "${doc.sha256Hex()}:${doc.primaryIpv4}:${doc.primaryPort}"
        }.getOrNull()
    }

    /**
     * 清理 ZeroTier `peers.d` 缓存目录，防止切换或更新 Planet 后残留旧端点缓存。
     */
    fun clearPeerCache() {
        runCatching {
            val peersDir = File(context.filesDir, DIR_PEERS_CACHE)
            if (peersDir.exists()) {
                peersDir.deleteRecursively()
                ChainLog.i(TAG, "已清理 peers.d 缓存目录")
            }
        }.onFailure { error ->
            ChainLog.w(TAG, "清理 peers.d 缓存目录失败 err=${error.message}")
        }
    }

    /**
     * 从系统文档 Uri 导入并保存 planet 文件。
     *
     * @param sourceUri 用户在系统文件选择器里选中的 Uri。
     */
    fun importFromUri(sourceUri: Uri): ImportResult {
        val temp = tempPlanetFile()
        runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                copyStreamToFile(input, temp)
            } ?: return ImportResult.Failure(FailureReason.CANNOT_READ_SOURCE)
        }.onFailure {
            temp.delete()
            return ImportResult.Failure(FailureReason.CANNOT_READ_SOURCE)
        }

        val promoteResult = promoteTempToCustomPlanet(temp)
        temp.delete()
        return promoteResult
    }

    /**
     * 从 URL 或动态域名导入/更新 `planet.custom`（方案 A）。
     *
     * 行为分支：
     * 1. 若输入为带路径的 `http://` 或 `https://` URL，则先按完整文件下载并校验；
     * 2. 若输入为裸域名（如 `ddns.example.com`）、IPv4 地址或不含文件路径的主机地址，
     *    则基于本地已导入的 `planet.custom` 模板，通过 DoH 解析公网 IPv4 并就地重写端点 IP。
     *
     * @param sourceUrl 用户输入的 URL 或动态域名。
     */
    fun importFromUrl(sourceUrl: String): ImportResult {
        val trimmed = sourceUrl.trim()
        if (trimmed.isBlank()) {
            return ImportResult.Failure(FailureReason.INVALID_URL)
        }

        if (PlanetDohResolver.isHttpDownloadUrl(trimmed)) {
            return downloadPlanetFromHttpUrl(trimmed)
        }

        val host = PlanetDohResolver.extractHost(trimmed)
            ?: return ImportResult.Failure(FailureReason.INVALID_URL)

        return updateCustomPlanetEndpointByHost(host)
    }

    /**
     * 在每次启动或恢复 ZeroTier Runtime 前，若用户配置了动态域名/URL 模式的自定义 Planet，
     * 自动通过 DoH 同步最新公网 IPv4 并更新 `planet.custom`。
     *
     * 容错策略（对齐 ZeroTierHome `SyncPlanetBeforeConnect`）：
     * - 若 DoH 解析成功且 IP 发生变化，原子重写 `planet.custom` 并清理 `peers.d`；
     * - 若 DoH 解析超时或失败，保留现有 `planet.custom` 继续启动，不阻断连接流程。
     *
     * @return `true` 表示 `planet.custom` 内容发生了更新；`false` 表示未更新或无需更新。
     */
    fun syncCustomPlanetEndpointIfNeeded(
        planetUseCustom: Boolean,
        dynamicHost: String = "",
        sourceType: PlanetSourceType = PlanetSourceType.NONE,
        rawSourceValue: String = "",
    ): Boolean {
        if (!planetUseCustom) {
            return false
        }
        val trimmed = dynamicHost.trim().ifBlank {
            if (sourceType == PlanetSourceType.URL) rawSourceValue.trim() else ""
        }
        if (trimmed.isBlank() || PlanetDohResolver.isHttpDownloadUrl(trimmed)) {
            return false
        }
        val host = PlanetDohResolver.extractHost(trimmed) ?: return false
        val targetFile = customPlanetFile()
        if (!targetFile.exists()) {
            ChainLog.w(TAG, "连接前同步 Planet 跳过：本地 planet.custom 不存在 host=$host")
            return false
        }

        val currentDoc = runCatching {
            PlanetDocument.parse(targetFile.readBytes())
        }.getOrElse { error ->
            ChainLog.w(TAG, "连接前同步 Planet 跳过：现有 planet.custom 解析失败 err=${error.message}")
            return false
        }

        val resolvedIp = planetDohResolver.resolveIpv4(host)
        if (resolvedIp.isNullOrBlank()) {
            ChainLog.w(
                TAG,
                "连接前 DoH 解析未返回新 IP，回退沿用现有 planet.custom " +
                    "host=$host current=${currentDoc.primaryIpv4}:${currentDoc.primaryPort}",
            )
            return false
        }

        if (currentDoc.primaryIpv4 == resolvedIp) {
            ChainLog.i(
                TAG,
                "连接前 DoH 校验完成：Planet 端点未变化 host=$host endpoint=$resolvedIp:${currentDoc.primaryPort}",
            )
            return false
        }

        return runCatching {
            val updatedBytes = currentDoc.withIpv4Endpoints(resolvedIp)
            writeBytesAtomicallyToCustomPlanet(updatedBytes)
            clearPeerCache()
            ChainLog.i(
                TAG,
                "连接前已通过 DoH 更新 planet.custom 端点 host=$host " +
                    "old=${currentDoc.primaryIpv4}:${currentDoc.primaryPort} " +
                    "new=$resolvedIp:${currentDoc.primaryPort}",
            )
            true
        }.getOrElse { error ->
            ChainLog.w(TAG, "连接前写入更新后的 planet.custom 失败 err=${error.message}")
            false
        }
    }

    /**
     * 使用 DoH 解析指定域名/IP 并就地更新现有 `planet.custom` 中的 IPv4 端点。
     */
    private fun updateCustomPlanetEndpointByHost(host: String): ImportResult {
        val targetFile = customPlanetFile()
        if (!targetFile.exists()) {
            ChainLog.w(TAG, "域名模式更新 Planet 失败：尚未导入基准 planet.custom 文件 host=$host")
            return ImportResult.Failure(FailureReason.INVALID_PLANET_FILE)
        }

        val currentDoc = runCatching {
            PlanetDocument.parse(targetFile.readBytes())
        }.getOrElse {
            return ImportResult.Failure(FailureReason.INVALID_PLANET_FILE)
        }

        val resolvedIp = planetDohResolver.resolveIpv4(host)
            ?: return ImportResult.Failure(FailureReason.DOWNLOAD_FAILED)

        return runCatching {
            val updatedBytes = currentDoc.withIpv4Endpoints(resolvedIp)
            writeBytesAtomicallyToCustomPlanet(updatedBytes)
            clearPeerCache()
            ChainLog.i(
                TAG,
                "域名模式更新 planet.custom 成功 host=$host " +
                    "old=${currentDoc.primaryIpv4}:${currentDoc.primaryPort} " +
                    "new=$resolvedIp:${currentDoc.primaryPort}",
            )
            ImportResult.Success
        }.getOrElse { error ->
            ChainLog.w(TAG, "域名模式写入 planet.custom 失败 host=$host err=${error.message}")
            ImportResult.Failure(FailureReason.WRITE_FAILED)
        }
    }

    /**
     * 从完整 HTTP(S) URL 下载 planet 文件。
     */
    private fun downloadPlanetFromHttpUrl(sourceUrl: String): ImportResult {
        val temp = tempPlanetFile()
        val connection = runCatching {
            (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
            }
        }.getOrElse {
            return ImportResult.Failure(FailureReason.INVALID_URL)
        }

        return runCatching {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                temp.delete()
                return ImportResult.Failure(FailureReason.DOWNLOAD_FAILED)
            }
            connection.inputStream.use { input ->
                copyStreamToFile(input, temp)
            }
            val promoteResult = promoteTempToCustomPlanet(temp)
            temp.delete()
            promoteResult
        }.getOrElse {
            temp.delete()
            ImportResult.Failure(FailureReason.DOWNLOAD_FAILED)
        }.also {
            connection.disconnect()
        }
    }

    /**
     * 将临时文件提升为正式 custom planet 文件。
     */
    private fun promoteTempToCustomPlanet(tempFile: File): ImportResult {
        val bytes = runCatching { tempFile.readBytes() }.getOrElse {
            return ImportResult.Failure(FailureReason.CANNOT_READ_SOURCE)
        }
        val parsed = runCatching { PlanetDocument.parse(bytes) }.getOrElse {
            return ImportResult.Failure(FailureReason.INVALID_PLANET_FILE)
        }

        return runCatching {
            writeBytesAtomicallyToCustomPlanet(parsed.toByteArray())
            clearPeerCache()
            ChainLog.i(
                TAG,
                "已导入 planet.custom worldId=${java.lang.Long.toUnsignedString(parsed.worldId)} " +
                    "endpoint=${parsed.primaryIpv4}:${parsed.primaryPort}",
            )
            ImportResult.Success
        }.getOrElse {
            ImportResult.Failure(FailureReason.WRITE_FAILED)
        }
    }

    /**
     * 原子写入字节数组到 `planet.custom`。
     */
    private fun writeBytesAtomicallyToCustomPlanet(bytes: ByteArray) {
        val target = customPlanetFile()
        target.parentFile?.mkdirs()
        FileOutputStream(target, false).use { output ->
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
        }
    }

    /**
     * 校验 planet 文件是否符合完整 `PlanetDocument` 二进制规范。
     */
    private fun isValidPlanetFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            return false
        }
        return runCatching {
            PlanetDocument.isValid(file.readBytes())
        }.getOrDefault(false)
    }

    /**
     * 将输入流复制到目标文件（覆盖模式）。
     */
    private fun copyStreamToFile(input: InputStream, target: File) {
        FileOutputStream(target, false).use { output ->
            input.copyTo(output)
            output.flush()
        }
    }

    /**
     * 获取正式 custom planet 文件。
     */
    private fun customPlanetFile(): File = File(context.filesDir, FILE_CUSTOM_PLANET)

    /**
     * 获取临时 planet 文件。
     */
    private fun tempPlanetFile(): File = File(context.cacheDir, FILE_TEMP_PLANET)
}
