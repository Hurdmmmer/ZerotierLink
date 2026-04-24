package io.github.jimmy.ztlink.service.controller

import io.github.jimmy.ztlink.service.notification.ServiceNotificationController
import io.github.jimmy.ztlink.model.service.ServiceState
import io.github.jimmy.ztlink.model.service.ServiceStateType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 服务流量观察控制器。
 *
 * 省电策略：
 * 1. 仅在 Connected 状态进入刷新循环；
 * 2. 非 Connected 状态不轮询；
 * 3. 文案变化才触发 notify，减少系统唤醒。
 */
class ServiceTrafficController(
    private val serviceScope: CoroutineScope,
    private val stateFlow: StateFlow<ServiceState>,
    private val notificationController: ServiceNotificationController,
    private val txBytesProvider: () -> Long,
    private val rxBytesProvider: () -> Long,
    private val isForegroundStarted: () -> Boolean,
    private val isScreenInteractive: () -> Boolean,
) {

    /** 流量观察任务。 */
    private var trafficJob: Job? = null

    /** 流量读取器。 */
    private val trafficStatsProvider: ServiceNotificationController.TrafficStatsProvider =
        object : ServiceNotificationController.TrafficStatsProvider {
            override fun currentTxBytes(): Long = txBytesProvider()
            override fun currentRxBytes(): Long = rxBytesProvider()
        }

    /**
     * 启动流量观察。
     */
    fun start() {
        if (trafficJob != null) {
            return
        }
        trafficJob = serviceScope.launch {
            stateFlow.collectLatest { state ->
                if (state.type != ServiceStateType.CONNECTED) {
                    return@collectLatest
                }
                while (currentCoroutineContext().isActive) {
                    if (!isForegroundStarted()) {
                        delay(BACKGROUND_REFRESH_DELAY_MS)
                        continue
                    }
                    if (!isScreenInteractive()) {
                        delay(SCREEN_OFF_REFRESH_DELAY_MS)
                        continue
                    }
                    val changed = notificationController.refreshTrafficIfNeeded(
                        nowMs = System.currentTimeMillis(),
                        statsProvider = trafficStatsProvider,
                    )
                    delay(if (changed) ACTIVE_REFRESH_DELAY_MS else IDLE_REFRESH_DELAY_MS)
                }
            }
        }
    }

    /**
     * 停止流量观察。
     */
    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
    }

    private companion object {
        /** 连接态流量高频刷新间隔。 */
        private const val ACTIVE_REFRESH_DELAY_MS: Long = 1_500L

        /** 连接态流量低频刷新间隔。 */
        private const val IDLE_REFRESH_DELAY_MS: Long = 3_000L

        /** 前台未启动时的保守等待间隔。 */
        private const val BACKGROUND_REFRESH_DELAY_MS: Long = 10_000L

        /** 灭屏后不刷新可视流量通知，降低后台唤醒。 */
        private const val SCREEN_OFF_REFRESH_DELAY_MS: Long = 60_000L
    }
}

