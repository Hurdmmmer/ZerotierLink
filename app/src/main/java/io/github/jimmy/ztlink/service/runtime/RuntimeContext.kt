package io.github.jimmy.ztlink.service.runtime

import android.content.Context
import android.util.Log
import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.Event
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkFrameListener
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.data.settings.PlanetFileStore
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.service.runtime.kernel.KernelTunTapBridge
import io.github.jimmy.ztlink.service.runtime.kernel.KernelUdpBridge
import io.github.jimmy.ztlink.service.runtime.kernel.NodeKernelRuntimeCore
import io.github.jimmy.ztlink.util.ChainLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Node Runtime 上下文。
 *
 * 说明：
 * 1. 该类是 runtime 级资源持有者，统一管理 runtime 句柄、TUN IO、统计与回调；
 * 2. 控制器通过它共享 runtime 数据，避免跨层直接耦合；
 * 3. 桥接线程所需工厂与监听器都从这里创建，保证生命周期一致。
 */
@Singleton
class RuntimeContext @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsStateHolder: SettingsStateHolder,
    private val planetFileStore: PlanetFileStore,
) {
    /** IPv4 邻居表：networkId -> (ipv4Int -> macLong)。 */
    private val ipv4MacTable: MutableMap<Long, MutableMap<Int, Long>> = mutableMapOf()

    /** IPv4 邻居表锁。 */
    private val ipv4MacTableLock: ReentrantLock = ReentrantLock()

    /** 后台任务循环唤醒锁。 */
    private val backgroundWakeLock: ReentrantLock = ReentrantLock()

    /** 后台任务循环唤醒条件。 */
    private val backgroundWakeCondition = backgroundWakeLock.newCondition()

    /** TUN 输入流变化唤醒锁。 */
    private val tunInputWakeLock: ReentrantLock = ReentrantLock()

    /** TUN 输入流变化唤醒条件。 */
    private val tunInputWakeCondition = tunInputWakeLock.newCondition()


    /** 当前运行时句柄。 */
    private val runtimeRef: AtomicReference<NodeKernelRuntimeCore?> = AtomicReference(null)

    /** 当前 TUN 输入流。 */
    private val tunInputRef: AtomicReference<FileInputStream?> = AtomicReference(null)

    /** 当前 TUN 输出流。 */
    private val tunOutputRef: AtomicReference<FileOutputStream?> = AtomicReference(null)

    /** 上行累计字节数。 */
    private val txBytes: AtomicLong = AtomicLong(0L)

    /** 下行累计字节数。 */
    private val rxBytes: AtomicLong = AtomicLong(0L)

    /** 下一次后台任务截止时间。 */
    private val nextBackgroundDeadlineMs: AtomicLong = AtomicLong(0L)

    /** 当前活动网络 ID。 */
    private val activeNetworkIdRef: AtomicLong = AtomicLong(0L)

    /** 当前 Socket 保护器。 */
    private val socketProtectorRef: AtomicReference<((DatagramSocket) -> Boolean)?> = AtomicReference(null)

    /** 网络配置更新回调。 */
    private val networkConfigCallbackRef:
        AtomicReference<((Long, VirtualNetworkConfigOperation, VirtualNetworkConfig?) -> Unit)?> =
        AtomicReference(null)

    /** 统一 DataStore 监听器实现。 */
    private val dataStoreDelegate: NodeDataStore = NodeDataStore(
        context = appContext,
        settingsStateHolder = settingsStateHolder,
        planetFileStore = planetFileStore,
    )

    /** 统一 PacketSender 实现。 */
    private val packetSenderDelegate: NodePacketSender = NodePacketSender()

    /** DataStore Get 监听器。 */
    val dataStoreGetListener: DataStoreGetListener
        get() = dataStoreDelegate

    /** DataStore Put 监听器。 */
    val dataStorePutListener: DataStorePutListener
        get() = dataStoreDelegate

    /** PacketSender 监听器。 */
    val packetSender: PacketSender
        get() = packetSenderDelegate

    /** SDK 事件监听器。 */
    val eventListener: EventListener = object : EventListener {
        override fun onEvent(event: Event) {
            Log.d(TAG, "Node event: $event")
        }

        override fun onTrace(message: String) {
            // Node trace 日志量极大，长期开启会造成明显 I/O 与发热。
            // 默认关闭，仅在排障时临时打开。
            if (NODE_TRACE_LOG_ENABLED) {
                Log.d(TAG, "Node trace: $message")
            }
        }
    }

    /** SDK 虚拟网帧监听器。 */
    val frameListener: VirtualNetworkFrameListener = object : VirtualNetworkFrameListener {
        override fun onVirtualNetworkFrame(
            nwid: Long,
            srcMac: Long,
            destMac: Long,
            etherType: Long,
            vlanId: Long,
            frameData: ByteArray,
        ) {
            when (etherType.toInt()) {
                ETHER_TYPE_ARP -> handleIncomingArpFrame(
                    networkId = nwid,
                    srcMac = srcMac,
                    frameData = frameData,
                )

                ETHER_TYPE_IPV4 -> {
                    extractIpv4Source(frameData)?.let { sourceIpv4 ->
                        putIpv4MacMapping(
                            networkId = nwid,
                            ipv4 = sourceIpv4,
                            mac = srcMac,
                        )
                    }
                    val output = tunOutputRef.get() ?: return
                    runCatching {
                        output.write(frameData)
                        rxBytes.addAndGet(frameData.size.toLong())
                    }.onFailure {
                        Log.w(TAG, "Write TUN output failed. nwid=$nwid", it)
                    }
                }

                ETHER_TYPE_IPV6 -> {
                    val output = tunOutputRef.get() ?: return
                    runCatching {
                        output.write(frameData)
                        rxBytes.addAndGet(frameData.size.toLong())
                    }.onFailure {
                        Log.w(TAG, "Write TUN output failed. nwid=$nwid", it)
                    }
                }

                else -> Unit
            }
        }
    }

    /** SDK 网络配置监听器。 */
    val configListener: VirtualNetworkConfigListener =
        VirtualNetworkConfigListener { nwid, op, config ->
            networkConfigCallbackRef.get()?.invoke(nwid, op, config)
            0
        }

    /** VPN 后台任务循环。 */
    val vpnRunnable: Runnable = Runnable {
        runBackgroundTaskLoop()
    }

    /**
     * 创建 UDP Bridge 工厂。
     *
     * @return UDP Bridge 工厂。
     */
    fun createUdpBridgeFactory(): (DatagramSocket) -> KernelUdpBridge {
        return { socket ->
            packetSenderDelegate.attachSocket(socket)
            NodeUdpBridge(
                socket = socket,
                runtimeProvider = { runtimeRef.get() },
                onNextBackgroundDeadline = { deadline -> updateNextBackgroundDeadline(deadline) },
            )
        }
    }

    /**
     * 创建 TUN Bridge 工厂。
     *
     * @return TUN Bridge 工厂。
     */
    fun createTunTapBridgeFactory(): (Long) -> KernelTunTapBridge {
        return { networkId ->
            NodeTunTapBridge(
                networkId = networkId,
                runtimeProvider = { runtimeRef.get() },
                inputProvider = { tunInputRef.get() },
                awaitInputProvider = { currentInput ->
                    awaitTunInputAvailableInterruptibly(currentInput)
                },
                findIpv4Mac = { nwid, ipv4 -> findIpv4Mac(nwid, ipv4) },
                buildArpRequestPayload = { localMac, localIpv4, targetIpv4 ->
                    buildArpRequestPayload(localMac, localIpv4, targetIpv4)
                },
                resolveAssignedIpv4 = { addresses -> resolveAssignedIpv4(addresses) },
                onTxBytes = { delta -> txBytes.addAndGet(delta) },
                onNextBackgroundDeadline = { deadline -> updateNextBackgroundDeadline(deadline) },
            )
        }
    }

    /**
     * 绑定当前 runtime。
     *
     * @param runtime Node runtime 句柄。
     */
    fun bindRuntime(runtime: NodeKernelRuntimeCore?) {
        runtimeRef.set(runtime)
        notifyBackgroundLoop()
    }

    /**
     * 绑定 TUN IO。
     *
     * @param input TUN 输入流。
     * @param output TUN 输出流。
     */
    fun bindTunnelIo(
        input: FileInputStream?,
        output: FileOutputStream?,
    ) {
        tunInputRef.set(input)
        tunOutputRef.set(output)
        notifyTunInputChanged()
    }

    /**
     * 清空 TUN IO 引用。
     */
    fun clearTunnelIo() {
        tunInputRef.set(null)
        tunOutputRef.set(null)
        notifyTunInputChanged()
    }

    /**
     * 设置活动网络 ID。
     *
     * @param networkId 活动网络 ID，可空。
     */
    fun setActiveNetworkId(networkId: Long?) {
        activeNetworkIdRef.set(networkId ?: 0L)
    }

    /**
     * 读取活动网络 ID。
     *
     * @return 活动网络 ID，可空。
     */
    fun activeNetworkId(): Long? {
        val value = activeNetworkIdRef.get()
        return value.takeIf { it != 0L }
    }

    /**
     * 设置 Socket 保护器。
     *
     * @param protector Socket 保护器，可空。
     */
    fun setSocketProtector(protector: ((DatagramSocket) -> Boolean)?) {
        socketProtectorRef.set(protector)
    }

    /**
     * 获取当前 Socket 保护器。
     *
     * @return Socket 保护器，可空。
     */
    fun socketProtector(): ((DatagramSocket) -> Boolean)? = socketProtectorRef.get()

    /**
     * 设置网络配置回调。
     *
     * @param callback 配置回调，可空。
     */
    fun setNetworkConfigCallback(
        callback: ((Long, VirtualNetworkConfigOperation, VirtualNetworkConfig?) -> Unit)?,
    ) {
        networkConfigCallbackRef.set(callback)
    }

    /**
     * 读取累计上行字节数。
     *
     * @return 上行字节数。
     */
    fun txBytes(): Long = txBytes.get()

    /**
     * 读取累计下行字节数。
     *
     * @return 下行字节数。
     */
    fun rxBytes(): Long = rxBytes.get()

    /**
     * 重置流量计数。
     */
    fun resetTrafficStats() {
        txBytes.set(0L)
        rxBytes.set(0L)
    }

    /**
     * 运行 Node 后台任务循环。
     *
     * 关键逻辑：
     * 1. 严格按 SDK 返回的 deadline 调度；
     * 2. 当 runtime 尚未就绪时降频等待，避免空转耗电。
     */
    private fun runBackgroundTaskLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val runtime = runtimeRef.get()
            if (runtime == null) {
                // 无 runtime 时不做固定轮询，等待绑定事件唤醒即可。
                if (!waitBackgroundInterruptibly(timeoutMs = null)) {
                    break
                }
                continue
            }

            val now = System.currentTimeMillis()
            val deadline = nextBackgroundDeadlineMs.get()
            if (deadline > now) {
                if (!waitBackgroundInterruptibly(deadline - now)) {
                    break
                }
                continue
            }

            val nextDeadline = longArrayOf(0L)
            val result = runtime.processBackgroundTasks(now, nextDeadline)
            updateNextBackgroundDeadline(nextDeadline[0])
            if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                Log.w(TAG, "processBackgroundTasks returned $result")
                if (!waitBackgroundInterruptibly(ERROR_RETRY_DELAY_MS)) {
                    break
                }
            }
        }
    }

    /**
     * 更新后台任务 deadline，并在有必要时唤醒循环线程。
     *
     * 关键逻辑：
     * 1. 新 deadline 更早时立刻唤醒，减少延迟；
     * 2. 切换到 0（立即可执行）时立刻唤醒，避免等待超时；
     * 3. 从 0 变为非 0 时也唤醒，确保新调度生效。
     */
    private fun updateNextBackgroundDeadline(deadlineMs: Long) {
        val previous = nextBackgroundDeadlineMs.getAndSet(deadlineMs)
        if (previous == 0L || deadlineMs == 0L || deadlineMs < previous) {
            notifyBackgroundLoop()
        }
    }

    /** runtime/deadline 变化时唤醒后台循环。 */
    private fun notifyBackgroundLoop() {
        backgroundWakeLock.withLock {
            backgroundWakeCondition.signalAll()
        }
    }

    /**
     * TUN 输入流变化时唤醒等待线程。
     */
    private fun notifyTunInputChanged() {
        tunInputWakeLock.withLock {
            tunInputWakeCondition.signalAll()
        }
    }

    /**
     * 处理来自 ZeroTier 的 ARP 帧。
     *
     * 关键逻辑：
     * 1. 维护“源 IPv4 -> 源 MAC”邻居映射；
     * 2. 当收到发往本机 IPv4 的 ARP 请求时，主动回 ARP Reply（对齐老项目）。
     */
    private fun handleIncomingArpFrame(
        networkId: Long,
        srcMac: Long,
        frameData: ByteArray,
    ) {
        val arp = parseArpFrame(frameData) ?: return
        putIpv4MacMapping(networkId = networkId, ipv4 = arp.senderIpv4, mac = arp.senderMac)

        // 仅对“请求本机地址”的 ARP Request 回复。
        if (arp.operation != ARP_OPERATION_REQUEST) {
            return
        }
        val runtime = runtimeRef.get() ?: return
        val config = runtime.networkConfig(networkId) ?: return
        val localIpv4 = resolveAssignedIpv4(config.assignedAddresses.orEmpty()) ?: return
        if (arp.targetIpv4 != localIpv4) {
            return
        }

        val reply = buildArpReplyPayload(
            localMac = config.mac,
            localIpv4 = localIpv4,
            requesterMac = arp.senderMac,
            requesterIpv4 = arp.senderIpv4,
        )
        val nextDeadline = longArrayOf(0L)
        val result = runtime.processVirtualNetworkFrame(
            now = System.currentTimeMillis(),
            networkId = networkId,
            sourceMac = config.mac,
            destMac = srcMac,
            etherType = ETHER_TYPE_ARP,
            vlanId = 0,
            frameData = reply,
            nextBackgroundTaskDeadline = nextDeadline,
        )
        updateNextBackgroundDeadline(nextDeadline[0])
        if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
            Log.w(TAG, "ARP reply send failed. result=$result")
        } else {
            ChainLog.i(
                TAG,
                "ARP应答已发送 networkId=$networkId target=${ipv4ToString(arp.senderIpv4)} mac=${macToString(srcMac)}",
            )
        }
    }

    /**
     * 写入 IPv4 邻居映射。
     */
    private fun putIpv4MacMapping(
        networkId: Long,
        ipv4: Int,
        mac: Long,
    ) {
        // 只记录“同二层网段”的邻居，避免把公网目的地址写进 ARP 邻居表。
        // 这类公网地址并不是可直接 ARP 的邻居，只会污染日志和邻居缓存。
        val localIpv4WithPrefix = resolveLocalIpv4WithPrefix(networkId) ?: return
        if (!isInSameSubnet(ipv4, localIpv4WithPrefix.first, localIpv4WithPrefix.second)) {
            return
        }
        ipv4MacTableLock.withLock {
            val table = ipv4MacTable.getOrPut(networkId) { mutableMapOf() }
            val previous = table.put(ipv4, mac)
            if (previous == null || previous != mac) {
                ChainLog.d(
                    TAG,
                    "记录IPv4邻居映射 networkId=$networkId ip=${ipv4ToString(ipv4)} mac=${macToString(mac)}",
                )
            }
        }
    }

    /**
     * 读取 IPv4 邻居映射。
     */
    private fun findIpv4Mac(
        networkId: Long,
        ipv4: Int,
    ): Long? {
        return ipv4MacTableLock.withLock {
            ipv4MacTable[networkId]?.get(ipv4)
        }
    }

    /**
     * 从 SDK 地址列表中提取本机 IPv4。
     */
    private fun resolveAssignedIpv4(addresses: Array<out InetSocketAddress>): Int? {
        val host = addresses.firstOrNull { it.address is Inet4Address }?.address as? Inet4Address
        return host?.let { ipv4ToInt(it.address) }
    }

    /**
     * 读取本机在指定网络上的 IPv4 与前缀。
     */
    private fun resolveLocalIpv4WithPrefix(networkId: Long): Pair<Int, Int>? {
        val runtime = runtimeRef.get() ?: return null
        val config = runtime.networkConfig(networkId) ?: return null
        val assigned = config.assignedAddresses.orEmpty()
        for (address in assigned) {
            val ipv4 = address.address as? Inet4Address ?: continue
            val prefix = address.port.coerceIn(0, IPV4_MAX_PREFIX)
            return ipv4ToInt(ipv4.address) to prefix
        }
        return null
    }

    /**
     * 判断目标 IPv4 是否属于本地二层可达网段。
     */
    private fun isInSameSubnet(ipv4: Int, localIpv4: Int, prefix: Int): Boolean {
        val normalizedPrefix = prefix.coerceIn(0, IPV4_MAX_PREFIX)
        if (normalizedPrefix == 0) {
            return true
        }
        val mask = if (normalizedPrefix == IPV4_MAX_PREFIX) {
            -1
        } else {
            (-1 shl (IPV4_MAX_PREFIX - normalizedPrefix))
        }
        return (ipv4 and mask) == (localIpv4 and mask)
    }

    /**
     * 构造 ARP Request 负载。
     */
    private fun buildArpRequestPayload(
        localMac: Long,
        localIpv4: Int,
        targetIpv4: Int,
    ): ByteArray {
        val payload = ByteArray(ARP_PAYLOAD_SIZE_BYTES)
        payload[0] = 0x00
        payload[1] = 0x01 // Hardware type: Ethernet
        payload[2] = 0x08.toByte()
        payload[3] = 0x00 // Protocol type: IPv4
        payload[4] = 0x06 // MAC length
        payload[5] = 0x04 // IPv4 length
        payload[6] = 0x00
        payload[7] = ARP_OPERATION_REQUEST.toByte()

        writeMacTo(payload, 8, localMac)
        writeIpv4To(payload, 14, localIpv4)
        // Target MAC unknown => all zero
        writeIpv4To(payload, 24, targetIpv4)
        return payload
    }

    /**
     * 构造 ARP Reply 负载。
     */
    private fun buildArpReplyPayload(
        localMac: Long,
        localIpv4: Int,
        requesterMac: Long,
        requesterIpv4: Int,
    ): ByteArray {
        val payload = ByteArray(ARP_PAYLOAD_SIZE_BYTES)
        payload[0] = 0x00
        payload[1] = 0x01 // Hardware type: Ethernet
        payload[2] = 0x08.toByte()
        payload[3] = 0x00 // Protocol type: IPv4
        payload[4] = 0x06 // MAC length
        payload[5] = 0x04 // IPv4 length
        payload[6] = 0x00
        payload[7] = ARP_OPERATION_REPLY.toByte()

        writeMacTo(payload, 8, localMac)
        writeIpv4To(payload, 14, localIpv4)
        writeMacTo(payload, 18, requesterMac)
        writeIpv4To(payload, 24, requesterIpv4)
        return payload
    }

    /**
     * 解析 ARP 负载。
     */
    private fun parseArpFrame(frameData: ByteArray): ArpFrame? {
        if (frameData.size < ARP_PAYLOAD_SIZE_BYTES) {
            return null
        }
        val operation = ((frameData[6].toInt() and 0xFF) shl 8) or (frameData[7].toInt() and 0xFF)
        val senderMac = readMacFrom(frameData, 8)
        val senderIpv4 = readIpv4From(frameData, 14)
        val targetIpv4 = readIpv4From(frameData, 24)
        return ArpFrame(
            operation = operation,
            senderMac = senderMac,
            senderIpv4 = senderIpv4,
            targetIpv4 = targetIpv4,
        )
    }

    /**
     * 从 IPv4 包提取源地址（int）。
     */
    private fun extractIpv4Source(packet: ByteArray): Int? {
        if (packet.size < IPV4_HEADER_MIN_SIZE_BYTES) {
            return null
        }
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) {
            return null
        }
        return readIpv4From(packet, 12)
    }

    /**
     * byte[4] IPv4 转 int。
     */
    private fun ipv4ToInt(address: ByteArray): Int {
        return ((address[0].toInt() and 0xFF) shl 24) or
            ((address[1].toInt() and 0xFF) shl 16) or
            ((address[2].toInt() and 0xFF) shl 8) or
            (address[3].toInt() and 0xFF)
    }

    /**
     * 从字节数组读取 IPv4（int）。
     */
    private fun readIpv4From(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }

    /**
     * 将 IPv4（int）写入字节数组。
     */
    private fun writeIpv4To(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * 从字节数组读取 MAC（低 6 字节）。
     */
    private fun readMacFrom(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until MAC_LENGTH_BYTES) {
            value = (value shl 8) or (buffer[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    /**
     * IPv4 int 转可读字符串。
     */
    private fun ipv4ToString(ipv4: Int): String {
        return "${(ipv4 ushr 24) and 0xFF}.${(ipv4 ushr 16) and 0xFF}.${(ipv4 ushr 8) and 0xFF}.${ipv4 and 0xFF}"
    }

    /**
     * MAC 转可读字符串。
     */
    private fun macToString(mac: Long): String {
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            (mac ushr 40) and 0xFF,
            (mac ushr 32) and 0xFF,
            (mac ushr 24) and 0xFF,
            (mac ushr 16) and 0xFF,
            (mac ushr 8) and 0xFF,
            mac and 0xFF,
        )
    }

    /**
     * 将 MAC 写入字节数组（低 6 字节）。
     */
    private fun writeMacTo(buffer: ByteArray, offset: Int, mac: Long) {
        for (index in 0 until MAC_LENGTH_BYTES) {
            val shift = (MAC_LENGTH_BYTES - 1 - index) * 8
            buffer[offset + index] = ((mac ushr shift) and 0xFFL).toByte()
        }
    }

    /**
     * 等待 TUN 输入流可用（或已切换到新实例）。
     *
     * @param currentInput 当前正在使用的输入流，可空。
     * @return 可用输入流；若线程中断则返回 null。
     */
    private fun awaitTunInputAvailableInterruptibly(currentInput: FileInputStream?): FileInputStream? {
        return try {
            tunInputWakeLock.withLock {
                while (!Thread.currentThread().isInterrupted) {
                    val latest = tunInputRef.get()
                    // currentInput 为 null：表示尚未建立输入流，只接受“首次可用”。
                    if (latest != null && currentInput == null) {
                        return latest
                    }
                    // 输入流对象已切换：直接返回新对象。
                    if (latest != null && latest !== currentInput) {
                        return latest
                    }
                    tunInputWakeCondition.await()
                }
            }
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * 后台任务循环等待：支持“无限等待唤醒”与“限时等待唤醒”。
     *
     * @param timeoutMs 等待超时（毫秒）；为 null 时表示无限等待。
     * @return true 表示正常被唤醒或超时返回，false 表示线程中断。
     */
    private fun waitBackgroundInterruptibly(timeoutMs: Long?): Boolean {
        return try {
            backgroundWakeLock.withLock {
                when {
                    timeoutMs == null -> backgroundWakeCondition.await()
                    timeoutMs > 0L -> backgroundWakeCondition.await(timeoutMs, TimeUnit.MILLISECONDS)
                    else -> Unit
                }
            }
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private companion object {
        /** 日志标签。 */
        private const val TAG = "RuntimeContext"

        /** 异常重试等待时长。 */
        private const val ERROR_RETRY_DELAY_MS = 500L

        /** 以太类型：ARP。 */
        private const val ETHER_TYPE_ARP = 0x0806

        /** 以太类型：IPv4。 */
        private const val ETHER_TYPE_IPV4 = 0x0800

        /** 以太类型：IPv6。 */
        private const val ETHER_TYPE_IPV6 = 0x86DD

        /** ARP 操作码：请求。 */
        private const val ARP_OPERATION_REQUEST = 1

        /** ARP 操作码：应答。 */
        private const val ARP_OPERATION_REPLY = 2

        /** ARP 负载长度。 */
        private const val ARP_PAYLOAD_SIZE_BYTES = 28

        /** IPv4 头最小长度。 */
        private const val IPV4_HEADER_MIN_SIZE_BYTES = 20
        private const val IPV4_MAX_PREFIX = 32

        /** MAC 长度。 */
        private const val MAC_LENGTH_BYTES = 6

        /** 是否开启 Node trace 原始日志。 */
        private const val NODE_TRACE_LOG_ENABLED = false
    }

    /**
     * ARP 帧解析结构。
     */
    private data class ArpFrame(
        val operation: Int,
        val senderMac: Long,
        val senderIpv4: Int,
        val targetIpv4: Int,
    )
}

/**
 * Node DataStore 实现。
 *
 * 说明：
 * 保持与老项目一致的对象名语义，支持子目录与 planet hook。
 */
private class NodeDataStore(
    private val context: Context,
    private val settingsStateHolder: SettingsStateHolder,
    private val planetFileStore: PlanetFileStore,
) : DataStoreGetListener, DataStorePutListener {

    override fun onDataStoreGet(
        name: String,
        out_buffer: ByteArray,
    ): Long {
        val useCustomPlanet = settingsStateHolder.currentState().planetUseCustom
        if (shouldBypassLocalCacheInOfficialMode(name, useCustomPlanet)) {
            if (name == PlanetFileStore.FILE_PLANET) {
                ChainLog.i(
                    DATA_STORE_TAG,
                    "Planet数据源=OFFICIAL（不读取本地planet文件）",
                )
            } else {
                ChainLog.i(
                    DATA_STORE_TAG,
                    "官方链路启动，跳过本地缓存对象 name=$name",
                )
            }
            return -1L
        }

        if (name == PlanetFileStore.FILE_PLANET) {
            val hasCustomPlanetFile = planetFileStore.hasCustomPlanetFile()
            if (hasCustomPlanetFile) {
                ChainLog.i(
                    DATA_STORE_TAG,
                    "Planet数据源=NON_OFFICIAL",
                )
                return readDataStoreFile(
                    name = PlanetFileStore.FILE_CUSTOM_PLANET,
                    outBuffer = out_buffer,
                )
            }
            ChainLog.w(
                DATA_STORE_TAG,
                "Planet数据源=OFFICIAL（自定义已开启但文件不存在）",
            )
            return -1L
        }

        val targetName = resolveTargetName(name)
        return readDataStoreFile(
            name = targetName,
            outBuffer = out_buffer,
        )
    }

    override fun onDataStorePut(
        name: String,
        buffer: ByteArray,
        secure: Boolean,
    ): Int {
        val targetName = resolveTargetName(name)
        return runCatching {
            val file = resolveFile(targetName)
            file.parentFile?.mkdirs()
            FileOutputStream(file, false).use { output ->
                output.write(buffer)
                output.flush()
            }
            if (secure) {
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
            0
        }.getOrElse {
            Log.e(DATA_STORE_TAG, "Write failed. name=$targetName", it)
            -1
        }
    }

    override fun onDelete(name: String): Int {
        val targetName = resolveTargetName(name)
        return runCatching {
            val file = resolveFile(targetName)
            if (!file.exists() || file.delete()) 0 else 1
        }.getOrElse {
            Log.e(DATA_STORE_TAG, "Delete failed. name=$targetName", it)
            1
        }
    }

    /**
     * 解析 DataStore 对象名。
     *
     * @param rawName 原始对象名。
     * @return 目标文件名。
     */
    private fun resolveTargetName(rawName: String): String {
        if (rawName == PlanetFileStore.FILE_PLANET && settingsStateHolder.currentState().planetUseCustom) {
            if (planetFileStore.hasCustomPlanetFile()) {
                return PlanetFileStore.FILE_CUSTOM_PLANET
            }
        }
        return rawName
    }

    /**
     * 按对象名读取 DataStore 文件。
     *
     * @param name DataStore 对象名。
     * @param outBuffer 读取缓冲区。
     * @return 读取字节数；不存在返回 -1；异常返回 -2。
     */
    private fun readDataStoreFile(
        name: String,
        outBuffer: ByteArray,
    ): Long {
        return runCatching {
            val file = resolveFile(name)
            if (!file.exists()) {
                return@runCatching -1L
            }
            FileInputStream(file).use { input ->
                input.read(outBuffer).toLong()
            }
        }.getOrElse {
            Log.e(DATA_STORE_TAG, "Read failed. name=$name", it)
            -2L
        }
    }

    /**
     * 解析对象文件路径。
     *
     * @param name 对象名。
     * @return 目标文件。
     */
    private fun resolveFile(name: String): File {
        return File(context.filesDir, name)
    }

    /**
     * 官方链路模式下是否跳过本地缓存对象读取。
     *
     * 业务语义：
     * 1. 当 `planetUseCustom=false` 时，运行时必须依赖 SDK 官方发现链路；
     * 2. 旧会话写入的 `peers/moons/networks` 缓存可能把节点直接带回自定义控制平面；
     * 3. 这里仅“跳过读取”，不删除文件，满足“保留本地文件”的要求。
     */
    private fun shouldBypassLocalCacheInOfficialMode(name: String, useCustomPlanet: Boolean): Boolean {
        if (useCustomPlanet) {
            return false
        }
        if (name == PlanetFileStore.FILE_PLANET) {
            return true
        }
        return name.startsWith("peers.d/") ||
            name.startsWith("moons.d/") ||
            name.startsWith("networks.d/")
    }

    private companion object {
        /** 日志标签。 */
        private const val DATA_STORE_TAG = "NodeDataStore"
    }
}

/**
 * Node PacketSender 实现。
 */
private class NodePacketSender : PacketSender {

    /** 当前 UDP Socket。 */
    private val socketRef: AtomicReference<DatagramSocket?> = AtomicReference(null)

    /**
     * 绑定 Socket。
     *
     * @param socket UDP 套接字。
     */
    fun attachSocket(socket: DatagramSocket?) {
        socketRef.set(socket)
    }

    override fun onSendPacketRequested(
        localSocket: Long,
        remoteAddr: InetSocketAddress,
        packetData: ByteArray,
        ttl: Int,
    ): Int {
        val socket = socketRef.get() ?: return -1
        return runCatching {
            socket.send(DatagramPacket(packetData, packetData.size, remoteAddr))
            0
        }.getOrElse {
            Log.w("NodePacketSender", "Send packet failed. localSocket=$localSocket ttl=$ttl", it)
            -1
        }
    }
}

/**
 * UDP Bridge 实现。
 */
private class NodeUdpBridge(
    private val socket: DatagramSocket,
    private val runtimeProvider: () -> NodeKernelRuntimeCore?,
    private val onNextBackgroundDeadline: (Long) -> Unit,
) : KernelUdpBridge() {

    /** 当前 Node 引用（由内核启动流程在启动后回绑）。 */
    @Volatile
    private var nodeBound: Boolean = false

    override fun bindNode(node: Node) {
        nodeBound = true
    }

    override fun run() {
        val packetBuffer = ByteArray(MAX_UDP_PACKET_SIZE)
        while (!Thread.currentThread().isInterrupted) {
            val packet = DatagramPacket(packetBuffer, packetBuffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Throwable) {
                Log.w(TAG, "UDP receive failed", error)
                continue
            }

            if (!nodeBound) {
                continue
            }

            val runtime = runtimeProvider() ?: continue
            val payload = packet.data.copyOf(packet.length)
            val nextDeadline = longArrayOf(0L)
            val result = runtime.processWirePacket(
                now = System.currentTimeMillis(),
                localSocket = -1L,
                remoteAddress = InetSocketAddress(packet.address, packet.port),
                packetData = payload,
                nextBackgroundTaskDeadline = nextDeadline,
            )
            onNextBackgroundDeadline(nextDeadline[0])
            if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                Log.w(TAG, "processWirePacket returned $result")
            }
        }
    }

    private companion object {
        /** 日志标签。 */
        private const val TAG = "NodeUdpBridge"

        /** UDP 包缓冲区大小。 */
        private const val MAX_UDP_PACKET_SIZE = 16 * 1024
    }
}

/**
 * TUN Bridge 实现。
 *
 * 说明：
 * 该桥接器在单线程中持续读取 TUN 输入并交给 Node 处理。
 */
private class NodeTunTapBridge(
    private val networkId: Long,
    private val runtimeProvider: () -> NodeKernelRuntimeCore?,
    private val inputProvider: () -> FileInputStream?,
    private val awaitInputProvider: (FileInputStream?) -> FileInputStream?,
    private val findIpv4Mac: (Long, Int) -> Long?,
    private val buildArpRequestPayload: (Long, Int, Int) -> ByteArray,
    private val resolveAssignedIpv4: (Array<out InetSocketAddress>) -> Int?,
    private val onTxBytes: (Long) -> Unit,
    private val onNextBackgroundDeadline: (Long) -> Unit,
) : KernelTunTapBridge() {

    /**
     * ARP 请求节流表（targetIpv4 -> lastRequestAtMs）。
     *
     * 说明：
     * 1. 当邻居未解析成功时，原逻辑会对每个待转发包都发 ARP；
     * 2. 这会在高流量场景造成 CPU/日志 I/O 持续升高，导致发热；
     * 3. 节流后同一目标在窗口内仅发一次 ARP，不影响最终连通语义。
     */
    private val arpRequestLastSentAtMs: MutableMap<Int, Long> = mutableMapOf()

    /** 最近一次 ARP 采样日志时间。 */
    private var lastArpTraceAtMs: Long = 0L

    /** 桥接线程。 */
    private val workerThread: Thread = Thread(
        {
            runBridgeLoop()
        },
        "NodeTunTapBridge-$networkId",
    ).apply {
        isDaemon = true
        start()
    }

    override fun bindNode(node: Node) = Unit

    override fun isRunning(): Boolean = workerThread.isAlive

    override fun interrupt() {
        workerThread.interrupt()
    }

    @Throws(InterruptedException::class)
    override fun join() {
        workerThread.join()
    }

    @Throws(InterruptedException::class)
    override fun join(timeoutMs: Long) {
        workerThread.join(timeoutMs)
    }

    /**
     * TUN 读包循环。
     */
    private fun runBridgeLoop() {
        val frameBuffer = ByteArray(MAX_TUN_FRAME_SIZE)
        while (!Thread.currentThread().isInterrupted) {
            val input = inputProvider() ?: awaitInputProvider(null) ?: break

            val length = runCatching { input.read(frameBuffer) }.getOrElse {
                // 读取异常统一按“当前输入流不可用”处理，
                // 由下方 length<0 分支负责等待输入流切换，避免重复等待两次。
                return@getOrElse -1
            }
            if (length < 0) {
                // EOF：等待输入流更新，避免空转。
                awaitInputProvider(input) ?: break
                continue
            }
            if (length == 0) continue

            val runtime = runtimeProvider() ?: continue
            val packet = frameBuffer.copyOf(length)
            val ipVersion = ((packet[0].toInt() ushr 4) and 0x0F)
            if (ipVersion != 4) {
                // 当前先对齐老项目 IPv4 主链路，IPv6 后续补 NDP 表再打通。
                continue
            }
            if (packet.size < IPV4_HEADER_MIN_SIZE_BYTES) {
                continue
            }
            val sourceIpv4 = readIpv4From(packet, IPV4_SOURCE_OFFSET)
            val destIpv4 = readIpv4From(packet, 16)
            val config = runtime.networkConfig(networkId) ?: continue
            val localMac = config.mac
            val localIpv4WithPrefix = resolveAssignedIpv4WithPrefix(config.assignedAddresses.orEmpty()) ?: continue
            val forwardedTargetIpv4 = resolveForwardedTargetIpv4(
                sourceIpv4 = sourceIpv4,
                destIpv4 = destIpv4,
                localPrefix = localIpv4WithPrefix.second,
                routes = config.routes.orEmpty(),
            )
            val isIpv4Multicast = isIpv4Multicast(forwardedTargetIpv4)
            val destMac = if (isIpv4Multicast) {
                ipv4MulticastToMac(forwardedTargetIpv4)
            } else {
                findIpv4Mac(networkId, forwardedTargetIpv4)
            }

            onTxBytes(length.toLong())
            val nextDeadline = longArrayOf(0L)
            if (destMac != null) {
                // 邻居已命中，清理该目标的 ARP 节流状态，避免后续切换时误抑制首个 ARP。
                arpRequestLastSentAtMs.remove(forwardedTargetIpv4)
                val result = runtime.processVirtualNetworkFrame(
                    now = System.currentTimeMillis(),
                    networkId = networkId,
                    sourceMac = localMac,
                    destMac = destMac,
                    etherType = ETHER_TYPE_IPV4,
                    vlanId = 0,
                    frameData = packet,
                    nextBackgroundTaskDeadline = nextDeadline,
                )
                onNextBackgroundDeadline(nextDeadline[0])
                if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                    Log.w(TAG, "processVirtualNetworkFrame returned $result")
                }
                continue
            }
            val nowMs = System.currentTimeMillis()
            if (!shouldSendArpRequest(forwardedTargetIpv4, nowMs)) {
                continue
            }

            val localIpv4 = localIpv4WithPrefix.first
            val arpRequest = buildArpRequestPayload(localMac, localIpv4, forwardedTargetIpv4)
            val arpResult = runtime.processVirtualNetworkFrame(
                now = nowMs,
                networkId = networkId,
                sourceMac = localMac,
                destMac = BROADCAST_MAC,
                etherType = ETHER_TYPE_ARP,
                vlanId = 0,
                frameData = arpRequest,
                nextBackgroundTaskDeadline = nextDeadline,
            )
            onNextBackgroundDeadline(nextDeadline[0])
            if (arpResult != ResultCode.RESULT_OK && arpResult != ResultCode.RESULT_OK_IGNORED) {
                Log.w(TAG, "send ARP request failed result=$arpResult")
            } else {
                traceArpSample(forwardedTargetIpv4, destIpv4, nowMs)
            }
        }
    }

    /**
     * 判断当前是否允许发送 ARP 请求。
     */
    private fun shouldSendArpRequest(
        targetIpv4: Int,
        nowMs: Long,
    ): Boolean {
        val lastSentAtMs = arpRequestLastSentAtMs[targetIpv4]
        if (lastSentAtMs != null && (nowMs - lastSentAtMs) < ARP_REQUEST_THROTTLE_WINDOW_MS) {
            return false
        }
        arpRequestLastSentAtMs[targetIpv4] = nowMs
        return true
    }

    /**
     * ARP 发送采样日志（避免每次都写日志造成 I/O 压力）。
     */
    private fun traceArpSample(
        forwardedTargetIpv4: Int,
        originalDestIpv4: Int,
        nowMs: Long,
    ) {
        if (nowMs - lastArpTraceAtMs < ARP_TRACE_SAMPLE_INTERVAL_MS) {
            return
        }
        lastArpTraceAtMs = nowMs
        ChainLog.d(
            TAG,
            "发送ARP请求 networkId=$networkId targetIpv4=${ipv4ToString(forwardedTargetIpv4)} 原始目标=${ipv4ToString(originalDestIpv4)}",
        )
    }

    /**
     * 解析本地分配的 IPv4 地址及前缀。
     */
    private fun resolveAssignedIpv4WithPrefix(
        addresses: Array<out InetSocketAddress>,
    ): Pair<Int, Int>? {
        for (address in addresses) {
            val host = address.address as? Inet4Address ?: continue
            val prefix = address.port.coerceIn(0, IPV4_MAX_PREFIX)
            return inet4ToInt(host) to prefix
        }
        return null
    }

    /**
     * 依据受管路由与网关决定本次应解析的 ARP 目标。
     *
     * 对齐老项目逻辑：
     * 1. 先按目标地址匹配受管路由；
     * 2. 匹配到网关后，仅当“源/目的不在同一本地网段”时改为解析网关；
     * 3. 其余场景维持解析原始目的地址。
     */
    private fun resolveForwardedTargetIpv4(
        sourceIpv4: Int,
        destIpv4: Int,
        localPrefix: Int,
        routes: Array<out com.zerotier.sdk.VirtualNetworkRoute>,
    ): Int {
        for (route in routes) {
            val target = route.target ?: continue
            val targetAddress = target.address as? Inet4Address ?: continue
            val targetPrefix = target.port.coerceIn(0, IPV4_MAX_PREFIX)
            if (!isInSubnet(destIpv4, inet4ToInt(targetAddress), targetPrefix)) {
                continue
            }
            val gateway = route.via?.address as? Inet4Address ?: continue
            val sourceAndDestInSameLocalSubnet = isInSubnet(destIpv4, sourceIpv4, localPrefix)
            if (!sourceAndDestInSameLocalSubnet) {
                return inet4ToInt(gateway)
            }
            return destIpv4
        }
        return destIpv4
    }

    /**
     * 判断两个 IPv4 是否在同一前缀网段。
     */
    private fun isInSubnet(ipv4: Int, networkOrPeer: Int, prefix: Int): Boolean {
        val normalizedPrefix = prefix.coerceIn(0, IPV4_MAX_PREFIX)
        if (normalizedPrefix == 0) {
            return true
        }
        val mask = if (normalizedPrefix == IPV4_MAX_PREFIX) {
            -1
        } else {
            (-1 shl (IPV4_MAX_PREFIX - normalizedPrefix))
        }
        return (ipv4 and mask) == (networkOrPeer and mask)
    }

    /**
     * 将 IPv4 组播地址映射为以太网组播 MAC。
     */
    private fun ipv4MulticastToMac(ipv4: Int): Long {
        val b1 = 0x01L
        val b2 = 0x00L
        val b3 = 0x5eL
        val b4 = ((ipv4 ushr 16) and 0x7F).toLong()
        val b5 = ((ipv4 ushr 8) and 0xFF).toLong()
        val b6 = (ipv4 and 0xFF).toLong()
        return (b1 shl 40) or
            (b2 shl 32) or
            (b3 shl 24) or
            (b4 shl 16) or
            (b5 shl 8) or
            b6
    }

    /**
     * 判断是否为 IPv4 组播地址（224.0.0.0/4）。
     */
    private fun isIpv4Multicast(ipv4: Int): Boolean {
        val firstOctet = (ipv4 ushr 24) and 0xFF
        return firstOctet in 224..239
    }

    /**
     * Inet4Address 转 int。
     */
    private fun inet4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    /**
     * 从字节数组读取 IPv4（int）。
     */
    private fun readIpv4From(
        buffer: ByteArray,
        offset: Int,
    ): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }

    /**
     * IPv4 int 转可读字符串。
     */
    private fun ipv4ToString(ipv4: Int): String {
        return "${(ipv4 ushr 24) and 0xFF}.${(ipv4 ushr 16) and 0xFF}.${(ipv4 ushr 8) and 0xFF}.${ipv4 and 0xFF}"
    }

    private companion object {
        /** 日志标签。 */
        private const val TAG = "NodeTunTapBridge"

        /** 以太类型：ARP。 */
        private const val ETHER_TYPE_ARP = 0x0806

        /** 以太类型：IPv4。 */
        private const val ETHER_TYPE_IPV4 = 0x0800

        /** IPv4 头最小长度。 */
        private const val IPV4_HEADER_MIN_SIZE_BYTES = 20
        private const val IPV4_SOURCE_OFFSET = 12
        private const val IPV4_MAX_PREFIX = 32

        /** 广播 MAC。 */
        private const val BROADCAST_MAC = 0xFFFF_FFFF_FFFFL

        /** 同一目标 ARP 请求节流窗口。 */
        private const val ARP_REQUEST_THROTTLE_WINDOW_MS = 1_500L

        /** ARP 日志采样窗口。 */
        private const val ARP_TRACE_SAMPLE_INTERVAL_MS = 2_000L

        /** TUN 帧最大长度。 */
        private const val MAX_TUN_FRAME_SIZE = 32 * 1024
    }
}
