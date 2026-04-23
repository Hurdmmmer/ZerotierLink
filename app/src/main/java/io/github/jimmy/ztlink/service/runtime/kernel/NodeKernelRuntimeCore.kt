package io.github.jimmy.ztlink.service.runtime.kernel

import android.util.Log
import android.os.ParcelFileDescriptor
import com.zerotier.sdk.Node
import com.zerotier.sdk.NodeStatus
import com.zerotier.sdk.Peer
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.Version
import com.zerotier.sdk.VirtualNetworkConfig
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * 仅在 runtime 管线内部使用的底层运行态快照。
 */
data class NodeKernelState(
    val socket: DatagramSocket?,
    val udpBridge: KernelUdpBridge?,
    val tunTapBridge: KernelTunTapBridge?,
    val node: Node?,
    val vpnThread: Thread?,
    val udpThread: Thread?,
    val vpnSocket: ParcelFileDescriptor?,
    val input: FileInputStream?,
    val output: FileOutputStream?,
    val nodeCreatedAtStart: Boolean,
    val nodeAddress: Long?,
    val nodeInited: Boolean,
)

/**
 * Node Kernel Runtime 实体。
 *
 * 说明：
 * 1. 统一代理 Node API；
 * 2. 维护运行时资源快照；
 * 3. 负责停止阶段资源回收。
 */
