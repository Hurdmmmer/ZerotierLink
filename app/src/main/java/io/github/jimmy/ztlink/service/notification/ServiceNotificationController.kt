package io.github.jimmy.ztlink.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.MainActivity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 前台服务通知控制器。
 *
 * 省电策略：
 * 1. 状态事件立即刷新（连接/暂停/停止）；
 * 2. 流量文案采用自适应采样间隔，避免固定 1s 轮询；
 * 3. 文案不变时不调用 notify，减少系统唤醒与重绘。
 */
@Singleton
class ServiceNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 系统通知管理器。 */
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** 当前连接网络名称。 */
    private var connectedNetworkName: String = ""

    /** 当前连接网络 ID 文本。 */
    private var connectedNetworkIdText: String = ""

    /** 当前通知模式。 */
    private var notificationMode: NotificationMode = NotificationMode.STATUS

    /** 通用状态通知标题。 */
    private var statusTitle: String = ""

    /** 通用状态通知内容。 */
    private var statusContent: String = ""

    /** 最近一次流量采样时间。 */
    private var lastSampleAtMs: Long = 0L

    /** 最近一次上行总量。 */
    private var lastTxBytes: Long = 0L

    /** 最近一次下行总量。 */
    private var lastRxBytes: Long = 0L

    /** 最近一次通知实际刷新时间。 */
    private var lastRefreshAtMs: Long = 0L

    /** 最近一次流量展示文案。 */
    private var lastTrafficText: String = DEFAULT_TRAFFIC_TEXT

    /**
     * 绑定当前已连接网络信息。
     *
     * @param networkName 网络名称。
     * @param networkIdText 网络 ID 文本。
     */
    fun bindConnectedNetwork(
        networkName: String,
        networkIdText: String,
    ) {
        logChain("通知模式切换 mode=CONNECTED network=$networkName networkId=$networkIdText")
        notificationMode = NotificationMode.CONNECTED
        connectedNetworkName = networkName
        connectedNetworkIdText = networkIdText
        statusTitle = ""
        statusContent = ""
        // 关键逻辑：切换网络时重置采样窗口，避免速率跳变。
        lastSampleAtMs = 0L
        lastTxBytes = 0L
        lastRxBytes = 0L
        lastTrafficText = DEFAULT_TRAFFIC_TEXT
    }

    /**
     * 绑定“连接中”通知状态。
     *
     * @param networkName 网络名称。
     * @param networkIdText 网络 ID 文本。
     */
    fun bindConnectingNetwork(
        networkName: String,
        networkIdText: String,
    ) {
        logChain("通知模式切换 mode=CONNECTING network=$networkName networkId=$networkIdText")
        notificationMode = NotificationMode.CONNECTING
        connectedNetworkName = networkName
        connectedNetworkIdText = networkIdText
        statusTitle = ""
        statusContent = ""
    }

    /**
     * 绑定“仅监控”通知状态。
     *
     * @param networkName 网络名称。
     * @param detail 详情文案。
     */
    fun bindMonitorOnly(
        networkName: String,
        detail: String,
    ) {
        logChain("通知模式切换 mode=MONITOR_ONLY network=$networkName detail=$detail")
        notificationMode = NotificationMode.MONITOR_ONLY
        connectedNetworkName = networkName
        connectedNetworkIdText = detail
        statusTitle = ""
        statusContent = ""
    }

    /**
     * 绑定通用状态通知（例如停止中、错误态）。
     *
     * @param title 标题。
     * @param content 内容。
     */
    fun bindStatus(
        title: String,
        content: String,
    ) {
        logChain("通知模式切换 mode=STATUS title=$title content=$content")
        notificationMode = NotificationMode.STATUS
        statusTitle = title
        statusContent = content
    }

    /**
     * 重置通知内部状态。
     */
    fun resetState() {
        connectedNetworkName = ""
        connectedNetworkIdText = ""
        notificationMode = NotificationMode.STATUS
        statusTitle = ""
        statusContent = ""
        lastSampleAtMs = 0L
        lastTxBytes = 0L
        lastRxBytes = 0L
        lastRefreshAtMs = 0L
        lastTrafficText = DEFAULT_TRAFFIC_TEXT
    }

    /**
     * 初始化通知所需资源。
     */
    fun initNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.service_notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 构建“已连接”通知。
     *
     * @return 通知对象。
     */
    fun buildConnectedNotification(): Notification {
        val displayName = connectedNetworkName.ifBlank {
            connectedNetworkIdText.ifBlank { context.getString(R.string.app_name) }
        }
        val title = context.getString(R.string.service_notification_connected_title, displayName)
        val content = context.getString(R.string.service_notification_connected_content, lastTrafficText)
        return createBaseBuilder()
            .setContentTitle(title)
            .setContentText(content)
            .build()
    }

    /**
     * 按当前通知模式构建前台通知。
     */
    fun buildForegroundNotification(): Notification {
        return when (notificationMode) {
            NotificationMode.CONNECTED -> buildConnectedNotification()
            NotificationMode.CONNECTING -> {
                val displayName = connectedNetworkName.ifBlank {
                    connectedNetworkIdText.ifBlank { context.getString(R.string.app_name) }
                }
                createBaseBuilder()
                    .setContentTitle(context.getString(R.string.service_notification_connecting_title, displayName))
                    .setContentText(context.getString(R.string.service_notification_connecting_content))
                    .build()
            }

            NotificationMode.MONITOR_ONLY -> {
                val displayName = connectedNetworkName.ifBlank {
                    context.getString(R.string.app_name)
                }
                val detailText = connectedNetworkIdText.ifBlank {
                    context.getString(R.string.service_notification_monitor_only_content)
                }
                createBaseBuilder()
                    .setContentTitle(context.getString(R.string.service_notification_monitor_only_title, displayName))
                    .setContentText(detailText)
                    .build()
            }

            NotificationMode.STATUS -> {
                val title = statusTitle.ifBlank { context.getString(R.string.app_name) }
                val content = statusContent.ifBlank { context.getString(R.string.service_notification_idle_network) }
                createBaseBuilder()
                    .setContentTitle(title)
                    .setContentText(content)
                    .build()
            }
        }
    }

    /**
     * 按节流策略刷新流量文案。
     *
     * @param nowMs 当前时间戳（毫秒）。
     * @param statsProvider 流量统计提供者。
     * @return 是否发生实际刷新。
     */
    fun refreshTrafficIfNeeded(
        nowMs: Long,
        statsProvider: TrafficStatsProvider,
    ): Boolean {
        if (notificationMode != NotificationMode.CONNECTED) {
            return false
        }
        val totalTx = statsProvider.currentTxBytes()
        val totalRx = statsProvider.currentRxBytes()

        if (lastSampleAtMs == 0L) {
            lastSampleAtMs = nowMs
            lastTxBytes = totalTx
            lastRxBytes = totalRx
            return false
        }

        val elapsedMs = max(nowMs - lastSampleAtMs, 1L)
        val txDelta = (totalTx - lastTxBytes).coerceAtLeast(0L)
        val rxDelta = (totalRx - lastRxBytes).coerceAtLeast(0L)
        val txSpeed = txDelta * 1000.0 / elapsedMs
        val rxSpeed = rxDelta * 1000.0 / elapsedMs
        val dynamicIntervalMs = computeAdaptiveIntervalMs(txSpeed, rxSpeed)
        if (nowMs - lastRefreshAtMs < dynamicIntervalMs) {
            return false
        }

        lastSampleAtMs = nowMs
        lastTxBytes = totalTx
        lastRxBytes = totalRx
        lastRefreshAtMs = nowMs

        val latestTrafficText = formatSpeedText(txSpeed, rxSpeed)
        if (latestTrafficText == lastTrafficText) {
            return false
        }

        lastTrafficText = latestTrafficText
        notificationManager.notify(NOTIFICATION_ID, buildConnectedNotification())
        return true
    }

    /**
     * 取消通知。
     *
     * @param notificationTag 通知 ID。
     */
    fun cancel(notificationTag: Int) {
        logChain("通知取消 id=$notificationTag")
        notificationManager.cancel(notificationTag)
    }

    private fun logChain(message: String) {
        Log.i(TAG, "[$LOG_KEY] $message")
    }

    /**
     * 流量统计提供者。
     */
    interface TrafficStatsProvider {

        /**
         * 当前上行总字节数。
         *
         * @return 上行总字节数。
         */
        fun currentTxBytes(): Long

        /**
         * 当前下行总字节数。
         *
         * @return 下行总字节数。
         */
        fun currentRxBytes(): Long
    }

    /**
     * 构建基础通知 Builder。
     *
     * @return 通知构建器。
     */
    private fun createBaseBuilder(): NotificationCompat.Builder {
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            pendingIntentFlag,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(ContextCompat.getColor(context, android.R.color.holo_orange_light))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
    }

    /**
     * 计算自适应采样间隔。
     *
     * @param txSpeedBytesPerSec 上行速率（B/s）。
     * @param rxSpeedBytesPerSec 下行速率（B/s）。
     * @return 采样间隔毫秒。
     */
    private fun computeAdaptiveIntervalMs(
        txSpeedBytesPerSec: Double,
        rxSpeedBytesPerSec: Double,
    ): Long {
        val maxSpeed = max(txSpeedBytesPerSec, rxSpeedBytesPerSec)
        return when {
            maxSpeed >= 256 * 1024 -> 1_500L
            maxSpeed >= 64 * 1024 -> 3_000L
            maxSpeed >= 8 * 1024 -> 5_000L
            else -> 10_000L
        }
    }

    /**
     * 速率格式化。
     *
     * @param txSpeedBytesPerSec 上行速率。
     * @param rxSpeedBytesPerSec 下行速率。
     * @return 文案。
     */
    private fun formatSpeedText(
        txSpeedBytesPerSec: Double,
        rxSpeedBytesPerSec: Double,
    ): String {
        return String.format(
            Locale.ROOT,
            "↑%.1fKB/s ↓%.1fKB/s",
            txSpeedBytesPerSec / 1024.0,
            rxSpeedBytesPerSec / 1024.0,
        )
    }

    companion object {
        private const val TAG: String = "ServiceNotification"
        private const val LOG_KEY: String = "ZTL_CHAIN"

        /** 服务通知渠道 ID。 */
        const val CHANNEL_ID: String = "ztlink_runtime_channel"

        /** 服务通知 ID。 */
        const val NOTIFICATION_ID: Int = 2101

        /** 默认流量文案。 */
        private const val DEFAULT_TRAFFIC_TEXT: String = "↑0.0KB/s ↓0.0KB/s"
    }

    /**
     * 前台通知模式。
     */
    private enum class NotificationMode {
        CONNECTED,
        CONNECTING,
        MONITOR_ONLY,
        STATUS,
    }
}
