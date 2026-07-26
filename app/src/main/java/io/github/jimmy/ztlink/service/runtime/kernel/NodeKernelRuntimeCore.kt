package io.github.jimmy.ztlink.service.runtime.kernel

import android.os.ParcelFileDescriptor
import com.zerotier.sdk.Node
import com.zerotier.sdk.NodeStatus
import com.zerotier.sdk.Peer
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.Version
import com.zerotier.sdk.VirtualNetworkConfig
import io.github.jimmy.ztlink.util.ChainLog
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
    val socketOpen: Boolean,
    val socketProtected: Boolean,
    val vpnThreadAlive: Boolean,
    val udpThreadAlive: Boolean,
    val tunTapBridgeAlive: Boolean,
    val vpnFdValid: Boolean,
) {
    /**
     * Node 与物理链路的数据面是否仍可工作。
     *
     * 这里必须检查资源的实际状态，不能仅凭对象引用存在判断运行时可复用；
     * 线程异常退出或 Socket 被关闭后，引用仍然存在，但数据面已经无法转发。
     */
    val kernelAlive: Boolean
        get() = nodeInited &&
            socketOpen &&
            socketProtected &&
            vpnThreadAlive &&
            udpThreadAlive &&
            tunTapBridgeAlive

    /**
     * Android TUN 数据面是否完整可用。
     *
     * TUN 尚未建立时该值为 false，调用方可根据当前生命周期决定是否要求此门禁。
     */
    val tunnelAlive: Boolean
        get() = kernelAlive && vpnFdValid
}

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
    socketProtected: Boolean = false,
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

    /** 当前 UDP Socket 是否已成功从 VPN 路由中排除。 */
    private val socketProtectedRef: Boolean = socketProtected

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
     * 订阅指定网络上的广播/组播组。
     *
     * 说明：
     * ZeroTier 的虚拟二层依赖 multicastSubscribe 接收与本地址相关的广播/组播帧。
     * 若缺失这一步，控制面可能正常，但 ARP/邻居发现会表现为“只发不收”。
     *
     * @param networkId 目标网络 ID。
     * @param multicastGroup 组播 MAC（低 48 位）。
     * @param multicastAdi 附加分组标识。
     * @return SDK 返回码。
     */
    fun multicastSubscribe(
        networkId: Long,
        multicastGroup: Long,
        multicastAdi: Long,
    ): ResultCode? = nodeRef?.multicastSubscribe(networkId, multicastGroup, multicastAdi)

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
     * 判断当前运行时能否安全复用。
     *
     * @param requireTunnel 是否要求 Android TUN FD 同时有效；Node 冷启动但尚未建立
     * TUN 时应传 false，已连接状态应传 true。
     * @return 所有必需数据面资源均真实存活时返回 true。
     */
    fun isHealthy(requireTunnel: Boolean): Boolean {
        val state = readKernelState()
        return if (requireTunnel) state.tunnelAlive else state.kernelAlive
    }

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
                runCatching { bridge.join(THREAD_STOP_TIMEOUT_MS) }
                    .onFailure { ChainLog.w(TAG, "等待 TunTap 线程退出异常", it) }
                if (bridge.isRunning()) {
                    ChainLog.w(TAG, "TunTap 线程超时未退出 timeout=${THREAD_STOP_TIMEOUT_MS}ms")
                } else {
                    ChainLog.d(TAG, "TunTap 线程已退出")
                }
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
        val socket = socketRef
        val vpnThread = vpnThreadRef
        val udpThread = udpThreadRef
        val tunTapBridge = tunTapBridgeRef
        val vpnSocket = vpnSocketRef
        return NodeKernelState(
            socket = socket,
            udpBridge = udpBridgeRef,
            tunTapBridge = tunTapBridge,
            node = node,
            vpnThread = vpnThread,
            udpThread = udpThread,
            vpnSocket = vpnSocket,
            input = inputRef,
            output = outputRef,
            nodeCreatedAtStart = nodeCreatedAtStartRef,
            nodeAddress = node?.address(),
            nodeInited = node?.isInited == true,
            socketOpen = socket?.isClosed == false,
            socketProtected = socketProtectedRef,
            vpnThreadAlive = vpnThread?.isAlive == true,
            udpThreadAlive = udpThread?.isAlive == true,
            tunTapBridgeAlive = runCatching { tunTapBridge?.isRunning() == true }.getOrDefault(false),
            vpnFdValid = isTunnelIoValid(
                vpnSocket = vpnSocket,
                input = inputRef,
                output = outputRef,
            ),
        )
    }

    /**
     * 检查 TUN 及双向 IO 是否完整有效。
     *
     * 只检查 ParcelFileDescriptor 会漏掉输入流或输出流被单独关闭的半失效状态。
     * 任意句柄关闭或读取 FD 抛异常都降级为 false，不能让诊断反向击穿服务主流程。
     */
    private fun isTunnelIoValid(
        vpnSocket: ParcelFileDescriptor?,
        input: FileInputStream?,
        output: FileOutputStream?,
    ): Boolean {
        return runCatching {
            vpnSocket?.fileDescriptor?.valid() == true &&
                input?.fd?.valid() == true &&
                output?.fd?.valid() == true
        }.getOrDefault(false)
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
     * 中断并有限时等待线程退出。
     *
     * @param thread 目标线程。
     * @param timeoutMs 最长等待时间（毫秒）；超时后记录警告并继续，不再无限阻塞。
     */
    private fun interruptAndJoinThread(thread: Thread?, timeoutMs: Long = THREAD_STOP_TIMEOUT_MS) {
        if (thread?.isAlive != true) {
            return
        }
        thread.interrupt()
        runCatching { thread.join(timeoutMs) }
            .onFailure { ChainLog.w(TAG, "等待线程退出异常 name=${thread.name}", it) }
        if (thread.isAlive) {
            ChainLog.w(TAG, "线程超时未退出 name=${thread.name} timeout=${timeoutMs}ms")
        } else {
            ChainLog.d(TAG, "线程已退出 name=${thread.name}")
        }
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

        /** Node 内核 VPN 线程名。 */
        private const val NODE_KERNEL_VPN_THREAD_NAME = "NodeKernel-VpnThread"

        /** Node 内核 UDP 线程名。 */
        private const val NODE_KERNEL_UDP_THREAD_NAME = "NodeKernel-UdpThread"

        /**
         * 线程停止等待超时（3 秒）。
         *
         * 说明：
         * - stop() 顺序是先关底层 FD/Socket 再 interrupt+join；
         *   正常路径下线程应在几十毫秒内退出；
         * - 为防止极端情况（FD 关闭未解除阻塞、JNI 调用卡住等）
         *   导致 stop() 永久阻塞，超时后记录警告并继续清理。
         */
        private const val THREAD_STOP_TIMEOUT_MS = 3_000L

        /**
         * 创建并启动内核运行时。
         *
         * 说明：
         * 1. 启动职责统一收敛到 Core，避免外部重复启动线程；
         * 2. 支持复用已有资源（socket/node/bridge/thread）；
         * 3. 返回值始终是“可直接运行”的 runtime 实例。
         */
        fun start(config: KernelRuntimeStartConfig): NodeKernelRuntimeCore {
            val createdSocket = config.existingSocket == null
            val socket = config.existingSocket ?: createSocket(
                bindPort = config.bindPort,
                socketTimeoutMs = config.socketTimeoutMs,
            )
            // 关键守卫：socketProtector 为 null 时拒绝启动。
            // 未受保护的 UDP socket 会被路由进 VPN 自身（TUN），导致 ZeroTier 报文自环、
            // 完全不通；旧实现的 `?.invoke` 在此场景下静默跳过 protect，正是「重连后完全不通、
            // 必须杀进程才恢复」的根因之一。这里改为缺失即抛出，由 startRuntime 的 runCatching
            // 捕获并映射为可恢复的 RUNTIME_START_FAILED，而不是制造一个静默不通的 socket。
            val protector = config.socketProtector
            if (protector == null) {
                if (createdSocket) {
                    runCatching { socket.close() }
                }
                throw IllegalStateException(
                    "socketProtector is null; refusing to start runtime with an unprotected UDP socket",
                )
            }
            val protectedOk = try {
                protector.invoke(socket)
            } catch (error: Throwable) {
                if (createdSocket) {
                    runCatching { socket.close() }
                }
                throw IllegalStateException(
                    "VpnService.protect failed for ZeroTier UDP socket",
                    error,
                )
            }
            if (!protectedOk) {
                if (createdSocket) {
                    runCatching { socket.close() }
                }
                throw IllegalStateException(
                    "VpnService.protect returned false; refusing to start runtime with an unprotected UDP socket",
                )
            }

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
                socketProtected = true,
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
