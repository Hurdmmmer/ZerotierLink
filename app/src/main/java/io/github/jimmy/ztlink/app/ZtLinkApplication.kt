package io.github.jimmy.ztlink.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用级入口。
 *
 * 说明：
 * - Hilt 通过该类初始化全局依赖图。
 * - 仅负责框架初始化，不承载业务逻辑。
 */
@HiltAndroidApp
class ZtLinkApplication : Application()
