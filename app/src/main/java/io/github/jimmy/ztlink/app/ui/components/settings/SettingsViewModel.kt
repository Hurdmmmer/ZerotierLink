package io.github.jimmy.ztlink.app.ui.components.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimmy.ztlink.data.settings.PlanetFileStore
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.text.Regex

import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 设置页一次性 UI 事件。
 */
sealed interface SettingsUiEvent : CommonUiEvent {
    /**
     * Planet 导入失败事件。
     */
    data class PlanetImportFailed(
        val reason: PlanetFileStore.FailureReason
    ) : SettingsUiEvent, CommonUiEvent.ShowToast {
        override val messageRes: Int
            get() = when (reason) {
                PlanetFileStore.FailureReason.CANNOT_READ_SOURCE -> R.string.settings_planet_import_error_cannot_read_source
                PlanetFileStore.FailureReason.INVALID_URL -> R.string.settings_planet_import_error_invalid_url
                PlanetFileStore.FailureReason.DOWNLOAD_FAILED -> R.string.settings_planet_import_error_download_failed
                PlanetFileStore.FailureReason.INVALID_PLANET_FILE -> R.string.settings_planet_import_error_invalid_file
                PlanetFileStore.FailureReason.WRITE_FAILED -> R.string.settings_planet_import_error_write_failed
            }
    }
}

