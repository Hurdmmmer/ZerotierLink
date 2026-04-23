package io.github.jimmy.ztlink.service

import io.github.jimmy.ztlink.service.controller.ServiceNetworkController
import io.github.jimmy.ztlink.service.controller.ServiceStateController
import io.github.jimmy.ztlink.service.controller.ServiceTrafficController

/**
 * 运行时观察编排管线。
 *
 * 设计原则：
 * 1. Pipeline 只负责编排 start/stop；
 * 2. 具体观察逻辑全部下沉到独立 Controller；
 * 3. 保持 Service 的 onCreate/onDestroy 清晰可读。
 */
class ServiceRuntimeObserverPipeline(
    private val stateController: ServiceStateController,
    private val trafficController: ServiceTrafficController,
    private val networkController: ServiceNetworkController,
) {

    /** 启动全部观察控制器。 */
    fun start() {
        stateController.start()
        trafficController.start()
        networkController.start()
    }

    /** 停止全部观察控制器。 */
    fun stop() {
        networkController.stop()
        trafficController.stop()
        stateController.stop()
    }
}