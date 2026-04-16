package io.github.jimmy.ztlink.app.ui.components.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimmy.ztlink.data.settings.PlanetFileStore
import io.github.jimmy.ztlink.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent

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
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 配置持久化存储。 */
    private val settingsStore = SettingsStore(appContext)

    /** planet 文件落盘管理器。 */
    private val planetFileStore = PlanetFileStore(appContext)

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
        viewModelScope.launch {
            // App 启动后立即读取持久化配置。
            // 如果本地勾选了“使用自定义 planet”但文件已丢失，会自动回退关闭该开关。
            val hasPlanetFile = withContext(Dispatchers.IO) {
                planetFileStore.hasCustomPlanetFile()
            }
            val shouldForceDisableCustomPlanet = !hasPlanetFile

            val stateFromStore = withContext(Dispatchers.IO) {
                settingsStore.readState(
                    forceDisableCustomPlanet = shouldForceDisableCustomPlanet
                )
            }
            settingUiState = stateFromStore

            if (shouldForceDisableCustomPlanet && stateFromStore.planetUseCustom) {
                // 理论上 readState 已经处理，这里作为保险兜底。
                withContext(Dispatchers.IO) {
                    settingsStore.disableCustomPlanet()
                }
            }
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
                it.copy(
                    planetUseCustom = false,
                    planetAutoRouteCheck = false
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
            it.copy(planetAutoRouteCheck = enabled)
        }
    }

    /**
     * 修改用于探测内网环境的 Wi-Fi SSID。
     *
     * @param probeSSID 目标 Wi-Fi SSID。
     */
    fun updateProbeWifiSsid(probeSSID: String) {
        updateStateAndPersist {
            it.copy(probeWifiSsid = probeSSID)
        }
    }

    /**
     * 从本地文件导入 planet 并设置来源信息。
     *
     * @param displayName 用于 UI 展示的文件名。
     * @param rawUri 文件 Uri 字符串。
     */
    fun setPlanetSourceFromFile(displayName: String, rawUri: String) {
        viewModelScope.launch {
            val sourceUri = runCatching { rawUri.toUri() }.getOrNull()
            if (sourceUri == null) {
                return@launch
            }

            // 文件 IO 必须放到 IO 线程，避免阻塞主线程。
            val importResult = withContext(Dispatchers.IO) {
                planetFileStore.importFromUri(sourceUri)
            }
            when (importResult) {
                is PlanetFileStore.ImportResult.Success -> {
                    updateStateAndPersist {
                        it.copy(
                            planetUseCustom = true,
                            planetSourceType = PlanetSourceType.FILE,
                            planetSourceDisplay = displayName,
                            planetSourceRawValue = rawUri
                        )
                    }
                }
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

        viewModelScope.launch {
            // 网络 + 文件写入统一放在 IO 线程。
            val importResult = withContext(Dispatchers.IO) {
                planetFileStore.importFromUrl(finalUrl)
            }
            when (importResult) {
                is PlanetFileStore.ImportResult.Success -> {
                    updateStateAndPersist {
                        it.copy(
                            planetUseCustom = true,
                            planetSourceType = PlanetSourceType.URL,
                            planetSourceDisplay = finalUrl,
                            planetSourceRawValue = finalUrl
                        )
                    }
                }
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
        val newState = reducer(settingUiState)
        settingUiState = newState

        // 持久化放在 IO 线程，避免主线程做磁盘写入。
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.writeState(newState)
        }
    }

}