class NodeKernelRuntimeCore (
    socket: DatagramSocket?,
    udpBridge: KernelUdpBridge?,
    tunTapBridge: KernelTunTapBridge?,
    node: Node?,
    vpnThread: Thread?,
    udpThread: Thread?,
    vpnSocket: ParcelFileDescriptor?,
    input: FileInputStream?,
    output: FileOutputStream?,
    nodeCreatedAtStart: Boolean,
) {

    /** 启动时是否创建了新 Node。 */
    private val nodeCreatedAtStartRef: Boolean = nodeCreatedAtStart

    /** 当前 UDP Socket 句柄。 */
    private var socketRef: DatagramSocket? = socket

    /** 当前 UDP 桥接器句柄。 */
    private var udpBridgeRef: KernelUdpBridge? = udpBridge

    /** 当前 TUN/TAP 桥接器句柄。 */
    private var tunTapBridgeRef: KernelTunTapBridge? = tunTapBridge

    /** 当前 Node 句柄。 */
    private var nodeRef: Node? = node

    /** 当前 VPN 后台线程句柄。 */
    private var vpnThreadRef: Thread? = vpnThread

    /** 当前 UDP 后台线程句柄。 */
    private var udpThreadRef: Thread? = udpThread

    /** 当前 VPN FD 句柄。 */
    private var vpnSocketRef: ParcelFileDescriptor? = vpnSocket

    /** 当前 VPN 输入流句柄。 */
    private var inputRef: FileInputStream? = input

    /** 当前 VPN 输出流句柄。 */
    private var outputRef: FileOutputStream? = output

    /**
     * 更新 runtime 持有的 VPN IO 资源。
     *
     * @param vpnSocket VPN 文件描述符（FD）。
     * @param input VPN 输入流。
     * @param output VPN 输出流。
     */
    fun attachTunnelIo(
        vpnSocket: ParcelFileDescriptor?,
        input: FileInputStream?,
        output: FileOutputStream?,
    ) {
        vpnSocketRef = vpnSocket
        inputRef = input
        outputRef = output
    }

    /**
     * 加入网络。
     *
     * @param networkId 目标网络 ID。
     * @return SDK 返回码。
     */
    fun join(networkId: Long): ResultCode? = nodeRef?.join(networkId)

    /**
     * 离开网络。
     *
     * @param networkId 目标网络 ID。
     * @return SDK 返回码。
     */
    fun leave(networkId: Long): ResultCode? = nodeRef?.leave(networkId)

    /**
     * 获取全部网络配置。
     *
     * @return 网络配置数组。
     */
    fun networkConfigs(): Array<VirtualNetworkConfig>? = nodeRef?.networkConfigs()

    /**
     * 获取指定网络配置。
     *
     * @param networkId 目标网络 ID。
     * @return 网络配置；不存在时返回 null。
     */
    fun networkConfig(networkId: Long): VirtualNetworkConfig? = nodeRef?.networkConfig(networkId)

    /**
     * 获取当前 Peer 列表。
     *
     * @return Peer 数组。
     */
    fun peers(): Array<Peer>? = nodeRef?.peers()

    /**
     * Moon 入轨。
     *
     * @param moonWorldId Moon 世界 ID。
     * @param moonSeed Moon 种子值。
     * @return SDK 返回码。
     */
    fun orbit(moonWorldId: Long, moonSeed: Long): ResultCode? = nodeRef?.orbit(moonWorldId, moonSeed)

    /**
     * Moon 退轨。
     *
     * @param moonWorldId Moon 世界 ID。
     * @return SDK 返回码。
     */
    fun deorbit(moonWorldId: Long): ResultCode? = nodeRef?.deorbit(moonWorldId)

    /**
     * 获取节点地址。
     *
     * @return 节点地址。
     */
    fun address(): Long? = nodeRef?.address()

    /**
     * 获取节点状态。
     *
     * @return 节点状态。
     */
    fun status(): NodeStatus? = nodeRef?.status()

    /**
     * 获取 ZeroTier 版本。
     *
     * @return 版本信息。
     */
    fun version(): Version? = nodeRef?.getVersion()

    /**
     * 当前 Node 是否已初始化。
     *
     * @return 初始化状态。
     */
    fun isInited(): Boolean = nodeRef?.isInited() == true

    /**
     * 执行后台任务处理。
     *
     * @param now 当前时间戳（毫秒）。
     * @param nextBackgroundTaskDeadline 下次后台任务截止时间数组。
     * @return SDK 返回码。
     */
    fun processBackgroundTasks(
        now: Long,
        nextBackgroundTaskDeadline: LongArray,
    ): ResultCode? = nodeRef?.processBackgroundTasks(now, nextBackgroundTaskDeadline)

    /**
     * 处理物理网络包。
     *
     * @param now 当前时间戳（毫秒）。
     * @param localSocket 本地 socket 句柄。
     * @param remoteAddress 远端地址。
     * @param packetData 包数据。
     * @param nextBackgroundTaskDeadline 下次后台任务截止时间数组。
     * @return SDK 返回码。
     */
    fun processWirePacket(
        now: Long,
        localSocket: Long,
        remoteAddress: InetSocketAddress,
        packetData: ByteArray,
        nextBackgroundTaskDeadline: LongArray,
    ): ResultCode? =
        nodeRef?.processWirePacket(
            now,
            localSocket,
            remoteAddress,
            packetData,
            nextBackgroundTaskDeadline,
        )

    /**
     * 处理虚拟网络帧。
     *
     * @param now 当前时间戳（毫秒）。
     * @param networkId 网络 ID。
     * @param sourceMac 源 MAC。
     * @param destMac 目标 MAC。
     * @param etherType 以太网类型。
     * @param vlanId VLAN 标识 ID。
     * @param frameData 帧数据。
     * @param nextBackgroundTaskDeadline 下次后台任务截止时间数组。
     * @return SDK 返回码。
     */
    fun processVirtualNetworkFrame(
        now: Long,
        networkId: Long,
        sourceMac: Long,
        destMac: Long,
        etherType: Int,
        vlanId: Int,
        frameData: ByteArray,
        nextBackgroundTaskDeadline: LongArray,
    ): ResultCode? =
        nodeRef?.processVirtualNetworkFrame(
            now,
            networkId,
            sourceMac,
            destMac,
            etherType,
            vlanId,
            frameData,
            nextBackgroundTaskDeadline,
        )

    /**
     * 停止并回收 runtime 资源。
     */
    fun stop(): Boolean {
        // 关键顺序：
        // 1) 先关闭会导致线程阻塞的底层资源（tun fd / udp socket），让 read/recv 尽快返回；
        // 2) 再执行线程中断与等待退出；
        // 3) 最后关闭 node，清理所有引用。
        //
        // 这样可以避免“先 join 再关资源”导致的无限等待，修复离网时卡死。
        closeTunnelIoHeldByRuntime()
        closeQuietly(socketRef)
        interruptAndJoinThread(udpThreadRef)

        tunTapBridgeRef?.let { bridge ->
            if (bridge.isRunning()) {
                bridge.interrupt()
                runCatching { bridge.join() }
                    .onFailure { Log.w(TAG, "[$LOG_KEY] 等待 TunTap 线程退出异常", it) }
                if (bridge.isRunning()) {
                    Log.w(TAG, "[$LOG_KEY] TunTap 线程超时未退出")
                }
                Log.d(TAG, "[$LOG_KEY] TunTap 线程已退出")
            }
        }

        interruptAndJoinThread(vpnThreadRef)

        val nodeClosed = nodeRef != null
        nodeRef?.close()

        socketRef = null
        udpBridgeRef = null
        tunTapBridgeRef = null
        nodeRef = null
        vpnThreadRef = null
        udpThreadRef = null

        return nodeClosed
    }

    /**
     * 获取运行时状态快照。
     *
     * @return 当前 runtime 运行态快照。
     */
    fun readKernelState(): NodeKernelState {
        val node = nodeRef
        return NodeKernelState(
            socket = socketRef,
            udpBridge = udpBridgeRef,
            tunTapBridge = tunTapBridgeRef,
            node = node,
            vpnThread = vpnThreadRef,
            udpThread = udpThreadRef,
            vpnSocket = vpnSocketRef,
            input = inputRef,
            output = outputRef,
            nodeCreatedAtStart = nodeCreatedAtStartRef,
            nodeAddress = node?.address(),
            nodeInited = node?.isInited == true,
        )
    }

    /**
     * 关闭 runtime 持有的 tunnel IO。
     */
    private fun closeTunnelIoHeldByRuntime() {
        closeQuietly(vpnSocketRef)
        closeQuietly(inputRef)
        closeQuietly(outputRef)
        vpnSocketRef = null
        inputRef = null
        outputRef = null
    }

    /**
     * 中断并等待线程退出。
     *
     * @param thread 目标线程。
     */
    private fun interruptAndJoinThread(thread: Thread?) {
        if (thread?.isAlive != true) {
            return
        }
        thread.interrupt()
        runCatching { thread.join() }
            .onFailure { Log.w(TAG, "[$LOG_KEY] 等待线程退出异常 name=${thread.name}", it) }
        if (thread.isAlive) {
            Log.w(TAG, "[$LOG_KEY] 线程超时未退出 name=${thread.name}")
        }
        Log.d(TAG, "[$LOG_KEY] 线程已退出 name=${thread.name}")
    }

    /**
     * 安静关闭 Closeable。
     */
    private fun closeQuietly(target: Closeable?) {
        runCatching { target?.close() }
    }

    /**
     * 安静关闭 ParcelFileDescriptor。
     */
    private fun closeQuietly(target: ParcelFileDescriptor?) {
        runCatching { target?.close() }
    }

    companion object {
        private const val TAG = "NodeKernelRuntimeCore"
        private const val LOG_KEY = "ZTL_CHAIN"

        /** Node 内核 VPN 线程名。 */
        private const val NODE_KERNEL_VPN_THREAD_NAME = "NodeKernel-VpnThread"

        /** Node 内核 UDP 线程名。 */
        private const val NODE_KERNEL_UDP_THREAD_NAME = "NodeKernel-UdpThread"

        /**
         * 创建并启动内核运行时。
         *
         * 说明：
         * 1. 启动职责统一收敛到 Core，避免外部重复启动线程；
         * 2. 支持复用已有资源（socket/node/bridge/thread）；
         * 3. 返回值始终是“可直接运行”的 runtime 实例。
         */
        fun start(config: KernelRuntimeStartConfig): NodeKernelRuntimeCore {
            val socket = config.existingSocket ?: createSocket(
                bindPort = config.bindPort,
                socketTimeoutMs = config.socketTimeoutMs,
            )
            config.socketProtector?.invoke(socket)

            val udpBridge = config.existingUdpBridge ?: config.udpBridgeFactory?.invoke(socket)
            val tunTapBridge = config.existingTunTapBridge ?: config.tunTapBridgeFactory?.invoke(config.networkId)

            val nodeCreatedAtStart = config.existingNode == null
            val node = config.existingNode ?: createAndInitNode(config, udpBridge, tunTapBridge)

            udpBridge?.bindNode(node)
            tunTapBridge?.bindNode(node)

            val vpnThread = config.existingVpnThread?.takeIf { it.isAlive }
                ?: Thread(config.vpnRunnable, NODE_KERNEL_VPN_THREAD_NAME)
            val udpThread = config.existingUdpThread?.takeIf { it.isAlive }
                ?: udpBridge?.let { Thread(it, NODE_KERNEL_UDP_THREAD_NAME) }

            // 启动运行线程（NEW 状态才启动，避免重复 start）。
            if (vpnThread.state == Thread.State.NEW) {
                vpnThread.start()
            }
            if (udpThread?.state == Thread.State.NEW) {
                udpThread.start()
            }

            return NodeKernelRuntimeCore(
                socket = socket,
                udpBridge = udpBridge,
                tunTapBridge = tunTapBridge,
                node = node,
                vpnThread = vpnThread,
                udpThread = udpThread,
                vpnSocket = config.existingVpnSocket,
                input = config.existingInput,
                output = config.existingOutput,
                nodeCreatedAtStart = nodeCreatedAtStart,
            )
        }

        /**
         * 创建并初始化 Node。
         */
        private fun createAndInitNode(
            config: KernelRuntimeStartConfig,
            udpBridge: KernelUdpBridge?,
            tunTapBridge: KernelTunTapBridge?,
        ): Node {
            checkNotNull(udpBridge) {
                "KernelUdpBridge is required when existing node is null."
            }
            checkNotNull(tunTapBridge) {
                "KernelTunTapBridge is required when existing node is null."
            }

            val node = Node(System.currentTimeMillis())
            val initResult = node.init(
                config.dataStoreGetListener,
                config.dataStorePutListener,
                config.packetSender,
                config.eventListener,
                config.frameListener,
                config.configListener,
                config.pathChecker,
            )
            if (initResult != ResultCode.RESULT_OK) {
                node.close()
                throw IllegalStateException("Failed to init node. result=$initResult")
            }
            return node
        }

        /**
         * 创建 UDP Socket。
         */
        private fun createSocket(
            bindPort: Int,
            socketTimeoutMs: Int,
        ): DatagramSocket {
            return DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = socketTimeoutMs
                bind(InetSocketAddress(bindPort))
            }
        }
    }
}
