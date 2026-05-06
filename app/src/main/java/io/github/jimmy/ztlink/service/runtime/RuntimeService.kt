package io.github.jimmy.ztlink.service.runtime

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.zerotier.sdk.Peer
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.util.StringUtils
import io.github.jimmy.ztlink.data.network.local.AppNodeDao
import io.github.jimmy.ztlink.data.network.local.AppNodeDbEntity
import io.github.jimmy.ztlink.data.settings.PlanetFileStore
import io.github.jimmy.ztlink.data.settings.SettingsStateHolder
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.model.network.NetworkId
import io.github.jimmy.ztlink.model.network.ZeroTierStatusMapper
import io.github.jimmy.ztlink.model.runtime.RuntimeMoonOrbit
import io.github.jimmy.ztlink.model.runtime.RuntimeNetworkInfo
import io.github.jimmy.ztlink.model.runtime.RuntimeNodeInfo
import io.github.jimmy.ztlink.model.runtime.RuntimeResult
import io.github.jimmy.ztlink.model.runtime.RuntimePeerInfo
import io.github.jimmy.ztlink.model.runtime.RuntimeResultCode
import io.github.jimmy.ztlink.model.runtime.NodeState
import io.github.jimmy.ztlink.model.runtime.VpnTunnelConfig
import io.github.jimmy.ztlink.model.service.ServiceError
import io.github.jimmy.ztlink.model.service.ServiceErrorCode
import io.github.jimmy.ztlink.service.AppWhitelistApplier
import io.github.jimmy.ztlink.service.runtime.kernel.KernelRuntimeStartConfig
import io.github.jimmy.ztlink.service.runtime.kernel.NodeKernelRuntimeCore
import io.github.jimmy.ztlink.service.runtime.kernel.NodeKernelState
import io.github.jimmy.ztlink.util.ChainLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime 服务。
 *
 * 该类覆盖老项目核心能力面：
 * 启停、入网/离网、隧道重配置、网络配置查询、Peer 查询、节点查询、Moon 入轨/退轨。
 *
 * 设计目标：
 * 1. 对外提供“受控能力”而不是裸 Node 访问；
 * 2. 对内统一管理 runtime 生命周期与隧道重配置；
 * 3. 保持与老项目职责边界一致：Node 能力归 Runtime，通知与事件归 Service。
 */
