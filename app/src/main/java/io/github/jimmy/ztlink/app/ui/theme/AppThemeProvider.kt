package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsViewModel
import io.github.jimmy.ztlink.app.ui.components.settings.ThemeSettings

/**
 * 当前主题配置的 CompositionLocal。
 *
 * 设计意图：
 * 1. 让任意页面都能直接读取主题状态，避免跨层透传。
 * 2. 提供默认值，保证预览与异常场景下仍可渲染。
 */
val LocalThemeSettings = staticCompositionLocalOf<ThemeSettings> {
    ThemeSettings()
}

/**
 * 主题更新函数的 CompositionLocal。
 *
 * 设计意图：
 * 1. 页面只关心“发出更新意图”，不直接依赖 ViewModel 实现细节。
 * 2. 无效默认实现可以避免未注入时报错。
 */
val LocalUpdateTheme = staticCompositionLocalOf<(ThemeSettings) -> Unit> {
    {}
}

/**
 * 应用主题提供器。
 *
 * 职责：
 * 1. 在根层读取设置状态并驱动全局 [ZerotierLinkTheme]。
 * 2. 通过 CompositionLocal 向下分发主题状态与更新入口。
 *
 * @param content 需要在主题环境中渲染的应用内容。
 */
@Composable
fun AppThemeProvider(
    content: @Composable () -> Unit,
) {

    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val uiState = settingsViewModel.settingUiState
    val themeSettings = uiState.themeSettings

    // 使用 remember 固定函数引用，避免每次重组创建新 lambda 引发不必要重组。
    val updateTheme: (ThemeSettings) -> Unit = remember(settingsViewModel) {
        { newTheme ->
            settingsViewModel.updateTheme(newTheme)
        }
    }

    CompositionLocalProvider(
        LocalThemeSettings provides themeSettings,
        LocalUpdateTheme provides updateTheme,
    ) {
        ZerotierLinkTheme(settings = themeSettings) {
            content()
        }
    }
}
