package io.github.jimmy.ztlink.service.runtime.kernel

import android.os.ParcelFileDescriptor
import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.PathChecker
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkFrameListener
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket

/**
 * 内核运行时启动参数。
 *
 * 说明：
 * 1. 该配置只用于 `NodeKernelRuntimeCore.start(...)`；
 * 2. 字段按“复用已有资源 + 首次创建资源”两类组织；
 * 3. 运行时启动所需的 SDK 回调、桥接工厂都在这里一次性提供。
 */
data class KernelRuntimeStartConfig(
    val networkId: Long,
    val existingSocket: DatagramSocket? = null,
    val existingUdpBridge: KernelUdpBridge? = null,
    val existingTunTapBridge: KernelTunTapBridge? = null,
    val existingNode: Node? = null,
    val existingVpnThread: Thread? = null,
    val existingUdpThread: Thread? = null,
    val existingVpnSocket: ParcelFileDescriptor? = null,
    val existingInput: FileInputStream? = null,
    val existingOutput: FileOutputStream? = null,
    val dataStoreGetListener: DataStoreGetListener,
    val dataStorePutListener: DataStorePutListener,
    val packetSender: PacketSender,
    val eventListener: EventListener,
    val frameListener: VirtualNetworkFrameListener,
    val configListener: VirtualNetworkConfigListener,
    val pathChecker: PathChecker? = null,
    val vpnRunnable: Runnable,
    val socketProtector: ((DatagramSocket) -> Boolean)? = null,
    val udpBridgeFactory: ((DatagramSocket) -> KernelUdpBridge)? = null,
    val tunTapBridgeFactory: ((Long) -> KernelTunTapBridge)? = null,
    val bindPort: Int = 9994,
    val socketTimeoutMs: Int = 15_000,
)
