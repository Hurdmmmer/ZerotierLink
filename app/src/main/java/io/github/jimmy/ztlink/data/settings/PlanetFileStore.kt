package io.github.jimmy.ztlink.data.settings

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 负责 Planet 文件的统一落盘逻辑。
 *
 * 设计目标：
 * 1. 不让 UI 或 ViewModel 直接操作文件细节，避免职责混乱。
 * 2. 无论来自“本地文件”还是“URL 下载”，都走同一套校验与写入流程。
 * 3. 最终只暴露稳定结果：成功 / 失败。
 */
class PlanetFileStore(
    /** 应用级 Context，用于访问 filesDir / cacheDir / ContentResolver。 */
    private val context: Context
) {
    /**
     * planet 导入结果。
     *
     * 设计意图：
     * 旧版只有 true/false，UI 无法知道失败原因；
     * 现在返回结构化结果，便于给用户显示明确提示。
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

        /** URL 不合法。 */
        INVALID_URL,

        /** 下载失败（网络异常、超时、状态码异常等）。 */
        DOWNLOAD_FAILED,

        /** 文件不是合法 planet（头部不匹配或内容过短）。 */
        INVALID_PLANET_FILE,

        /** 文件写入失败。 */
        WRITE_FAILED
    }

    companion object {
        /** ZeroTier 原生默认读取的 planet 文件名。 */
        const val FILE_PLANET = "planet"

        /** 自定义 planet 文件名。DataStore hook 时会重定向到这个文件。 */
        const val FILE_CUSTOM_PLANET = "planet.custom"

        /** 临时下载文件名，避免未校验文件直接覆盖正式文件。 */
        private const val FILE_TEMP_PLANET = "planet.tmp"

        /**
         * planet 文件头部校验。
         * 旧项目就是按首字节 0x01 判断 planet 文件，沿用同样规则。
         */
        private val PLANET_FILE_HEADER = byteArrayOf(0x01)

        /** URL 下载连接超时（毫秒）。 */
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** URL 下载读取超时（毫秒）。 */
        private const val READ_TIMEOUT_MS = 10_000
    }

    /**
     * 判断当前是否已有有效的自定义 planet 文件。
     */
    fun hasCustomPlanetFile(): Boolean = customPlanetFile().exists()

    /**
     * 从系统文档 Uri 导入并保存 planet 文件。
     *
     * @param sourceUri 用户在系统文件选择器里选中的 Uri。
     * @return true 表示导入成功；false 表示导入失败或格式不合法。
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
        if (promoteResult !is ImportResult.Success) {
            temp.delete()
        }
        return promoteResult
    }

    /**
     * 从 URL 下载并保存 planet 文件。
     *
     * @param sourceUrl 用户输入的 planet 文件 URL。
     * @return true 表示下载并保存成功；false 表示网络失败或格式不合法。
     */
    fun importFromUrl(sourceUrl: String): ImportResult {
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
            if (promoteResult !is ImportResult.Success) {
                temp.delete()
            }
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
     *
     * 逻辑：
     * 1. 先校验头部，确保文件像一个合法 planet。
     * 2. 再执行覆盖写入，保证最终文件始终是“完整文件”。
     */
    private fun promoteTempToCustomPlanet(tempFile: File): ImportResult {
        // 校验是不是合法的 planet 文件
        if (!isValidPlanetFile(tempFile)) {
            return ImportResult.Failure(FailureReason.INVALID_PLANET_FILE)
        }

        val target = customPlanetFile()
        return runCatching {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            ImportResult.Success
        }.getOrElse {
            ImportResult.Failure(FailureReason.WRITE_FAILED)
        }
    }

    /**
     * 校验 planet 文件头部。
     *
     * @param file 待校验文件。
     * @return true 表示头部符合预期；false 表示不符合或读取失败。
     */
    private fun isValidPlanetFile(file: File): Boolean {
        if (!file.exists() || file.length() < PLANET_FILE_HEADER.size) {
            return false
        }
        return runCatching {
            val header = ByteArray(PLANET_FILE_HEADER.size)
            FileInputStream(file).use { input ->
                val readBytes = input.read(header)
                if (readBytes != PLANET_FILE_HEADER.size) {
                    return false
                }
            }
            header.contentEquals(PLANET_FILE_HEADER)
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
