package io.github.jimmy.ztlink.service.startup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.jimmy.ztlink.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ZeroTier 开机自启服务（脚手架版本）。
 *
 * 教学说明：
 * 1. 该服务当前只负责“开机链路占位 + 日志验证”。
 * 2. 核心入网能力（Node 初始化 / join network）尚未接入。
 * 3. 等核心功能完成后，把 TODO 替换成真实逻辑即可。
 */
class ZeroTierBootService : Service() {
    /**
     * 服务级协程作用域。
     *
     * 说明：
     * 与 Service 生命周期绑定，onDestroy 时统一 cancel。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 是否已经处理过开机启动请求。
     *
     * 说明：
     * 防止系统短时间重复触发 onStartCommand 导致重复执行准备流程。
     */
    @Volatile
    private var bootStartHandled = false

    companion object {
        /** 启动动作：由开机广播触发。 */
        const val ACTION_BOOT_AUTOSTART = "io.github.jimmy.ztlink.action.BOOT_AUTOSTART"

        /** 前台通知渠道 ID。 */
        private const val NOTIFICATION_CHANNEL_ID = "ztlink_boot_channel"

        /** 前台通知 ID。 */
        private const val NOTIFICATION_ID = 1101

        /** 日志 TAG。 */
        private const val TAG = "ZeroTierBootService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
    }

    /**
     * 服务启动入口。
     *
     * @param intent 启动 Intent。
     * @param flags 系统标志位。
     * @param startId 本次启动 ID。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_BOOT_AUTOSTART) {
            // 非预期动作直接拒绝，避免该服务被误用。
            Log.w(TAG, "Ignore unexpected action: $action")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 先进入前台，满足 Android 8.0+ 前台服务启动要求。
        startForeground(NOTIFICATION_ID, buildBootNotification())

        serviceScope.launch {
            try {
                Log.i(TAG, "Boot service started. action=$action")

                // 幂等保护：同一次开机周期重复触发时只执行一次。
                if (bootStartHandled) {
                    Log.i(TAG, "Boot start already handled, skip duplicated request.")
                    return@launch
                }
                bootStartHandled = true

                runBootPreparationPipeline()
            } catch (t: Throwable) {
                // 失败后允许系统后续重试。
                bootStartHandled = false
                Log.e(TAG, "Boot service execution failed.", t)
            }
        }

        // 作为“正确服务逻辑骨架”保持存活；后续接入 Node 运行循环更自然。
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        bootStartHandled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 开机自启动准备流程（可交付骨架）。
     *
     * 当前阶段：
     * 1. 启动链路完整（广播 -> 前台服务）。
     * 2. 生命周期完整（常驻 + 销毁回收）。
     * 3. 日志步骤完整（便于联调和验收）。
     *
     * TODO(ZeroTier-Core):
     * 在 Step-3 接入真实自动入网：
     * - 读取上次网络 ID
     * - 初始化 ZeroTier Runtime
     * - 执行 join(networkId)
     */
    private suspend fun runBootPreparationPipeline() {
        // Step-1：初始化运行时依赖（占位）
        Log.i(TAG, "Step-1 prepare runtime dependencies. TODO core init.")

        // Step-2：校验开机环境（占位）
        Log.i(TAG, "Step-2 validate boot environment. TODO network/vpn checks.")

        // Step-3：自动入网（核心功能待接入）
        Log.i(TAG, "Step-3 TODO auto-join network by last saved network id.")
    }

    /**
     * 构建开机自启前台通知。
     */
    private fun buildBootNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.boot_service_notification_title))
            .setContentText(getString(R.string.boot_service_notification_content))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Android 8.0+ 通知渠道创建。
     */
    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.boot_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.boot_service_notification_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
