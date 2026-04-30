package io.github.jimmy.ztlink.util

import android.util.Log

/**
 * ZTL_CHAIN 调试日志开关。
 *
 * 说明：
 * 1. 所有链路调试日志统一经过这里，避免散落在各模块难以统一关闭；
 * 2. enabled=false 时直接短路，减少字符串构建与 Log I/O；
 * 3. 仅控制测试链路日志，不影响普通业务告警日志。
 */
object ChainLog {
    private const val LOG_KEY = "ZTL_CHAIN"

    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun i(tag: String, message: String) {
        if (!enabled) return
        Log.i(tag, "[$LOG_KEY] $message")
    }

    fun d(tag: String, message: String) {
        if (!enabled) return
        Log.d(tag, "[$LOG_KEY] $message")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable == null) {
            Log.w(tag, "[$LOG_KEY] $message")
        } else {
            Log.w(tag, "[$LOG_KEY] $message", throwable)
        }
    }
}
