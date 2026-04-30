package io.github.jimmy.ztlink.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jimmy.ztlink.data.settings.SettingsStartupWarmup
import io.github.jimmy.ztlink.util.ChainLog
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
        // 全局测试链路日志开关：true=开启，false=关闭。
        ChainLog.setEnabled(CHAIN_LOG_ENABLED)
        // 关键逻辑：
        // 在 Application 阶段完成设置预热，确保首帧主题与开关状态直接使用持久化值，
        // 避免首次进入页面时从默认值过渡导致“闪一下”的体验问题。
        runBlocking {
            settingsStartupWarmup.warmup()
        }
    }

    companion object {
        /**
         * ZTL_CHAIN 调试日志总开关（代码一键开关）。
         *
         * 说明：
         * 1. 改这里即可全局开关链路调试日志；
         * 2. 建议调试阶段为 true，发布或压测阶段改为 false。
         */
        const val CHAIN_LOG_ENABLED: Boolean = false
    }
}
