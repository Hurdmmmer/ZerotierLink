package io.github.jimmy.ztlink.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jimmy.ztlink.data.settings.SettingsStartupWarmup
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * 应用级入口。
 *
 * 说明：
 * - Hilt 通过该类初始化全局依赖图。
 * - 仅负责框架初始化，不承载业务逻辑。
 */
@HiltAndroidApp
class ZtLinkApplication : Application() {

    /** 启动设置预热器。 */
    @Inject
    lateinit var settingsStartupWarmup: SettingsStartupWarmup

    override fun onCreate() {
        super.onCreate()
        // 关键逻辑：
        // 在 Application 阶段完成设置预热，确保首帧主题与开关状态直接使用持久化值，
        // 避免首次进入页面时从默认值过渡导致“闪一下”的体验问题。
        runBlocking {
            settingsStartupWarmup.warmup()
        }
    }
}
