package io.github.jimmy.ztlink.service.runtime.kernel

import com.zerotier.sdk.Node

/**
 * UDP 桥接器实体。
 */
open class KernelUdpBridge : Runnable {

    /**
     * 绑定 Node。
     *
     * @param node ZeroTier 节点实例。
     */
    open fun bindNode(node: Node) = Unit

    override fun run() = Unit
}
