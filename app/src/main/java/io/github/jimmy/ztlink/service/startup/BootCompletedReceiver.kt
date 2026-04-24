package io.github.jimmy.ztlink.service.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.github.jimmy.ztlink.data.settings.SettingsStore
import io.github.jimmy.ztlink.service.ServiceAction
import io.github.jimmy.ztlink.service.ServiceActionDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机完成广播接收器（服务层）。
 *
 * 教学说明：
 * 1. 接收器只负责“开机事件 -> 是否触发服务”，不做复杂业务。
 * 2. 具体的 ZeroTier 启动与自动入网逻辑放在 Service 里，职责更清晰。
 * 3. 统一通过 ServiceActionDispatcher 派发启动动作，避免入口分叉。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        /** 日志 TAG。 */
        private const val TAG = "BootCompletedReceiver"
    }

    /**
     * 接收系统广播入口。
     *
     * @param context 上下文。
     * @param intent 广播 Intent。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        // DataStore 默认位于凭据加密存储（CE）。
        // 在 LOCKED_BOOT_COMPLETED 阶段（用户未解锁）读取 CE 可能失败，
        // 因此这里明确跳过，等 BOOT_COMPLETED 再执行真实自启动流程。
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "Skip LOCKED_BOOT_COMPLETED. Wait for BOOT_COMPLETED.")
            return
        }

        // goAsync 异步处理：
        // BroadcastReceiver 默认要求 onReceive 快速返回；
        // 我们这里要异步读 DataStore，所以用 goAsync + finish 防止 ANR。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settingsStore = SettingsStore(context.applicationContext)
                // 从设置里面读取是否启动开机启动
                val enabled = settingsStore.readStartOnBootEnabled()
                if (!enabled) {
                    Log.i(TAG, "startOnBoot disabled, skip boot autostart.")
                    return@launch
                }
                val bootCount = readBootCount(context.applicationContext)
                if (bootCount != null) {
                    val lastHandledBootCount = settingsStore.readLastHandledBootCount()
                    if (lastHandledBootCount == bootCount) {
                        Log.i(TAG, "Skip duplicated boot autostart. bootCount=$bootCount")
                        return@launch
                    }
                }

                ServiceActionDispatcher(context.applicationContext).dispatch(
                    ServiceAction.StartOrResume(
                        targetNetworkId = null,
                        hasExplicitNetworkId = false,
                        reason = "boot_autostart",
                    ),
                )
                if (bootCount != null) {
                    settingsStore.writeLastHandledBootCount(bootCount)
                }
                Log.i(TAG, "Boot autostart requested. action=$action")
            } catch (t: Throwable) {
                Log.e(TAG, "Boot autostart failed.", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun readBootCount(context: Context): Int? {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
    }
}
