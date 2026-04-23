package io.github.jimmy.ztlink.data.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于设置状态的 Runtime 应用白名单包名提供器。
 */
@Singleton
class WhitelistPackagesProvider @Inject constructor(
    private val settingsStateHolder: SettingsStateHolder,
)  {

    /**
     * 读取当前白名单包名列表。
     *
     * @return 包名列表。
     */
    suspend fun listWhitelistPackages(): List<String> {
        return settingsStateHolder.currentState().whitelistAppPackages
    }
}