@Singleton
class RuntimeService @Inject constructor(
    private val runtimeContext: RuntimeContext,
    private val appNodeDao: AppNodeDao,
    private val appWhitelistApplier: AppWhitelistApplier,
    private val settingsStateHolder: SettingsStateHolder,
    private val planetFileStore: PlanetFileStore,
) {

    /** 运行时互斥锁，保证启停/重配置串行。 */
    private val runtimeLock: Mutex = Mutex()

    /** 当前 runtime 句柄。 */
    private val runtimeRef: AtomicReference<NodeKernelRuntimeCore?> = AtomicReference(null)

    /** 当前绑定的 VpnService 引用。 */
    private val vpnServiceRef: AtomicReference<VpnService?> = AtomicReference(null)

    /** 最近一次错误信息。 */
    private val lastErrorRef: AtomicReference<ServiceError?> = AtomicReference(null)

    /** 监控模式标记。 */
    private val monitorOnlyModeRef: AtomicReference<Boolean> = AtomicReference(false)

    /** 记录当前 runtime 启动时使用的 planet 模式。 */
    private val runtimeCustomPlanetRef: AtomicReference<Boolean?> = AtomicReference(null)

    /**
     * 绑定当前 VpnService。
     *
     * @param vpnService VpnService 实例。
     */
    fun bindVpnService(vpnService: VpnService) {
        vpnServiceRef.set(vpnService)
        runtimeContext.setSocketProtector { socket -> vpnService.protect(socket) }
        logChain("绑定 VPN Service")
    }

    /**
     * 解绑当前 VpnService。
     */
    fun unbindVpnService() {
        vpnServiceRef.set(null)
        runtimeContext.setSocketProtector(null)
        logChain("解绑 VPN Service")
    }

    /**
     * 更新监控模式。
     *
     * @param monitorOnly 是否为监控模式。
     */
    fun updateMonitorOnlyMode(monitorOnly: Boolean) {
        monitorOnlyModeRef.set(monitorOnly)
        logChain("仅监听模式已更新 enabled=$monitorOnly")
    }

    /**
     * 启动或恢复 ZeroTier 运行时（Runtime）。
     *
     * 该方法负责 ZeroTier 节点的“冷启动”或“热恢复”：
     * 1. 如果节点已在运行，则仅同步活动网络 ID。
     * 2. 如果节点未运行，则根据持久化状态和配置进行完整初始化。
     *
     * @param targetNetworkId 目标网络 ID；为空表示仅尝试恢复当前活动网络。
     * @return 启动结果，包含节点就绪状态和 VPN 隧道就绪状态。
     */
    suspend fun startRuntime(targetNetworkId: NetworkId?): RuntimeResult = runtimeLock.withLock {
        logChain("Runtime 启动开始 目标网络=${targetNetworkId?.value ?: "none"}")
        val settings = settingsStateHolder.currentState()
        var useCustomPlanet = settings.planetUseCustom
        val existingRuntime = runtimeRef.get()
        var existingKernelStateForStart: NodeKernelState? = existingRuntime?.readKernelState()

        // 第一步：检查节点是否已经初始化并正在运行
        if (existingRuntime?.isInited() == true) {
            val startedWithCustomPlanet = runtimeCustomPlanetRef.get()
            val shouldRestartForPlanetModeSwitch = startedWithCustomPlanet != null &&
                startedWithCustomPlanet != useCustomPlanet
            if (shouldRestartForPlanetModeSwitch) {
                logChain(
                    "检测到 Planet 链路模式变更，执行 Runtime 重启 oldCustom=$startedWithCustomPlanet newCustom=$useCustomPlanet",
                )
                stopRuntimeInternal(
                    runtime = existingRuntime,
                    keepServiceAlive = true,
                )
                existingKernelStateForStart = null
            } else {
            // 如果已在运行，尝试解析当前应激活的网络
                val activeNetwork = targetNetworkId ?: runtimeContext.activeNetworkId()?.toNetworkIdOrNull()
                runtimeContext.setActiveNetworkId(activeNetwork?.toLongId())

                return@withLock RuntimeResult.start(
                    activeNetworkId = activeNetwork,
                    nodeReady = true,
                    tunnelReady = existingRuntime.readKernelState().vpnSocket != null,
                    resultCode = RuntimeResultCode.ALREADY_RUNNING,
                    message = "Runtime already running.",
                ).also {
                    logChain("Runtime 启动跳过 结果=${it.resultCode} 当前网络=${activeNetwork?.value ?: "none"}")
                }
            }
        }

        if (useCustomPlanet && !planetFileStore.hasCustomPlanetFile()) {
            logChain("自定义 Planet 文件不存在，回退官方链路")
            settingsStateHolder.updateState {
                it.copy(
                    planetUseCustom = false,
                    planetAutoRouteCheck = false,
                )
            }
            useCustomPlanet = false
        }
        if (!useCustomPlanet) {
            logChain("当前使用官方 Planet，读取策略将强制回退 SDK 内置 planet")
        } else {
            logChain("当前使用自定义 Planet")
        }

        // 第二步：执行冷启动逻辑
        return@withLock runCatching {
            // 组装内核配置（会尝试从现有运行态恢复 Socket 等句柄，减少资源重建）
            val kernelConfig = buildRuntimeStartConfig(
                targetNetworkId = targetNetworkId,
                existingKernelState = existingKernelStateForStart,
            )

            // 启动底层 ZeroTier 内核（启动职责由 Core 单点完成）
            val runtime = NodeKernelRuntimeCore.start(kernelConfig)

            // 更新当前引用的运行时句柄
            runtimeRef.set(runtime)
            runtimeContext.bindRuntime(runtime)
            runtimeContext.setActiveNetworkId(targetNetworkId?.toLongId())

            // 将本次生成的节点 ID（Node ID）持久化到数据库
            persistNodeIdentity(runtime)

            val kernelState = runtime.readKernelState()
            lastErrorRef.set(null)
            runtimeCustomPlanetRef.set(useCustomPlanet)

            RuntimeResult.start(
                activeNetworkId = targetNetworkId,
                nodeReady = kernelState.nodeInited,
                tunnelReady = kernelState.vpnSocket != null,
                resultCode = RuntimeResultCode.SUCCESS,
                message = "Runtime started.",
            ).also {
                logChain("Runtime 启动成功 结果=${it.resultCode} 节点就绪=${it.nodeReady} 隧道就绪=${it.tunnelReady}")
            }
        }.getOrElse { error ->
            // 捕获启动过程中的所有异常，并转换为标准的服务错误
            val serviceError = buildServiceError(
                code = ServiceErrorCode.RUNTIME_START_FAILED,
                message = "Failed to start runtime: ${error.message.orEmpty()}",
                throwable = error,
            )
            lastErrorRef.set(serviceError)

            RuntimeResult.start(
                activeNetworkId = targetNetworkId,
                nodeReady = false,
                tunnelReady = false,
                resultCode = RuntimeResultCode.INTERNAL_ERROR,
                message = serviceError.message,
            ).also {
                runtimeCustomPlanetRef.set(null)
                logChain("Runtime 启动失败 结果=${it.resultCode} 信息=${it.message}")
                ChainLog.w(TAG, "Runtime 启动异常", error)
            }
        }
    }

    /**
     * 在运行时管线内就地组装 Node 内核配置。
     *
     * 该逻辑仅被 RuntimeService 消费，保留在本类可减少无意义的工厂跳转，
     * 让启动路径更直观。
     */
    private fun buildRuntimeStartConfig(
        targetNetworkId: NetworkId?,
        existingKernelState: NodeKernelState?,
    ): KernelRuntimeStartConfig {
        val networkId = targetNetworkId?.toLongId() ?: 0L
        return KernelRuntimeStartConfig(
            networkId = networkId,
            existingSocket = existingKernelState?.socket,
            existingUdpBridge = existingKernelState?.udpBridge,
            existingTunTapBridge = existingKernelState?.tunTapBridge,
            existingNode = existingKernelState?.node,
            existingVpnThread = existingKernelState?.vpnThread,
            existingUdpThread = existingKernelState?.udpThread,
            existingVpnSocket = existingKernelState?.vpnSocket,
            existingInput = existingKernelState?.input,
            existingOutput = existingKernelState?.output,
            dataStoreGetListener = runtimeContext.dataStoreGetListener,
            dataStorePutListener = runtimeContext.dataStorePutListener,
            packetSender = runtimeContext.packetSender,
            eventListener = runtimeContext.eventListener,
            frameListener = runtimeContext.frameListener,
            configListener = runtimeContext.configListener,
            pathChecker = null,
            vpnRunnable = runtimeContext.vpnRunnable,
            socketProtector = runtimeContext.socketProtector(),
            udpBridgeFactory = runtimeContext.createUdpBridgeFactory(),
            tunTapBridgeFactory = runtimeContext.createTunTapBridgeFactory(),
        )
    }

    /**
     * 停止 runtime 并释放绑定资源。
     *
     * 这里保留基础参数而非单独请求对象：
     * `stop` 当前只有 `keepServiceAlive` 一个行为开关，再包一层请求对象只会增加跳转成本。
     */
    suspend fun stopRuntime(
        keepServiceAlive: Boolean,
    ): RuntimeResult = runtimeLock.withLock {
        logChain("Runtime 停止开始 保活=$keepServiceAlive")
        val runtime = runtimeRef.get()
            ?: return@withLock RuntimeResult.stop(
                nodeClosed = false,
                tunnelClosed = true,
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
            ).also {
                // 关键逻辑：
                // 当调用方要求“彻底停止”时（keepServiceAlive=false），
                // 即使 runtime 已经不在运行，也必须清理 activeNetworkId，
                // 避免 UI/状态机继续把旧网络误判为“仍在活动”。
                if (!keepServiceAlive) {
                    runtimeContext.setActiveNetworkId(null)
                }
                logChain("Runtime 停止跳过 结果=${it.resultCode}")
            }

        return@withLock runCatching {
            val nodeClosed = stopRuntimeInternal(
                runtime = runtime,
                keepServiceAlive = keepServiceAlive,
            )
            lastErrorRef.set(null)
            RuntimeResult.stop(
                nodeClosed = nodeClosed,
                tunnelClosed = true,
                resultCode = RuntimeResultCode.SUCCESS,
                message = if (keepServiceAlive) {
                    "Runtime stopped and service kept alive."
                } else {
                    "Runtime stopped."
                },
            ).also {
                logChain("Runtime 停止成功 结果=${it.resultCode} 节点已关闭=${it.nodeClosed} 隧道已关闭=${it.tunnelClosed}")
            }
        }.getOrElse { error ->
            val serviceError = buildServiceError(
                code = ServiceErrorCode.INTERNAL_ERROR,
                message = "Failed to stop runtime: ${error.message.orEmpty()}",
                throwable = error,
            )
            lastErrorRef.set(serviceError)
            RuntimeResult.stop(
                nodeClosed = false,
                tunnelClosed = false,
                resultCode = RuntimeResultCode.INTERNAL_ERROR,
                message = serviceError.message,
            ).also {
                logChain("Runtime 停止失败 结果=${it.resultCode} 信息=${it.message}")
                ChainLog.w(TAG, "Runtime 停止异常", error)
            }
        }
    }

    /**
     * 加入目标网络。
     *
     * 这里直接使用 `networkId`：
     * 当前 `join` 仅有一个业务输入，去掉请求包装后命令到 runtime 的链路更直接。
     */
    suspend fun joinNetwork(
        networkId: NetworkId,
    ): RuntimeResult = runtimeLock.withLock {
        logChain("加入网络开始 networkId=${networkId.value}")
        val runtime = runtimeRef.get()
            ?: return@withLock RuntimeResult.operation(
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
            ).also {
                logChain("加入网络跳过 networkId=${networkId.value} 结果=${it.resultCode}")
            }
        val result = runtime.join(networkId.toLongId())
        return@withLock mapResultCode(result)
            .also { mapped ->
                logChain("加入网络结束 networkId=${networkId.value} 结果=${mapped.resultCode}")
                if (mapped.isSuccess) {
                    runtimeContext.setActiveNetworkId(networkId.toLongId())
                    lastErrorRef.set(null)
                }
            }
    }

    /**
     * 离开目标网络。
     */
    suspend fun leaveNetwork(
        networkId: NetworkId,
    ): RuntimeResult = runtimeLock.withLock {
        logChain("离开网络开始 networkId=${networkId.value}")
        val targetNetworkId = networkId.toLongId()
        val runtime = runtimeRef.get()
            ?: return@withLock RuntimeResult.leave(
                noNetworksLeft = run {
                    // 关键逻辑：
                    // 仅监听模式下 runtime 可能已经停止，但 activeNetworkId 仍保留；
                    // 主动离网时，如果目标网络等于当前 activeNetworkId，需要立即清空。
                    val activeNetworkBeforeLeave = runtimeContext.activeNetworkId()
                    if (activeNetworkBeforeLeave == targetNetworkId) {
                        runtimeContext.setActiveNetworkId(null)
                    }
                    runtimeContext.activeNetworkId() == null
                },
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
            ).also {
                logChain("离开网络跳过 networkId=${networkId.value} 结果=${it.resultCode} 无剩余网络=${it.noNetworksLeft}")
            }
        val leaveResultCode = runtime.leave(targetNetworkId)
        val mapped = mapResultCode(leaveResultCode)
        val noNetworksLeft = mapped.isSuccess && (runtime.networkConfigs()?.isEmpty() != false)
        val activeNetworkBeforeLeave = runtimeContext.activeNetworkId()
        if (mapped.isSuccess && (noNetworksLeft || activeNetworkBeforeLeave == targetNetworkId)) {
            runtimeContext.setActiveNetworkId(null)
        }
        return@withLock RuntimeResult.leave(
            noNetworksLeft = noNetworksLeft,
            resultCode = mapped.resultCode,
            message = mapped.message,
        ).also {
            logChain("离开网络结束 networkId=${networkId.value} 结果=${it.resultCode} 无剩余网络=$noNetworksLeft")
        }
    }

    /**
     * 执行 VPN 隧道建立与配置。
     *
     * 该方法是衔接 ZeroTier 虚拟网络与 Android 系统的核心桥梁。
     * 它通过 Android VpnService.Builder 构建虚拟网卡（TUN 接口），并实现以下功能：
     * 1. 应用虚拟 IP 地址。
     * 2. 注入 ZeroTier 控制器下发的路由规则。
     * 3. 配置分流策略（全量路由或仅私有网段）。
     * 4. 设置 DNS 服务器及搜索域。
     * 5. 应用 App 访问控制白名单。
     * 6. 将物理流量与虚拟网卡进行文件句柄级别的绑定。
     *
     * @param vpnTunnelConfig 隧道配置参数。
     * @return 建立结果，包含成功标志或详细错误码。
     */
    suspend fun establishVpnTunnel(vpnTunnelConfig: VpnTunnelConfig): RuntimeResult = runtimeLock.withLock {
        logChain(
            "隧道建立开始 networkId=${vpnTunnelConfig.networkId.value} 原因=${vpnTunnelConfig.reason} DNS模式=${vpnTunnelConfig.dnsMode} 默认路由=${vpnTunnelConfig.routeViaZeroTier}",
        )

        // 第一步：状态前置检查，确认内核已启动且 VPN 服务已就位
        val runtime = runtimeRef.get() ?: return@withLock RuntimeResult.establishVpnTunnel(
                managedRouteCount = 0,
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
                keepConnectingOnTunnelFailure = false,
            ).also {
                logChain("隧道建立跳过 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 原因=runtime_not_running")
            }
        val vpnService = vpnServiceRef.get() ?: return@withLock RuntimeResult.establishVpnTunnel(
                managedRouteCount = 0,
                resultCode = RuntimeResultCode.PERMISSION_DENIED,
                message = "VpnService is not attached.",
                keepConnectingOnTunnelFailure = false,
            ).also {
                logChain("隧道建立跳过 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 原因=vpn_service_not_attached")
            }

        // 第二步：从 ZeroTier 运行时获取当前网络的动态配置（IP、路由、MTU 等）
        val config = runtime.networkConfig(vpnTunnelConfig.networkId.toLongId())
            ?: return@withLock RuntimeResult.establishVpnTunnel(
                managedRouteCount = 0,
                resultCode = RuntimeResultCode.NETWORK_NOT_FOUND,
                message = "Network config not found.",
                keepConnectingOnTunnelFailure = false,
            ).also {
                logChain("隧道建立跳过 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 原因=network_config_missing")
            }
        logChain(
            "内核配置摘要 networkId=${vpnTunnelConfig.networkId.value} 状态=${config.status} 名称=${config.name ?: "none"} 地址数=${config.assignedAddresses.orEmpty().size} 路由数=${config.routes.orEmpty().size} DNS数=${config.dns?.servers.orEmpty().size}",
        )
        val runtimeStatus = ZeroTierStatusMapper.fromVirtualNetworkStatus(config.status)
        if (!runtimeStatus.isConnected) {
            val mappedCode = when (runtimeStatus) {
                NetworkStatusEnum.ACCESS_DENIED -> RuntimeResultCode.ACCESS_DENIED
                NetworkStatusEnum.AUTHENTICATION_REQUIRED -> RuntimeResultCode.AUTH_REQUIRED
                NetworkStatusEnum.REQUESTING_CONFIGURATION -> RuntimeResultCode.AUTH_REQUIRED
                else -> RuntimeResultCode.NETWORK_NOT_FOUND
            }
            return@withLock RuntimeResult.establishVpnTunnel(
                managedRouteCount = 0,
                resultCode = mappedCode,
                message = "Network status not ready: $runtimeStatus",
                keepConnectingOnTunnelFailure = runtimeStatus == NetworkStatusEnum.REQUESTING_CONFIGURATION ||
                    runtimeStatus == NetworkStatusEnum.AUTHENTICATION_REQUIRED ||
                    runtimeStatus == NetworkStatusEnum.ACCESS_DENIED,
            ).also {
                logChain(
                    "隧道建立跳过 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 原因=network_not_ready 状态=$runtimeStatus",
                )
            }
        }

        return@withLock runCatching {
            val disableIpv6 = settingsStateHolder.currentState().disableIpv6
            val previousKernelState = runtime.readKernelState()
            val builder = vpnService.Builder()
            var managedRouteCount = 0
            val vpnPermissionGranted = VpnService.prepare(vpnService) == null
            logChain("系统VPN授权检查 networkId=${vpnTunnelConfig.networkId.value} 已授权=$vpnPermissionGranted")

            // 第三步：配置虚拟网卡 IP 地址
            // 这里会将 ZeroTier 分配给本机的内网 IP（如 10.147.17.x）设置到网卡上
            config.assignedAddresses.orEmpty().forEach { address ->
                val host = address.address ?: return@forEach
                val prefix = address.port.coerceIn(0, MAX_PREFIX)
                if (disableIpv6 && host is Inet6Address) {
                    return@forEach
                }
                builder.addAddress(host, prefix)
                runCatching {
                    // 同时为该地址建立直连路由
                    builder.addRoute(maskAddress(host, prefix), prefix)
                    managedRouteCount += 1
                }.onFailure {
                    ChainLog.w(TAG, "添加直连路由失败 host=${host.hostAddress}/$prefix", it)
                }
            }

            // 第四步：配置受管路由（Managed Routes）
            // 这是实现“内网穿透”的关键，告诉 Android 哪些网段的流量需要走这个 VPN
            config.routes.orEmpty().forEach { route ->
                val targetAddress = route.target?.address ?: return@forEach
                val prefix = route.target.port.coerceIn(0, MAX_PREFIX)
                if (disableIpv6 && targetAddress is Inet6Address) {
                    return@forEach
                }
                // 判断是否需要路由（基于用户设置：是全量转发还是仅转发 ZT 流量）
                val shouldRoute = shouldRouteViaZeroTier(
                    routeViaZeroTier = vpnTunnelConfig.routeViaZeroTier,
                    gatewayAddress = route.via?.address,
                )
                if (!shouldRoute) {
                    return@forEach
                }
                runCatching {
                    builder.addRoute(maskAddress(targetAddress, prefix), prefix)
                    managedRouteCount += 1
                }.onFailure {
                    ChainLog.w(TAG, "添加受管路由失败 target=${targetAddress.hostAddress}/$prefix", it)
                }
            }

            // 第五步：基础参数配置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false) // 标记为非计费流量
            }
            // 默认 non-blocking 会在空闲时返回 0，导致 TUN 读循环空转耗电。
            builder.setBlocking(true)
            builder.setMtu(config.mtu.takeIf { it > 0 } ?: DEFAULT_MTU)
            builder.setSession(VPN_SESSION_NAME)

            // 第六步：应用 DNS 策略（系统 DNS 或 ZeroTier 私有 DNS）
            applyDns(vpnTunnelConfig, config, builder, disableIpv6)

            // 第七步：应用 App 白名单/黑名单，决定哪些 App 的流量受此 VPN 影响
            val whitelistApplyResult = appWhitelistApplier.apply(builder, vpnTunnelConfig.toAppWhitelistConfig())
            if (!whitelistApplyResult.isSuccess) {
                ChainLog.w(
                    TAG,
                    "应用白名单部分失败 ignored=${whitelistApplyResult.ignoredPackages.size} failed=${whitelistApplyResult.failedPackages.size}",
                )
            }

            // 第八步：核心动作 - 建立（establish）文件描述符
            // 这是 Android 系统真正创建虚拟接口（tun0）的时刻
            val vpnSocket = builder.establish()
                ?: return@runCatching RuntimeResult.establishVpnTunnel(
                    managedRouteCount = managedRouteCount,
                    resultCode = if (vpnPermissionGranted) {
                        RuntimeResultCode.INTERNAL_ERROR
                    } else {
                        RuntimeResultCode.PERMISSION_DENIED
                    },
                    message = if (vpnPermissionGranted) {
                        "VpnService establish returned null after permission granted."
                    } else {
                        "VPN permission not granted."
                    },
                    keepConnectingOnTunnelFailure = true,
                )

            // 第九步：双向绑定 IO 句柄
            // 将 VpnService 生成的文件描述符传给 ZeroTier 内核（JNI 层）
            // 这样系统接收的明文流量才能进入 ZT 加密，ZT 加密后的流量才能发往系统
            val input = FileInputStream(vpnSocket.fileDescriptor)
            val output = FileOutputStream(vpnSocket.fileDescriptor)

            runtime.attachTunnelIo(vpnSocket, input, output)
            runtimeContext.bindTunnelIo(input, output)
            runtimeContext.setActiveNetworkId(vpnTunnelConfig.networkId.toLongId())
            logChain("隧道IO已绑定 networkId=${vpnTunnelConfig.networkId.value} fdReady=true")

            // 清理旧的连接，防止句柄泄漏
            closeTunnelIoIfReplaced(
                previousKernelState.vpnSocket,
                previousKernelState.input,
                previousKernelState.output,
                vpnSocket,
                input,
                output,
            )
            lastErrorRef.set(null)

            RuntimeResult.establishVpnTunnel(
                managedRouteCount = managedRouteCount,
                resultCode = RuntimeResultCode.SUCCESS,
                message = "Tunnel established successfully.",
                keepConnectingOnTunnelFailure = false,
            ).also {
                logChain("隧道建立结束 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 受管路由数=$managedRouteCount")
            }
        }.getOrElse { error ->
            val serviceError = buildServiceError(
                code = ServiceErrorCode.ESTABLISH_VPN_TUNNEL_FAILED,
                message = "Failed to establish tunnel: ${error.message.orEmpty()}",
                throwable = error,
            )
            lastErrorRef.set(serviceError)
            RuntimeResult.establishVpnTunnel(
                managedRouteCount = 0,
                resultCode = RuntimeResultCode.INTERNAL_ERROR,
                message = serviceError.message,
                keepConnectingOnTunnelFailure = false,
            ).also {
                logChain("隧道建立失败 networkId=${vpnTunnelConfig.networkId.value} 结果=${it.resultCode} 信息=${it.message}")
                ChainLog.w(TAG, "隧道建立异常", error)
            }
        }
    }

    /**
     * 获取 runtime 当前可见的网络配置列表。
     *
     * @return 网络配置列表。
     */
    suspend fun listNetworks(): List<RuntimeNetworkInfo> {
        val runtime = runtimeRef.get() ?: return emptyList()
        return runtime.networkConfigs().orEmpty()
            .mapNotNull { it.networkRuntimeConfig() }
    }

    /**
     * 获取指定网络配置。
     *
     * @param networkId 目标网络 ID。
     * @return 网络配置，未命中返回 null。
     */
    suspend fun getNetworkConfig(networkId: NetworkId): RuntimeNetworkInfo? {
        val runtime = runtimeRef.get() ?: return null
        return runtime.networkConfig(networkId.toLongId())?.networkRuntimeConfig()
    }

    /**
     * 获取当前 Peer 列表。
     *
     * @return Peer 列表。
     */
    suspend fun listPeers(): List<RuntimePeerInfo> {
        val runtime = runtimeRef.get() ?: return emptyList()
        return runtime.peers().orEmpty().map { it.toRuntimePeer() }
    }

    /**
     * 执行 Moon 入轨。
     *
     * @param moons 待入轨 Moon 参数列表。
     * @return 操作结果。
     */
    suspend fun orbitMoons(
        moons: List<RuntimeMoonOrbit>,
    ): RuntimeResult = runtimeLock.withLock {
        val runtime = runtimeRef.get()
            ?: return@withLock RuntimeResult.operation(
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
            )
        moons.forEach { spec ->
            val result = runtime.orbit(spec.moonWorldId, spec.moonSeed)
            val mapped = mapResultCode(result)
            if (!mapped.isSuccess) {
                return@withLock mapped
            }
        }
        RuntimeResult.operation(
            resultCode = RuntimeResultCode.SUCCESS,
            message = "Moons orbited.",
        )
    }

    /**
     * 执行 Moon 退轨。
     *
     * @param moonWorldIds 待退轨 Moon World ID 列表。
     * @return 操作结果。
     */
    suspend fun deorbitMoons(
        moonWorldIds: List<Long>,
    ): RuntimeResult = runtimeLock.withLock {
        val runtime = runtimeRef.get()
            ?: return@withLock RuntimeResult.operation(
                resultCode = RuntimeResultCode.NOT_RUNNING,
                message = "Runtime is not running.",
            )
        moonWorldIds.forEach { moonWorldId ->
            val result = runtime.deorbit(moonWorldId)
            val mapped = mapResultCode(result)
            if (!mapped.isSuccess) {
                return@withLock mapped
            }
        }
        RuntimeResult.operation(
            resultCode = RuntimeResultCode.SUCCESS,
            message = "Moons deorbited.",
        )
    }

    /**
     * 查询当前节点信息。
     *
     * @return 节点信息；runtime 未启动时返回 null。
     */
    suspend fun getNode(): RuntimeNodeInfo? = runtimeLock.withLock {
        val runtime = runtimeRef.get() ?: return@withLock null
        val status = runtime.status()
        val nodeId = status?.address ?: runtime.address() ?: return@withLock null
        val version = runtime.version()
        val versionText = if (version != null) {
            "${version.major}.${version.minor}.${version.revision}"
        } else {
            null
        }
        RuntimeNodeInfo(
            nodeId = nodeId,
            nodeIdHex = java.lang.Long.toUnsignedString(nodeId, 16).padStart(10, '0'),
            online = status?.isOnline == true,
            version = versionText,
            activeNetworkId = runtimeContext.activeNetworkId()?.toNetworkIdOrNull(),
            joinedNetworkCount = runtime.networkConfigs()?.size ?: 0,
        )
    }

    /**
     * 获取 runtime 全局快照。
     *
     * @return runtime 快照。
     */
    fun readRuntimeState(): NodeState {
        val runtime = runtimeRef.get()
        val runtimeSnapshot = runtime?.readKernelState()
        return NodeState(
            nodeReady = runtime?.isInited() == true,
            tunnelReady = runtimeSnapshot?.vpnSocket != null,
            vpnSocketReady = runtimeSnapshot?.vpnSocket != null,
            activeNetworkId = runtimeContext.activeNetworkId()?.toNetworkIdOrNull(),
            monitorOnlyMode = monitorOnlyModeRef.get(),
            lastError = lastErrorRef.get(),
        )
    }

    /**
     * 持久化 Node 身份。
     *
     * @param runtime runtime 句柄。
     */
    private fun persistNodeIdentity(runtime: NodeKernelRuntimeCore) {
        val address = runtime.address() ?: return
        val now = System.currentTimeMillis()
        runCatching {
            appNodeDao.upsert(
                AppNodeDbEntity(
                    nodeId = address,
                    nodeIdHex = java.lang.Long.toUnsignedString(address, 16).padStart(10, '0'),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.onFailure {
            ChainLog.w(TAG, "持久化节点身份失败", it)
        }
    }

    private fun logChain(message: String) {
        ChainLog.i(TAG, message)
    }

    /**
     * 关闭被替换的 TUN IO。
     *
     * @param oldVpnSocket 旧 VPN FD。
     * @param oldInput 旧输入流。
     * @param oldOutput 旧输出流。
     * @param newVpnSocket 新 VPN FD。
     * @param newInput 新输入流。
     * @param newOutput 新输出流。
     */
    private fun closeTunnelIoIfReplaced(
        oldVpnSocket: ParcelFileDescriptor?,
        oldInput: FileInputStream?,
        oldOutput: FileOutputStream?,
        newVpnSocket: ParcelFileDescriptor,
        newInput: FileInputStream,
        newOutput: FileOutputStream,
    ) {
        if (oldVpnSocket !== newVpnSocket) {
            closeQuietly(oldVpnSocket)
        }
        if (oldInput !== newInput) {
            closeQuietly(oldInput)
        }
        if (oldOutput !== newOutput) {
            closeQuietly(oldOutput)
        }
    }

    /**
     * 关闭 runtime 内核并释放 RuntimeContext 绑定资源。
     *
     * @param runtime 当前 runtime 实例。
     * @param keepServiceAlive 是否保留当前 activeNetworkId。
     * @return 节点关闭结果。
     */
    private fun stopRuntimeInternal(
        runtime: NodeKernelRuntimeCore,
        keepServiceAlive: Boolean,
    ): Boolean {
        val currentKernelState = runtime.readKernelState()
        val activeNetworkBeforeStop = runtimeContext.activeNetworkId()
        val nodeClosed = runtime.stop()
        runtimeRef.set(null)
        runtimeContext.bindRuntime(null)
        runtimeCustomPlanetRef.set(null)
        if (keepServiceAlive) {
            runtimeContext.setActiveNetworkId(activeNetworkBeforeStop)
        } else {
            runtimeContext.setActiveNetworkId(null)
        }
        runtimeContext.clearTunnelIo()
        closeQuietly(currentKernelState.vpnSocket)
        closeQuietly(currentKernelState.input)
        closeQuietly(currentKernelState.output)
        return nodeClosed
    }

    /**
     * 应用 DNS 配置。
     *
     * @param request 重配置请求。
     * @param config 网络配置。
     * @param builder VPN 构建器。
     * @param disableIpv6 是否禁用 IPv6。
     */
    private fun applyDns(
        request: VpnTunnelConfig,
        config: VirtualNetworkConfig,
        builder: VpnService.Builder,
        disableIpv6: Boolean,
    ) {
        when (request.dnsMode) {
            NetworkDnsModeEnum.NONE -> Unit
            NetworkDnsModeEnum.NETWORK -> {
                val dnsConfig = config.dns ?: return
                val searchDomain = dnsConfig.domain
                if (searchDomain.isNotBlank()) {
                    builder.addSearchDomain(searchDomain)
                }
                dnsConfig.servers.orEmpty()
                    .mapNotNull(InetSocketAddress::getAddress)
                    .filter { address -> !(disableIpv6 && address is Inet6Address) }
                    .forEach { address -> builder.addDnsServer(address) }
            }

            NetworkDnsModeEnum.CUSTOM -> {
                request.customDnsServers
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                    .filter { address -> !(disableIpv6 && address is Inet6Address) }
                    .forEach { address -> builder.addDnsServer(address) }
            }
        }
    }

    /**
     * 判定路由是否应走 ZeroTier。
     *
     * @param routeViaZeroTier 默认路由开关。
     * @param gatewayAddress 网关地址。
     * @return 是否接管该路由。
     */
    private fun shouldRouteViaZeroTier(
        routeViaZeroTier: Boolean,
        gatewayAddress: InetAddress?,
    ): Boolean {
        if (routeViaZeroTier) {
            return true
        }
        if (gatewayAddress == null) {
            return false
        }
        return !gatewayAddress.isAnyLocalAddress &&
            !gatewayAddress.isLoopbackAddress
    }

    /**
     * 将地址按前缀掩码转换为路由网络地址。
     *
     * @param address 原始地址。
     * @param prefix 前缀长度。
     * @return 网络地址。
     */
    private fun maskAddress(
        address: InetAddress,
        prefix: Int,
    ): InetAddress {
        val bytes = address.address.clone()
        var remaining = prefix
        for (index in bytes.indices) {
            val mask = when {
                remaining >= 8 -> 0xFF
                remaining <= 0 -> 0x00
                else -> (0xFF shl (8 - remaining)) and 0xFF
            }
            bytes[index] = (bytes[index].toInt() and mask).toByte()
            remaining -= 8
        }
        return InetAddress.getByAddress(bytes)
    }

    /**
     * SDK 返回码映射为 Runtime 返回码。
     *
     * @param resultCode SDK 返回码。
     * @return Runtime 操作结果。
     */
    private fun mapResultCode(resultCode: ResultCode?): RuntimeResult {
        return when (resultCode) {
            ResultCode.RESULT_OK,
            ResultCode.RESULT_OK_IGNORED,
            -> RuntimeResult.operation(
                resultCode = RuntimeResultCode.SUCCESS,
                message = "Operation succeeded.",
            )

            ResultCode.RESULT_ERROR_NETWORK_NOT_FOUND ->
                RuntimeResult.operation(
                    resultCode = RuntimeResultCode.NETWORK_NOT_FOUND,
                    message = "Network not found.",
                )

            ResultCode.RESULT_ERROR_BAD_PARAMETER ->
                RuntimeResult.operation(
                    resultCode = RuntimeResultCode.INVALID_ARGUMENT,
                    message = "Invalid argument.",
                )

            ResultCode.RESULT_FATAL_ERROR_DATA_STORE_FAILED,
            ResultCode.RESULT_FATAL_ERROR_INTERNAL,
            ResultCode.RESULT_FATAL_ERROR_OUT_OF_MEMORY,
            -> RuntimeResult.operation(
                resultCode = RuntimeResultCode.INTERNAL_ERROR,
                message = "Internal runtime error: $resultCode",
            )

            null ->
                RuntimeResult.operation(
                    resultCode = RuntimeResultCode.NOT_RUNNING,
                    message = "Runtime is not running.",
                )

            else ->
                RuntimeResult.operation(
                    resultCode = RuntimeResultCode.UNKNOWN_ERROR,
                    message = "Unknown runtime error: $resultCode",
                )
        }
    }

    /**
     * 构建结构化错误。
     *
     * @param code 错误码。
     * @param message 错误信息。
     * @param throwable 原始异常。
     * @return 结构化错误。
     */
    private fun buildServiceError(
        code: ServiceErrorCode,
        message: String,
        throwable: Throwable,
    ): ServiceError {
        return ServiceError(
            code = code,
            message = message,
            recoverable = true,
            causeType = throwable::class.java.simpleName,
        )
    }

    /**
     * 安静关闭资源。
     *
     * @param target 目标资源。
     */
    private fun closeQuietly(target: Closeable?) {
        runCatching { target?.close() }
    }

    /**
     * 安静关闭 ParcelFileDescriptor。
     *
     * @param target 目标资源。
     */
    private fun closeQuietly(target: ParcelFileDescriptor?) {
        runCatching { target?.close() }
    }

    /**
     * SDK 网络配置映射为 runtime 网络模型。
     *
     * @return runtime 网络配置。
     */
    private fun VirtualNetworkConfig.networkRuntimeConfig(): RuntimeNetworkInfo? {
        val networkId = nwid.toNetworkIdOrNull() ?: return null
        val assignedIps = assignedAddresses.orEmpty().mapNotNull { address ->
            val host = address.address ?: return@mapNotNull null
            "${host.hostAddress}/${address.port}"
        }
        val dnsServers = dns?.servers.orEmpty().mapNotNull { it.address?.hostAddress }
        return RuntimeNetworkInfo(
            networkId = networkId,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            status = ZeroTierStatusMapper.fromVirtualNetworkStatus(status),
            assignedIps = assignedIps,
            dnsServers = dnsServers,
            mac = StringUtils.macAddressToString(mac),
            mtu = mtu.takeIf { it > 0 },
            broadcastEnabled = isBroadcastEnabled,
            bridgingEnabled = isBridge,
        )
    }

    /**
     * SDK Peer 映射为 runtime 对象。
     */
    private fun Peer.toRuntimePeer(): RuntimePeerInfo {
        // 对齐老项目：
        // 1) peerId 统一使用无符号十六进制地址，避免 SDK 工具方法在不同平台下格式不一致；
        // 2) 路径优先选择 preferred path，若不存在再回退到首个可用路径。
        val versionText = if (versionMajor >= 0 && versionMinor >= 0 && versionRev >= 0) {
            "$versionMajor.$versionMinor.$versionRev"
        } else {
            null
        }
        val preferredPath = paths.orEmpty()
            .firstOrNull { it.isPreferred && it.address != null }
        val fallbackPath = paths.orEmpty()
            .firstOrNull { it.address != null }
        val endpoint = (preferredPath ?: fallbackPath)?.address?.toString()
        return RuntimePeerInfo(
            peerId = address.toPeerIdHex(),
            role = role.name,
            address = endpoint,
            latencyMs = latency.toLong().takeIf { it >= 0 },
            version = versionText,
        )
    }

    private companion object {
        /** 日志标签。 */
        private const val TAG = "RuntimeService"

        /** VPN 默认 MTU。 */
        private const val DEFAULT_MTU = 2800

        /** VPN 会话名。 */
        private const val VPN_SESSION_NAME = "Zerotier Link"

        /** 前缀最大值。 */
        private const val MAX_PREFIX = 128
    }
}

/**
 * 将节点地址转换为固定宽度的无符号十六进制字符串。
 *
 * 说明：
 * 老项目 peers 列表使用十六进制地址展示，采用固定宽度后更便于比对与排序。
 */
private fun Long.toPeerIdHex(): String {
    return java.lang.Long.toUnsignedString(this, 16).padStart(10, '0')
}

/**
 * NetworkId 转换为无符号 Long。
 */
private fun NetworkId.toLongId(): Long {
    return java.lang.Long.parseUnsignedLong(value, 16)
}

/**
 * Long 转换为 NetworkId。
 */
private fun Long.toNetworkIdOrNull(): NetworkId? {
    val hex = java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')
    return NetworkId.parse(hex)
}

