package io.github.jimmy.ztlink.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jimmy.ztlink.app.ui.components.settings.PlanetSourceType
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsUiState
import io.github.jimmy.ztlink.app.ui.theme.AccentPreset
import io.github.jimmy.ztlink.app.ui.components.settings.ThemeMode
import io.github.jimmy.ztlink.app.ui.components.settings.ThemeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import java.io.IOException

/**
 * 负责设置项的持久化读写（DataStore 版本）。
 *
 * 为什么改成 DataStore：
 * 1. DataStore 是异步 IO，避免 SharedPreferences 的主线程阻塞风险。
 * 2. DataStore 天然适配协程和 Flow，更适合 Compose + ViewModel 架构。
 * 3. 后续做“设置变化监听”会更自然（直接 collect Flow）。
 */
class SettingsStore(private val context: Context) {

    companion object {
        /**
         * Context 扩展 DataStore。
         *
         * 注意：
         * 这个 delegate 必须是 top-level / companion 级别单例，不要在函数里反复创建。
         * 否则容易触发同名 DataStore 重复实例异常。
         */
        private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = SettingsKeys.PREFS_NAME
        )

        // Theme
        private val KEY_THEME_MODE = stringPreferencesKey(SettingsKeys.THEME_MODE)
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey(SettingsKeys.DYNAMIC_COLOR)
        private val KEY_ACCENT_PRESET = stringPreferencesKey(SettingsKeys.ACCENT_PRESET)

        // General / Planet
        private val KEY_START_ON_BOOT = booleanPreferencesKey(SettingsKeys.START_ON_BOOT)
        private val KEY_PLANET_USE_CUSTOM = booleanPreferencesKey(SettingsKeys.PLANET_USE_CUSTOM)
        private val KEY_PLANET_AUTO_ROUTE_CHECK = booleanPreferencesKey(SettingsKeys.PLANET_AUTO_ROUTE_CHECK)
        private val KEY_PLANET_PROBE_WIFI_SSID = stringPreferencesKey(SettingsKeys.PLANET_PROBE_WIFI_SSID)
        private val KEY_PLANET_SOURCE_TYPE = stringPreferencesKey(SettingsKeys.PLANET_SOURCE_TYPE)
        private val KEY_PLANET_SOURCE_DISPLAY = stringPreferencesKey(SettingsKeys.PLANET_SOURCE_DISPLAY)
        private val KEY_PLANET_SOURCE_RAW_VALUE = stringPreferencesKey(SettingsKeys.PLANET_SOURCE_RAW_VALUE)