/**
 * 设置页 ViewModel。
 *
 * 核心职责：
 * 1. 管理 Settings UI 状态。
 * 2. 在初始化时读取本地持久化配置。
 * 3. 在状态变更时写回持久化层。
 * 4. 处理 planet 文件导入（本地文件 / URL）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    /** 设置状态单例持有器（应用级唯一状态源）。 */
    private val settingsStateHolder: SettingsStateHolder,
) : ViewModel() {

    /**
     * UI 状态模型。
     *
     * 说明：
     * 先给默认值，init 中再替换成“本地读取结果”。
     */
    var settingUiState by mutableStateOf(SettingsUiState())
        private set

    /**
     * 一次性 UI 事件流。
     *
     * replay = 0：
     * 新订阅者不会重复收到旧事件，避免 Toast 重播。
     */
    private val _uiEvents = MutableSharedFlow<SettingsUiEvent>(replay = 0)
    val uiEvents = _uiEvents.asSharedFlow()

    init {
        // 关键逻辑：
        // 1) 先使用单例持有器当前快照，保证首帧可立即渲染；
        // 2) 再持续订阅同一状态流，保证多个 ViewModel 实例看到一致状态。
        settingUiState = settingsStateHolder.currentState()
        viewModelScope.launch {
            settingsStateHolder.state.collectLatest { latest ->
                settingUiState = latest
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // 关键逻辑：
            // 强制刷新最新持久化值，避免仅依赖启动缓存导致页面读到旧状态。
            settingsStateHolder.refreshFromStore(forceRefresh = true)
        }
    }

    /**
     * 修改主题设置。
     *
     * @param newTheme 新的主题配置对象。
     */
    fun updateTheme(newTheme: ThemeSettings) {
        updateStateAndPersist {
            it.copy(themeSettings = newTheme)
        }
    }

    /**
     * 修改开机启动设置。
     *
     * @param enabled 是否启用开机启动。
     */
    fun toggleStartOnBoot(enabled: Boolean) {
        updateStateAndPersist {
            it.copy(startOnBoot = enabled)
        }
    }

    /**
     * 修改“是否启用自定义 planet”。
     *
     * @param enabled 是否启用。
     */
    fun togglePlanetUseCustom(enabled: Boolean) {
        updateStateAndPersist {
            if (enabled) {
                it.copy(planetUseCustom = true)
            } else {
                // 关键逻辑：
                // 关闭自定义 Planet 仅代表“整套内网探测策略暂时失效”，
                // 不应改写 auto route check 的用户选择值。
                // auto route 开关状态只允许用户主动点击修改。
                it.copy(
                    planetUseCustom = false,
                )
            }
        }
    }

    /**
     * 修改自动路由探测开关。
     *
     * @param enabled 是否启用。
     */
    fun togglePlanetAutoRouteCheck(enabled: Boolean) {
        updateStateAndPersist {
            if (enabled) {
                it.copy(planetAutoRouteCheck = true)
            } else {
                // 关键逻辑：
                it.copy(
                    planetAutoRouteCheck = false,
                )
            }
        }
    }

    /**
     * 修改用于探测内网环境的固定 IP。
     *
     * @param probeIp 目标内网 IP。
     */
    fun updatePlanetIntranetProbeIp(probeIp: String) {
        updateStateAndPersist {
            val normalizedIp = probeIp.trim()
            if (!it.planetUseCustom) {
                return@updateStateAndPersist it
            }
            if (!it.planetAutoRouteCheck) {
                return@updateStateAndPersist it
            }
            if (normalizedIp.isBlank()) {
                return@updateStateAndPersist it.copy(planetIntranetProbeIp = "")
            }
            if (!isValidIpv4(normalizedIp)) {
                return@updateStateAndPersist it
            }
            it.copy(planetIntranetProbeIp = normalizedIp)
        }
    }

    /**
     * 从本地文件导入 planet 并设置来源信息。
     *
     * @param displayName 用于 UI 展示的文件名。
     * @param rawUri 文件 Uri 字符串。
     */
    fun setPlanetSourceFromFile(displayName: String, rawUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 关键逻辑：
            // 文件导入与状态写回统一由单例状态源处理，ViewModel 仅负责 UI 事件。
            val importResult = settingsStateHolder.importPlanetFromFile(
                displayName = displayName,
                rawUri = rawUri,
            )
            when (importResult) {
                is PlanetFileStore.ImportResult.Success -> Unit
                is PlanetFileStore.ImportResult.Failure -> {
                    _uiEvents.emit(SettingsUiEvent.PlanetImportFailed(importResult.reason))
                }
            }
        }
    }

    /**
     * 从 URL 下载 planet 并设置来源信息。
     *
     * @param url 输入的 URL。
     */
    fun setPlanetSourceFromUrl(url: String) {
        val finalUrl = url.trim()
        if (finalUrl.isBlank()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // 关键逻辑：
            // URL 下载与设置写回统一由单例状态源处理，避免多层重复写状态。
            val importResult = settingsStateHolder.importPlanetFromUrl(finalUrl)
            when (importResult) {
                is PlanetFileStore.ImportResult.Success -> Unit
                is PlanetFileStore.ImportResult.Failure -> {
                    _uiEvents.emit(SettingsUiEvent.PlanetImportFailed(importResult.reason))
                }
            }
        }
    }

    /**
     * 修改是否允许蜂窝数据连接。
     *
     * @param enabled 是否允许。
     */
    fun toggleUseCellularData(enabled: Boolean) {
        updateStateAndPersist {
            it.copy(useCellularData = enabled)
        }
    }

    /**
     * 修改是否禁用 IPv6。
     *
     * @param enabled 是否禁用。
     */
    fun toggleDisableIpv6(enabled: Boolean) {
        updateStateAndPersist {
            it.copy(disableIpv6 = enabled)
        }
    }

    /**
     * 覆盖更新白名单包名列表。
     *
     * @param packages 新的包名列表。
     */
    fun updateWhitelistAppPackages(packages: List<String>) {
        val normalized = normalizePackageNames(packages)
        updateStateAndPersist {
            it.copy(whitelistAppPackages = normalized)
        }
    }

    /**
     * 新增一个白名单包名。
     *
     * @param packageName 包名。
     */
    fun addWhitelistAppPackage(packageName: String) {
        updateStateAndPersist { old ->
            val merged = normalizePackageNames(old.whitelistAppPackages + packageName)
            old.copy(whitelistAppPackages = merged)
        }
    }

    /**
     * 删除一个白名单包名。
     *
     * @param packageName 包名。
     */
    fun removeWhitelistAppPackage(packageName: String) {
        val normalizedTarget = packageName.trim()
        if (normalizedTarget.isBlank()) {
            return
        }
        updateStateAndPersist { old ->
            old.copy(
                whitelistAppPackages = old.whitelistAppPackages
                    .filterNot { it == normalizedTarget }
            )
        }
    }

    /**
     * 快捷读取当前主题设置。
     */
    val themeSettings: ThemeSettings
        get() = settingUiState.themeSettings

    /**
     * 统一的“更新状态并持久化”入口。
     *
     * @param reducer 传入旧状态，返回新状态的函数。
     */
    private fun updateStateAndPersist(
        reducer: (SettingsUiState) -> SettingsUiState
    ) {
        // 统一委托给单例状态源执行“更新 + 缓存同步 + 持久化”，
        // 避免多个 ViewModel 各自写入导致状态互相覆盖。
        viewModelScope.launch(Dispatchers.IO) {
            settingsStateHolder.updateState(reducer)
        }
    }

    /**
     * 规范化包名列表。
     *
     * 规则：
     * 1. 去除首尾空白；
     * 2. 过滤空值；
     * 3. 过滤非法包名；
     * 4. 去重并保持原始顺序。
     */
    private fun normalizePackageNames(values: List<String>): List<String> {
        val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { packageRegex.matches(it) }
            .distinct()
            .toList()
    }

    private fun isValidIpv4(raw: String): Boolean {
        val address = runCatching { InetAddress.getByName(raw) }.getOrNull() ?: return false
        return address is Inet4Address && address.hostAddress == raw
    }

}
