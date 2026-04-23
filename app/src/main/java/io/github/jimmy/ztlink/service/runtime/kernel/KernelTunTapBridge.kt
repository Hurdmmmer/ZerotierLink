package io.github.jimmy.ztlink.service.runtime.kernel

import com.zerotier.sdk.Node

/**
 * TUN/TAP 桥接器实体。
 */
open class KernelTunTapBridge {

    /**
     * 绑定 Node。
     *
     * @param node ZeroTier 节点实例。
     */
    open fun bindNode(node: Node) = Unit

    /**
     * 当前是否运行中。
     *
     * @return 运行状态。
     */
    open fun isRunning(): Boolean = false

    /**
     * 请求中断。
     */
    open fun interrupt() = Unit

    /**
     * 等待线程结束。
     *
     * @throws InterruptedException 等待中断异常。
     */
    @Throws(InterruptedException::class)
    open fun join() = Unit

    /**
     * 限时等待线程结束。
     *
     * 说明：
     * - 默认回退到无参 join，保持旧实现兼容；
     * - 具体实现可覆写为真正的超时等待。
     *
     * @param timeoutMs 超时时间（毫秒）。
     * @throws InterruptedException 等待中断异常。
     */
    @Throws(InterruptedException::class)
    open fun join(timeoutMs: Long) {
        join()
    }
}
