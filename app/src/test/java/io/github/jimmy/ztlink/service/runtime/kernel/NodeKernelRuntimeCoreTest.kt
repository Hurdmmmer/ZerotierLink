package io.github.jimmy.ztlink.service.runtime.kernel

import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.Event
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkFrameListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch

class NodeKernelRuntimeCoreTest {

    @Test
    fun start_whenSocketProtectionFails_closesNewSocketAndThrows() {
        var protectedSocket: DatagramSocket? = null
        val error = try {
            NodeKernelRuntimeCore.start(
                createStartConfig(
                    socketProtector = { socket ->
                        protectedSocket = socket
                        false
                    },
                ),
            )
            fail("Expected runtime start to fail when VpnService.protect returns false")
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("protect returned false"))
        assertTrue(protectedSocket?.isClosed == true)
    }

    @Test
    fun readKernelState_reportsActualResourceLiveness() {
        val releaseWorkers = CountDownLatch(1)
        val vpnThread = waitingThread("test-vpn-worker", releaseWorkers)
        val udpThread = waitingThread("test-udp-worker", releaseWorkers)
        val socket = DatagramSocket()
        val tunTapBridge = TestTunTapBridge(running = true)
        val runtime = NodeKernelRuntimeCore(
            socket = socket,
            udpBridge = KernelUdpBridge(),
            tunTapBridge = tunTapBridge,
            node = null,
            vpnThread = vpnThread,
            udpThread = udpThread,
            vpnSocket = null,
            input = null,
            output = null,
            nodeCreatedAtStart = false,
            socketProtected = true,
        )

        try {
            val runningState = runtime.readKernelState()
            assertTrue(runningState.socketOpen)
            assertTrue(runningState.socketProtected)
            assertTrue(runningState.vpnThreadAlive)
            assertTrue(runningState.udpThreadAlive)
            assertTrue(runningState.tunTapBridgeAlive)
            assertFalse(runningState.vpnFdValid)
            assertFalse(runningState.kernelAlive)
            assertFalse(runningState.tunnelAlive)

            socket.close()
            releaseWorkers.countDown()
            vpnThread.join()
            udpThread.join()
            tunTapBridge.running = false

            val stoppedState = runtime.readKernelState()
            assertFalse(stoppedState.socketOpen)
            assertFalse(stoppedState.vpnThreadAlive)
            assertFalse(stoppedState.udpThreadAlive)
            assertFalse(stoppedState.tunTapBridgeAlive)
        } finally {
            socket.close()
            releaseWorkers.countDown()
            vpnThread.interrupt()
            udpThread.interrupt()
        }
    }

    private fun createStartConfig(
        socketProtector: (DatagramSocket) -> Boolean,
    ): KernelRuntimeStartConfig {
        return KernelRuntimeStartConfig(
            networkId = 1L,
            dataStoreGetListener = DataStoreGetListener { _, _ -> -1L },
            dataStorePutListener = object : DataStorePutListener {
                override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean): Int = 0

                override fun onDelete(name: String): Int = 0
            },
            packetSender = PacketSender { _, _, _, _ -> 0 },
            eventListener = object : EventListener {
                override fun onEvent(event: Event) = Unit

                override fun onTrace(message: String) = Unit
            },
            frameListener = VirtualNetworkFrameListener { _, _, _, _, _, _ -> Unit },
            configListener = VirtualNetworkConfigListener { _, _, _ -> 0 },
            vpnRunnable = Runnable { },
            socketProtector = socketProtector,
            bindPort = 0,
        )
    }

    private fun waitingThread(
        name: String,
        releaseWorkers: CountDownLatch,
    ): Thread {
        return Thread(
            {
                try {
                    releaseWorkers.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            name,
        ).apply {
            isDaemon = true
            start()
        }
    }

    private class TestTunTapBridge(
        var running: Boolean,
    ) : KernelTunTapBridge() {
        override fun isRunning(): Boolean = running
    }
}
