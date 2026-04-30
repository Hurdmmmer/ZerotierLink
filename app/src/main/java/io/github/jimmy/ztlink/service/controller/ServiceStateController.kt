package io.github.jimmy.ztlink.service.controller

import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.service.notification.ServiceNotificationController
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceState
import io.github.jimmy.ztlink.model.service.ServiceStateType
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 服务状态观察控制器。
 *
 * 说明：
 * 1. 只负责监听服务状态并刷新前台通知；
 * 2. 不参与命令执行与 runtime 业务逻辑；
 * 3. 通过回调控制前台服务启停，避免直接依赖 Service 实例。
 */
class ServiceStateController(
    private val serviceScope: CoroutineScope,
    private val stateFlow: StateFlow<ServiceState>,
    private val notificationController: ServiceNotificationController,
    private val resolveNetworkDisplayName: suspend (NetworkId) -> String,
    private val idleNetworkLabelProvider: () -> String,
    private val monitorOnlyLabelProvider: () -> String,
    private val monitorOnlyContentProvider: () -> String,
    private val stoppingLabelProvider: () -> String,
    private val errorLabelProvider: () -> String,
    private val resolveErrorContent: (ServiceError) -> String,
    private val onEnterForeground: () -> Unit,
    private val onExitForeground: () -> Unit,
) {

    /** 状态观察任务。 */
    private var stateJob: Job? = null

    /**
     * 前台通知是否已进入过。
     *
     * 说明：
     * 1. Service 刚创建时 stateFlow 初始值通常是 STOPPED；
     * 2. 该初始 STOPPED 不代表“正在退出前台”，只是不应触发退出动作；
     * 3. 只有真正进入过前台后，后续 STOPPED 才执行通知取消与 stopForeground。
     */
    private var hasEnteredForeground: Boolean = false

    /** 最近一次已渲染的通知状态签名，用于抑制重复刷新。 */
    private var lastRenderedStateSignature: String? = null

    /**
     * 启动状态观察。
     */
    fun start() {
        if (stateJob != null) {
            return
        }
        stateJob = serviceScope.launch {
            stateFlow.collectLatest { state ->
                val stateSignature = buildStateSignature(state)
                if (stateSignature == lastRenderedStateSignature) {
                    return@collectLatest
                }
                lastRenderedStateSignature = stateSignature
                logChain(
                    "通知状态同步 type=${state.type} networkId=${state.networkId?.value ?: "none"} reason=${state.reason ?: "none"}",
                )
                when (state.type) {
                    ServiceStateType.STOPPED -> {
                        if (!hasEnteredForeground) {
                            return@collectLatest
                        }
                        notificationController.cancel(ServiceNotificationController.NOTIFICATION_ID)
                        onExitForeground()
                        hasEnteredForeground = false
                    }

                    ServiceStateType.STARTING -> {
                        val displayName = state.networkId?.let { resolveNetworkDisplayName(it) }
                            ?: idleNetworkLabelProvider()
                        notificationController.bindConnectingNetwork(
                            networkName = displayName,
                            networkIdText = state.networkId?.value ?: "",
                        )
                        onEnterForeground()
                        hasEnteredForeground = true
                    }

                    ServiceStateType.CONNECTING -> {
                        val networkId = requireNotNull(state.networkId) {
                            "CONNECTING state must carry networkId."
                        }
                        val network = resolveNetworkDisplayName(networkId)
                        notificationController.bindConnectingNetwork(
                            networkName = network,
                            networkIdText = networkId.value,
                        )
                        onEnterForeground()
                        hasEnteredForeground = true
                    }

                    ServiceStateType.CONNECTED -> {
                        val networkId = requireNotNull(state.networkId) {
                            "CONNECTED state must carry networkId."
                        }
                        val network = resolveNetworkDisplayName(networkId)
                        notificationController.bindConnectedNetwork(network, networkId.value)
                        onEnterForeground()
                        hasEnteredForeground = true
                    }

                    ServiceStateType.MONITOR_ONLY -> {
                        val networkName = state.networkId?.let { resolveNetworkDisplayName(it) }
                            ?: monitorOnlyLabelProvider()
                        notificationController.bindMonitorOnly(
                            networkName = networkName,
                            detail = monitorOnlyContentProvider(),
                        )
                        onEnterForeground()
                        hasEnteredForeground = true
                    }

                    ServiceStateType.STOPPING -> {
                        val text = stoppingLabelProvider()
                        notificationController.bindStatus(
                            title = text,
                            content = text,
                        )
                        onEnterForeground()
                        hasEnteredForeground = true
                    }

                    ServiceStateType.ERROR -> {
                        val error = requireNotNull(state.error) {
                            "ERROR state must carry error payload."
                        }
                        notificationController.bindStatus(
                            title = errorLabelProvider(),
                            content = resolveErrorContent(error),
                        )
                        onEnterForeground()
                        hasEnteredForeground = true
                    }
                }
            }
        }
    }

    /**
     * 停止状态观察。
     */
    fun stop() {
        stateJob?.cancel()
        stateJob = null
        hasEnteredForeground = false
        lastRenderedStateSignature = null
    }

    /**
     * 生成通知渲染签名。
     *
     * 说明：
     * 1. 仅使用会影响通知显示的关键字段；
     * 2. 避免同一状态重复触发通知刷新与前台切换。
     */
    private fun buildStateSignature(state: ServiceState): String {
        return buildString {
            append(state.type.name)
            append('|').append(state.networkId?.value ?: "none")
            append('|').append(state.detail ?: "none")
            append('|').append(state.error?.code?.name ?: "none")
        }
    }

    private fun logChain(message: String) {
        ChainLog.i(TAG, message)
    }

    private companion object {
        private const val TAG = "ServiceStateController"
    }
}