        // Network
        private val KEY_NETWORK_USE_CELLULAR_DATA =
            booleanPreferencesKey(SettingsKeys.NETWORK_USE_CELLULAR_DATA)
        private val KEY_NETWORK_DISABLE_IPV6 = booleanPreferencesKey(SettingsKeys.NETWORK_DISABLE_IPV6)
        private val KEY_DISABLE_NO_NOTIFICATION_ALERT =
            booleanPreferencesKey(SettingsKeys.DISABLE_NO_NOTIFICATION_ALERT)
    }

    /**
     * 读取完整设置快照。
     *
     * @param forceDisableCustomPlanet 是否强制关闭自定义 planet（例如文件缺失）。
     * @return 可直接渲染的 UI 状态对象。
     */
    suspend fun readState(forceDisableCustomPlanet: Boolean): SettingsUiState {
        val prefs = readPreferencesSnapshot()
        val savedUseCustom = prefs[KEY_PLANET_USE_CUSTOM] ?: false
        val effectiveUseCustom = if (forceDisableCustomPlanet) false else savedUseCustom

        return SettingsUiState(
            themeSettings = ThemeSettings(
                themeMode = parseThemeMode(prefs[KEY_THEME_MODE]),
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
                accentPreset = parseAccentPreset(prefs[KEY_ACCENT_PRESET])
            ),
            startOnBoot = prefs[KEY_START_ON_BOOT] ?: true,
            planetUseCustom = effectiveUseCustom,
            planetAutoRouteCheck = prefs[KEY_PLANET_AUTO_ROUTE_CHECK] ?: false,
            probeWifiSsid = prefs[KEY_PLANET_PROBE_WIFI_SSID] ?: "",
            planetSourceType = parsePlanetSourceType(prefs[KEY_PLANET_SOURCE_TYPE]),
            planetSourceDisplay = prefs[KEY_PLANET_SOURCE_DISPLAY] ?: "",
            planetSourceRawValue = prefs[KEY_PLANET_SOURCE_RAW_VALUE] ?: "",
            useCellularData = prefs[KEY_NETWORK_USE_CELLULAR_DATA] ?: false,
            disableIpv6 = prefs[KEY_NETWORK_DISABLE_IPV6] ?: false,
            disableNoNotificationAlert = prefs[KEY_DISABLE_NO_NOTIFICATION_ALERT] ?: false
        )
    }

    /**
     * 仅读取“开机启动”开关。
     *
     * 说明：
     * 开机广播场景只关心这一项，单独提供方法可减少不必要的数据映射。
     */
    suspend fun readStartOnBootEnabled(): Boolean {
        return readPreferencesSnapshot()[KEY_START_ON_BOOT] ?: true
    }

    /**
     * 保存完整状态快照。
     *
     * @param state 当前 UI 状态快照。
     */
    suspend fun writeState(state: SettingsUiState) {
        context.settingsDataStore.edit { prefs ->
            // Theme
            prefs[KEY_THEME_MODE] = state.themeSettings.themeMode.name
            prefs[KEY_DYNAMIC_COLOR] = state.themeSettings.dynamicColor
            prefs[KEY_ACCENT_PRESET] = state.themeSettings.accentPreset.name

            // General / Planet
            prefs[KEY_START_ON_BOOT] = state.startOnBoot
            prefs[KEY_PLANET_USE_CUSTOM] = state.planetUseCustom
            prefs[KEY_PLANET_AUTO_ROUTE_CHECK] = state.planetAutoRouteCheck
            prefs[KEY_PLANET_PROBE_WIFI_SSID] = state.probeWifiSsid
            prefs[KEY_PLANET_SOURCE_TYPE] = state.planetSourceType.name
            prefs[KEY_PLANET_SOURCE_DISPLAY] = state.planetSourceDisplay
            prefs[KEY_PLANET_SOURCE_RAW_VALUE] = state.planetSourceRawValue

            // Network
            prefs[KEY_NETWORK_USE_CELLULAR_DATA] = state.useCellularData
            prefs[KEY_NETWORK_DISABLE_IPV6] = state.disableIpv6
            prefs[KEY_DISABLE_NO_NOTIFICATION_ALERT] = state.disableNoNotificationAlert
        }
    }

    /**
     * 强制关闭自定义 planet 相关开关。
     *
     * 典型场景：
     * 1. 本地 custom planet 文件被删掉。
     * 2. 导入失败，需要回退系统默认 planet。
     */
    suspend fun disableCustomPlanet() {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLANET_USE_CUSTOM] = false
            prefs[KEY_PLANET_AUTO_ROUTE_CHECK] = false
        }
    }

    /**
     * 从 DataStore 读取一次 Preferences 快照。
     *
     * 容错策略：
     * 仅吞掉 IO 异常并回退到空配置，避免读取时直接崩溃。
     */
    private suspend fun readPreferencesSnapshot(): Preferences {
        return context.settingsDataStore.data
            .catch { throwable ->
                if (throwable is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .first()
    }

    /**
     * 解析主题模式字符串。
     */
    private fun parseThemeMode(raw: String?): ThemeMode = runCatching {
        ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    /**
     * 解析强调色预设字符串。
     */
    private fun parseAccentPreset(raw: String?): AccentPreset = runCatching {
        AccentPreset.valueOf(raw ?: AccentPreset.CLASSIC_BLUE.name)
    }.getOrDefault(AccentPreset.CLASSIC_BLUE)

    /**
     * 解析 planet 来源类型字符串。
     */
    private fun parsePlanetSourceType(raw: String?): PlanetSourceType = runCatching {
        PlanetSourceType.valueOf(raw ?: PlanetSourceType.NONE.name)
    }.getOrDefault(PlanetSourceType.NONE)
}
