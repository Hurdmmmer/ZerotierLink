# ZerotierLink 重构索引（功能不减、稳定性不降）

## 1. 目标与红线
- 目标：把 `D:\IdeaProject\ZerotierFix` 的核心能力重构到 `D:\AndroidProject\ZerotierLink`。
- 红线：
  - 不丢功能
  - 不降稳定性
  - 不引入不可观测状态（失败必须可定位）

## 2. 当前阶段（2026-04-17）
- 已完成：
  - Compose UI 主框架与页面骨架
  - 设置模块（DataStore）
  - 开机广播与开机启动服务骨架
  - Join 第一批领域实体定义（`domain/network`）
  - 入网授权状态建模（`ZeroTierStatusMapper`、`ConnectionState`）
  - 设置状态单一状态源（`SettingsStateHolder`）
- 未完成（核心缺口）：
  - Join/Leave 真正连接链路
  - Runtime 管理与隧道编排
  - 网络切换策略恢复
  - 运行状态通知联动
  - Peers/Moons 真数据联动

## 3. 架构清单（最终落地）

### 3.1 服务与核心组件
- [x] `ZeroTierVpnService`（唯一前台服务，连接生命周期编排）
- [x] `RuntimeFacade`（唯一内核调用入口）
- [x] `ServiceCommandQueue`（Join/Leave/Pause/Resume/Stop 串行命令）
- [x] `ServiceStateStore`（单一状态源，StateFlow）
- [x] `ServiceNotificationController`（运行中/暂停中转/停止/错误通知）
- [x] `NetworkChangeObserver`（网络变化监听组件，非独立 Android Service）
- [x] `BootCompletedReceiver`（开机自启入口）

### 3.2 数据与调用入口
- [x] `NetworkRepository`（网络配置持久化，upsert）
- [x] UI 通过 `ZeroTierVpnService` Intent 直连命令入口（不再保留 UseCase 转发层）

### 3.3 状态与事件模型（简化且不丢能力）
- [x] `ServiceState` 定义：
  - `Stopped`
  - `Starting`
  - `Connecting(networkId)`
  - `Connected(networkId)`
  - `MonitorOnly`
  - `Stopping`
  - `Error(code, message)`
- [x] `ServiceEffect` 定义：
  - `JoinSuccess`
  - `JoinFailed`
  - `LeaveDone`
  - `RuntimeRecovered`

## 4. 功能清单（对齐老项目）

### 4.1 连接与网络管理
- [x] 加入网络（Join）
- [x] 离开网络（Leave）
- [ ] 网络列表状态实时刷新
- [ ] 网络详情读写（default route / dns mode）
- [x] 默认路由开关生效
- [x] DNS 模式生效（none/network/custom）

### 4.2 路由策略与恢复
- [x] 暂停中转（Monitor Only）
- [x] 恢复中转
- [x] 网络切换自动检测与策略执行
- [x] 手动连接保护窗口（防止刚连上就被自动策略回切）

### 4.3 启动与生命周期
- [ ] 开机自启（受设置开关控制）
- [x] 前台服务通知状态同步
- [ ] 服务异常恢复路径
- [ ] 后台/前台切换稳定

### 4.4 观测与排障
- [ ] 关键链路日志（join/leave/policy/runtime）
- [ ] 错误码透传（UI 可见）
- [ ] 通知与 UI 状态一致性校验

### 4.5 业务页面能力
- [ ] Peers 页面接入真实数据
- [ ] Moons 页面接入真实数据

## 5. 加入网络（Join）专项清单（第一优先级）
- [x] 输入校验：16 位十六进制 networkId
- [x] DNS 输入校验（custom 时强校验）
- [x] 落库（upsert）
- [x] 发命令到 `ZeroTierVpnService`
- [x] Runtime 执行 `ensureStarted + joinNetwork`
- [x] 回写状态到 `ServiceStateStore`
- [ ] UI 展示 connecting/connected/failed
- [x] 通知状态联动

## 6. 迁移映射（老 -> 新）
- [ ] `ZeroTierOneService` -> `ZeroTierVpnService`
- [ ] `RuntimeCoreController` -> `RuntimeFacade` + `ServiceCommandQueue`
- [ ] `RoutePolicyEngine` -> `RoutePolicyEngine`（保留决策职责）
- [ ] `NetworkChangeObserver` -> `NetworkChangeObserver`（组件化复用）
- [ ] `ServiceNotificationController` -> `ServiceNotificationController`
- [x] `StartupReceiver` -> `BootCompletedReceiver`

## 7. 稳定性门禁（每次合并前必须通过）
- [ ] 冷启动可用
- [ ] Join 成功路径通过
- [ ] Leave 成功路径通过
- [ ] 切网恢复通过（Wi-Fi <-> Cellular）
- [ ] 开机自启路径通过
- [ ] 暂停中转/恢复中转路径通过
- [ ] 通知状态与实际运行状态一致
- [ ] 失败场景可观测（日志 + UI 错误）

## 8. 当前执行顺序（固定）
1. 先完成 Join 全链路（MVP）
2. 再完成 Leave
3. 再完成 Pause/Resume Relay
4. 再接 NetworkChangeObserver 自动恢复
5. 最后补 Peers/Moons 与长尾优化

## 9. 使用规则
- 每完成一项就打勾并补充“验证结果”。
- 如果发现新增能力，先加到本索引再开发。
- 没有在索引里的变更，不允许直接合并。

## 10. 进度快照（2026-04-17）
- 当前判断：整体重构约 35%~45%，UI/设置层推进明显，内核链路尚未闭环。
- 已确认落地：
  - `BootCompletedReceiver` 已接入 Manifest 并按设置开关拉起前台服务。
  - `ZeroTierBootService` 仍为脚手架，核心 runtime/join 逻辑尚未接入。
  - `NetworkRepository` 与 `NetworkRepositoryImpl` 已落地并在 `NetworksViewModel` 中使用。
  - Join 页面已完成 networkId 校验、custom DNS 校验、落库写入。
  - 设置模块已引入 `SettingsStateHolder`，解决多 ViewModel 状态覆盖问题。
- 主要风险：
  - `Join/Leave/Pause/Resume` 仍未进入真实 runtime 调用链。
  - 当前网络状态刷新主要来自本地状态切换，非 runtime 实时回调。
  - `Peers/Moons` 仍为占位页面，未接真实数据。

## 10.1 API 契约进展（2026-04-20，历史记录）
- 已完成（仅定义接口与契约模型，未实现）：
  - `RuntimeFacade`（启动/停止、Join/Leave、隧道重配置、网络/Peer 查询、Moon 入轨）
  - `ServiceCommand`、`ServiceCommandQueue`、`ServiceState`、`ServiceEffect`、`ServiceStateStore`
  - `RoutePolicy` 策略契约（评估器、运行时委托、协调器）
  - `NetworkChangeObserver` 契约
  - `ServiceNotificationController` 契约
- 已完成（仅定义接口与契约模型，未实现）：
  - `NodeKernelCore` 采用“启动器 + Runtime 句柄”契约模式：
    - `NodeKernelCore.start(...)` 负责启动并返回 `NodeKernelRuntime`
    - `NodeKernelRuntime` 定义完整内核交互能力（stop、join/leave、networkConfig(s)、peers、orbit/deorbit、status/version、process*）。
  - `NodeKernelStartRequestFactory` 契约已定义（用于组装 Node 启动请求）。
- 验证结果：
  - `:app:compileDebugKotlin` 通过（当前仅接口契约，不含实现类）。

## 10.2 白名单绕过能力（2026-04-20）
- 已完成：
  - 设置层新增 `network_whitelist_app_packages` 配置键（底层兼容旧键值）
  - `SettingsUiState` 新增 `whitelistAppPackages` 状态字段
  - `SettingsViewModel` 新增白名单包名增删改入口
  - Runtime 契约新增隧道重配置字段：
    - `whitelistPackages`
    - `includeBuiltInWhitelistPackages`
  - 新增 `AppWhitelistApplier`，用于把白名单应用到 `VpnService.Builder.addDisallowedApplication`
  - 内置默认绕过包名与老项目对齐：
    - `com.android.vending`
    - `com.google.android.projection.gearhead`
    - `com.google.android.gms`
- 待接入：
  - （已完成）在 `ZeroTierVpnService` 隧道重配置链路中调用 `AppWhitelistApplier.apply(...)`
  - 设置页增加“选择应用白名单”交互 UI（当前仅数据与运行时链路已就绪）

## 10.3 内核实现进展（2026-04-20）
- 已完成：
  - `NodeKernelCore` 实现已落地到 `service/kernel/impl`：
    - `NodeKernelCoreImpl`
    - `NodeKernelRuntimeImpl`（作为运行时句柄实现）
  - 关键能力已对齐老项目：
    - 启动阶段复用/新建 `Socket + Node + Bridge + Thread`
    - `join / leave / peers / networkConfig(s) / orbit / deorbit / process*` 统一收敛到 runtime 句柄
    - 停止阶段资源回收（线程中断、bridge 回收、VPN IO 回收、Node 关闭）
  - Hilt 绑定已补齐：
    - `KernelModule.provideNodeKernelCore -> NodeKernelCoreImpl`
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.4 服务运行链路实现（2026-04-20）
- 已完成：
  - `ZeroTierVpnService` 真正落地为 `VpnService`：
    - 对外提供本地 Binder
    - 支持 `Start/Join/Leave/Stop` Intent 命令入口
    - 绑定 `NodeRuntimeController`、网络变化观察器、路由策略协调器
  - `RuntimeFacade` 实现：`NodeRuntimeController`
    - 打通 `ensureStarted / stopRuntime / join / leave / reconfigureTunnel / listPeers / listNetworks / orbitMoons`
    - 隧道重配置接入应用白名单（`AppWhitelistApplier`，自动跳过白名单 App 转发）
  - `NodeKernelStartRequestFactory` 实现：`DefaultNodeKernelStartRequestFactory`
    - 统一注入 DataStore、PacketSender、UDP/TUN Bridge、后台任务循环
  - `ServiceStateStore` 实现：`DefaultServiceStateStore`
  - `ServiceCommandQueue` 实现：`DefaultServiceCommandQueue`
    - Join 固定链路：`ensureStarted -> joinNetwork -> reconfigureTunnel`
    - 回写仓库状态与 Effect 事件
  - `ServiceNotificationController` 实现：`DefaultServiceNotificationController`
    - 事件驱动 + 自适应采样（1.5s~10s）+ 文案去抖，仅变化时 notify
  - `NetworkChangeObserver` 实现：`AndroidNetworkChangeObserver`（归位到 `service/listener` 模块）
  - `RoutePolicyEvaluator/Coordinator` 实现：
    - 按配置 SSID 判定内网，支持手动连接保护窗口
  - UI -> Service 调用链：
    - `NetworksViewModel.toggleNetwork` 直接构造 `ZeroTierVpnService` 的 Join/Leave Intent
    - 删除 `UseCase` 转发层，减少一层无业务价值抽象
  - Manifest 接线：新增 `ZeroTierVpnService`（`BIND_VPN_SERVICE`）
  - 开机接线：`BootCompletedReceiver` 改为直接拉起 `ZeroTierVpnService`
- 验证结果：
  - `:app:compileDebugKotlin` 通过（含上述实现类）。

## 10.5 去过度抽象整理（2026-04-20）
- 已完成：
  - 删除 `service/listener/impl`，保留单一具体类 `NetworkChangeObserver`。
  - 删除 `service/notification/impl`，保留单一具体类 `ServiceNotificationController`。
  - 删除 `service/policy/impl`，策略评估/协调改为 `service/policy` 下具体类：
    - `RoutePolicyEvaluator`
    - `RoutePolicyCoordinator`
  - `ZeroTierVpnService` 与 DI 改为直接注入具体类，不再做接口+默认实现映射。
- 说明：
  - 保留 `RoutePolicyRuntimeDelegate` 作为 Service 与策略协调器的反向委托边界，其余抽象层已收敛。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.6 SessionContext 与观察管线重构（2026-04-20）
- 已完成：
  - 修复 `RuntimeModule` 的依赖绑定异常，清理无效 `impl` 包路径映射，恢复 KSP 编译链路。
  - 命名收敛：
    - `NodeRuntimeInfrastructure` -> `NodeSessionContext`
    - 统一“会话资源持有者”语义（runtime 句柄/TUN IO/统计/回调）。
  - 工厂收敛（去接口+默认实现二段式）：
    - `NodeKernelStartRequestFactory` 改为单一具体类（直接注入使用）。
    - `RuntimeTunnelReconfigureRequestFactory` 改为单一具体类（直接注入使用）。
  - 服务观察逻辑拆分为 Controller，并由 Pipeline 统一编排：
    - `ServiceStateController`：状态 -> 通知/前台服务状态
    - `ServiceTrafficController`：连接态流量刷新（省电策略）
    - `ServiceNetworkController`：网络变化监听 + 内核配置回调转命令
    - `ServiceRuntimeObserverPipeline`：仅做 `start/stop` 编排
  - `ZeroTierVpnService` 收敛职责：
    - 保留生命周期、命令入口、前台服务控制、策略委托实现；
    - 移除 `observeState/observeTraffic/observeNetwork/observeRuntimeNetworkConfig` 内联实现。
- 省电优化结果：
  - 流量刷新从“常驻 while 全状态轮询”改为“仅 Connected 状态进入循环”，非连接态不轮询。
  - 通知仍保留“文案未变化不 notify”策略，减少唤醒与重绘。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.7 Runtime 命名收敛（2026-04-20）
- 已完成：
  - Runtime 核心命名统一：
    - `NodeKernelCore/NodeKernelCoreImpl` 收敛为单一具体类 `NodeRuntimeCore`
    - 删除 `service/runtime/kernel/impl` 包，核心实现移至 `service/runtime/kernel` 根目录
  - Runtime 编排命名统一：
    - `NodeRuntimeController` 更名为 `RuntimeService`
    - `ZeroTierVpnService` 与 `ServiceCommandQueue` 依赖同步改名（`runtimePipeline`）
  - DI 收敛：
    - 删除 `KernelModule`（`NodeRuntimeCore` 通过 `@Inject` 直接注入）
  - 合同文件命名清理：
    - `NodeKernelCore.kt` 更名为 `NodeRuntimeContracts.kt`
- 说明：
  - 本次仅做命名与结构收敛，不改运行时功能路径。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.8 内核契约去接口化（2026-04-20）
- 已完成：
  - `service/runtime/kernel/NodeRuntimeContracts.kt` 中内核相关接口全部移除：
    - `KernelSocketProtector` -> 函数类型 `typealias`
    - `KernelUdpBridge` / `KernelTunTapBridge` -> 抽象基类
    - `KernelUdpBridgeFactory` / `KernelTunTapBridgeFactory` -> 函数类型 `typealias`
    - `NodeKernelRuntime` -> 抽象基类
  - `NodeRuntimeCore` 与 `NodeSessionContext` 已同步改造调用方式（`invoke` / 抽象类继承）。
- 说明：
  - `RuntimeContracts.kt` 原本即为数据模型与结果类型，不存在接口定义。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.9 模型文件命名规范化（2026-04-20）
- 已完成：
  - `service/runtime/RuntimeContracts.kt` -> `service/runtime/RuntimeModels.kt`
  - `service/runtime/kernel/NodeRuntimeContracts.kt` -> `service/runtime/kernel/KernelRuntimeTypes.kt`
- 说明：
  - 仅调整文件命名，不改动类型语义与调用路径。
  - 命名从 `Contracts` 收敛为 `Models/Types`，避免误解为常量定义文件。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.10 Runtime 请求模型去重复（2026-04-21）
- 已完成：
  - 删除仅做字段搬运的重复请求模型：
    - `RuntimeJoinRequest`
    - `RuntimeLeaveRequest`
    - `RuntimeStopRequest`
  - `RuntimeService` API 收敛为直接参数形式：
    - `joinNetwork(networkId)`
    - `leaveNetwork(networkId)`
    - `stopRuntime(keepServiceAlive)`
  - `ServiceCommandQueue` 调用链同步收敛，移除上述请求对象构造。
- 说明：
  - `RuntimeStartRequest` 与 `RuntimeTunnelReconfigureRequest` 继续保留，二者承载的是多字段业务上下文，不属于重复包装。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.11 状态/事件与 Runtime 返回模型统一（2026-04-21）
- 已完成：
  - `ServiceState` 改为单对象模型（`type + payload`）：
    - 删除拆分类状态对象（`ServiceStateConnected/Connecting/...`）
    - 统一通过 `ServiceState.*` 工厂方法构造
    - 消费端统一为 `when (state.type)` 分发
  - `ServiceEffect` 改为单对象模型（`type + payload`）：
    - 删除拆分类事件对象（`ServiceEffectJoinSuccess/...`）
    - 统一通过 `ServiceEffect.*` 工厂方法构造
  - `ServiceCommandQueue`、`ServiceStateController`、`ServiceTrafficController`、`ZeroTierVpnService` 全量同步到新模型。
  - 修复 Leave 后状态漂移：
    - 原实现在“还有剩余网络”场景可能保留旧的 Connected 状态。
    - 现改为基于 leave 结果与 runtime 活跃网络重新计算终态（Connected/Stopped）。
  - Runtime 结果对象收敛：
    - 新增 `RuntimeResult`（`type + payload + resultCode`）
    - 删除 `RuntimeStartResult`、`RuntimeStopResult`、`RuntimeLeaveResult`、`RuntimeTunnelReconfigureResult`
    - `RuntimeService` 返回类型统一为 `RuntimeResult`（start/stop/leave/reconfigure）
- 说明：
  - `RuntimeStartRequest` 与 `RuntimeTunnelReconfigureRequest` 继续保留，二者属于多字段业务上下文，不是重复包装。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.12 Runtime Factory 冗余收敛（2026-04-21）
- 已完成：
  - 删除单点消费的工厂类：
    - `NodeKernelStartRequestFactory`
    - `RuntimeTunnelReconfigureRequestFactory`
  - 将构造逻辑内联到实际使用者：
    - `RuntimeService.buildNodeKernelStartRequest(...)`
    - `DefaultServiceCommandQueue` 内联构造 `RuntimeTunnelReconfigureRequest`
  - 白名单与 DNS 规范化逻辑保留在命令队列本地私有方法，行为不变。
- 说明：
  - 该调整减少一次抽象跳转，调用链更短，职责更直接：谁消费、谁组装。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.13 Kernel Bridge Factory Typealias 清理（2026-04-21）
- 已完成：
  - 删除 typealias 文件：
    - `KernelTunTapBridgeFactory.kt`
    - `KernelUdpBridgeFactory.kt`
  - 使用点改为直接函数类型：
    - `NodeKernelStartRequest`：
      - `udpBridgeFactory: ((DatagramSocket) -> KernelUdpBridge)?`
      - `tunTapBridgeFactory: ((Long) -> KernelTunTapBridge)?`
    - `NodeSessionContext`：
      - `createUdpBridgeFactory(): (DatagramSocket) -> KernelUdpBridge`
      - `createTunTapBridgeFactory(): (Long) -> KernelTunTapBridge`
- 说明：
  - 这两个 typealias 在当前架构中不承载独立语义，只增加文件与跳转层级，删除后可读性更直接。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.14 Kernel Stop 请求/结果模型清理（2026-04-21）
- 已完成：
  - 删除冗余模型：
    - `NodeKernelStopRequest`
    - `NodeKernelStopResult`
  - `NodeKernelRuntime.stop(...)` 收敛为基于当前 runtime 会话资源的关闭：
    - `fun stop(): Boolean`
    - 不再接收外部 stop request 对象
  - `RuntimeService` 同步更新读取 `Boolean nodeClosed`，行为保持一致。
- 说明：
  - 现有调用链中从未传入 stop request，属于无效扩展点；保留会造成“停止还要构造请求”的错误心智负担。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.15 快照分层收敛（2026-04-21）
- 已完成：
  - 删除 `model/runtime/kernel/NodeKernelRuntimeSnapshot`。
  - `NodeKernelSession.snapshot()` 返回值改为 kernel 层内部类型 `NodeKernelSessionSnapshot`（位于 `service/runtime/kernel`）。
  - `RuntimeService` 同步改为依赖 `NodeKernelSessionSnapshot` 进行启停与 IO 资源处理。
- 说明：
  - 业务侧仅保留 `RuntimeSnapshot` 作为正式运行态快照模型；
  - kernel 侧保留内部会话快照，仅用于底层资源编排，不再作为 model 层对象对外扩散。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.16 SocketProtector typealias 清理（2026-04-21）
- 已完成：
  - 删除 `KernelSocketProtector.kt`。
  - 使用点改为直接函数类型：
    - `NodeKernelStartConfig.socketProtector: ((DatagramSocket) -> Boolean)?`
    - `NodeSessionContext.socketProtectorRef/setSocketProtector/socketProtector` 同步改造。
- 说明：
  - 该 typealias 不承载额外语义，独立文件仅增加跳转层级。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.17 RuntimeResult 命名统一（2026-04-21）
- 已完成：
  - `RuntimeResult` 结果字段由 `code` 收敛为 `resultCode`，语义与 SDK `resultCode` 对齐。
  - `RuntimeService` 全量调用点改为 `resultCode = ...` 命名参数。
  - `ServiceCommandQueue` 对 `RuntimeResult` 的成功判定与错误映射改为读取 `resultCode`。
  - 彻底移除重复返回模型：`RuntimeExecutionResult`、`RuntimeOperationResult`。
- 说明：
  - Runtime 层保留单一返回对象：`RuntimeResult`，用 `type` 区分场景，用 `resultCode` 表达结果码。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.18 Runtime 启动入参与命名残留收敛（2026-04-21）
- 已完成：
  - 删除 `RuntimeStartConfig`，`RuntimeService.ensureStarted(...)` 改为直接接收 `targetNetworkId: NetworkId?`。
  - `ServiceCommandQueue` 的启动调用链同步改为直接传 `NetworkId?`，移除无效对象构造。
  - `NodeKernelCore` 命名收敛为 `NodeKernelRuntime`，明确其“运行实体”职责；`NodeKernelLauncher` 继续承担“启动器”职责。
  - `RuntimeService` 内部旧命名清理：
    - `buildNodeKernelStartConfig(...)` -> `buildNodeKernelConfig(...)`
    - `toRuntimeNetworkConfigSnapshot(...)` -> `toRuntimeNetwork(...)`
    - `toRuntimePeerSnapshot(...)` -> `toRuntimePeer(...)`
  - 清理注释中的旧名残留（`NodeRuntimeCore` / `快照` 等与当前模型不一致表述）。
- 说明：
  - `NodeKernelConfig` 保留为内核层配置模型，不与 runtime 层启动入参合并，避免把内核资源细节泄漏到服务编排层。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.19 核心能力补全（2026-04-21）
- 已完成：
  - 启动/入网网络门禁补齐（对齐老项目）：
    - 新增 `ServiceStartNetworkGuard`，在 `Join` 前执行网络环境校验：
      - 无网络拦截
      - 蜂窝网络且 `useCellularData=false` 时拦截
  - `StartOrResume` 启动链路补齐：
    - 增加 `targetNetworkId` 回退策略：显式 networkId 优先，否则回退最近激活网络
    - 修复“仅 ensureStarted 不 join”的缺口，改为走完整 `join + reconfigure` 链路
    - `ACTION_START_OR_RESUME` 的显式 networkId 识别改为 `hasExtra`，避免“传了非法 ID 被当作未传”的误判
  - 自定义 Planet 回退补齐（对齐老项目语义）：
    - `SettingsStartupWarmup` 增加“开关开启但文件缺失”自动回退（并持久化关闭）
    - `NodeDataStore` 仅在 `planet.custom` 文件存在时才重定向到自定义 planet
  - 节点/Moon 能力补齐：
    - `RuntimeService` 新增 `queryNodeInfo()`
    - `RuntimeService` 新增 `deorbitMoons(...)`
    - `ServiceCommand` / `ServiceCommandQueue` 新增 `DeorbitMoons` 命令链路
  - Peers 查询能力恢复：
    - `ServiceEffect.PEER_SNAPSHOT_UPDATED` 新增 peers 详情 payload（不再仅计数）
  - 提供 UI 可注入核心能力入口：
    - 新增 `ServiceRuntimeCapabilityProvider`（State/Effect、节点查询、网络查询、Peers、Join/Leave/Start/Stop、Moon 入/退轨、关键设置查询）
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 11. 下一步执行（先内核，固定顺序）
1. 把 UI 网络列表状态改为订阅服务状态流（替换当前本地假状态刷新）。
2. 补充 VPN 权限拒绝/撤销场景的 UI 提示与重试路径。
3. 接入 Peers/Moons 页面真实数据（走 `RuntimeFacade` 查询链路）。
4. 完成真机回归：Join/Leave、切网恢复、白名单绕过、监控模式切换。
5. 回填稳定性门禁并补自动化验证脚本。



## 10.20 主链路收敛重构（2026-04-21）
- 已完成：
  - 主链路统一为：`UI -> ViewModel(ServiceAction) -> ServiceActionDispatcher -> ZeroTierVpnService -> RuntimeService`
  - 命名收敛：
    - `ZeroTierConnectionService` -> `ZeroTierVpnService`
    - `NodeRuntimePipeline` -> `RuntimeService`
  - 架构收敛：
    - 删除 `ServiceCommand.kt`
    - 删除 `ServiceCommandQueue.kt`
    - 删除 `ServiceRuntimeCapabilityProvider.kt`
    - `ZeroTierVpnService` 内部使用 `Mutex` 串行执行所有 `ServiceAction`
  - 动作覆盖补齐：
    - `StartOrResume/Join/Leave/Stop/Reconfigure/Orbit/Deorbit/QueryPeers/QueryNode/QueryNetworkConfig`
    - `EnterMonitorOnly/ResumeRelay`
  - 查询能力可消费化：
    - `ServiceEffect.PEER_SNAPSHOT_UPDATED` 保留 peers 详情 payload
    - 新增 `ServiceEffect.NODE_INFO_UPDATED`
  - 观察控制器接线调整：
    - `ServiceNetworkController` 从“发命令”改为“发 ServiceAction”
  - 清理未接线死代码：
    - 删除 `ZeroTierBootService.kt`
- 验证结果：
  - `:app:compileDebugKotlin` 通过。
- 验收清单：
  - 见 `tasks/main-chain-migration-checklist.md`。

## 10.21 Leave 结果模型收敛（2026-04-22）
- 已完成：
  - 删除 `NodeKernelLeaveResult`。
  - `NodeKernelRuntimeCore.leave(...)` 改为直接返回 `ResultCode?`，与 `join/orbit/deorbit` 一致。
  - `RuntimeService.leaveNetwork(...)` 改为在 service 层计算 `noNetworksLeft`：
    - 离网成功后通过 `runtime.networkConfigs()` 判断是否还存在网络。
    - 保持 `RuntimeResult.leave(noNetworksLeft=...)` 对上层状态机的契约不变。
- 说明：
  - 内核层不再为单一操作维护独立 result 对象，减少模型分叉与跳转成本。
  - `LEAVE` 的业务语义仍保留在 `RuntimeResult`，不影响 `ServiceAction` 状态流处理。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.22 主链路日志补齐（2026-04-22）
- 已完成：
  - 增加统一链路检索关键字：`ZTL_CHAIN`。
  - `ZeroTierVpnService` 增加主链路日志：
    - Service 创建/销毁、命令接收
    - `ServiceAction` 执行开始/结束（含动作类型、核心参数、终态与错误码）
  - `RuntimeService` 增加运行阶段日志：
    - `ensureStarted / joinNetwork / leaveNetwork / establishVpnTunnel / stopRuntime`
    - 关键结果码与网络 ID
    - 异常路径统一前缀输出
  - `ServiceNetworkController` 增加观察链路日志：
    - 网络变化事件
    - Runtime 网络配置回调
    - 重配置触发点
  - `RoutePolicyCoordinator` 增加策略链路日志：
    - 策略评估开始/决策结果
    - 手动保护窗口更新/跳过
    - EnterMonitorOnly/ResumeRelay 触发动作
- 验证结果：
  - `:app:compileDebugKotlin` 通过。
  - `:app:testDebugUnitTest` 通过。

## 10.23 网络卡片视觉收敛与 P2P/LAN 数据回补（2026-04-22）
- 已完成：
  - `NetworkCard` 视觉重构（亮/暗主题统一）：
    - 卡片底色、边框、状态色改为 `MaterialTheme.colorScheme + ZtTheme.semantic` 组合，不再依赖硬编码深色值。
    - 统一信息层级：`状态行 -> 元信息盒 -> 标签/提示行`，调整字号、字重与对比度，提升可读性。
    - `StatusPill / StatusDot / Switch` 保持同源状态色，交互反馈与状态识别一致。
  - 修复左下角 `LAN / direct / relay` 不显示问题：
    - 根因修复：`NetworksViewModel.toListItem()` 原实现将 `isLan=false`、`p2pSummary=""` 写死，导致 UI 条件永远不命中。
    - 新增 `PEER_SNAPSHOT_UPDATED` 消费与 `QueryPeers` 触发链路（连接后自动拉取 peer 快照）。
    - 新增 P2P 摘要聚合：`direct / relay` 计数并映射为摘要文案。
    - 新增 LAN 判定：基于已分配地址识别私有网段（IPv4 RFC1918 / IPv6 ULA fc00::/7）。
  - 文案资源补齐：
    - `network_p2p_summary_direct_only`
    - `network_p2p_summary_relay_only`
    - `network_p2p_summary_mixed`
    - 已同步 `values` 与 `values-zh-rCN`。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.24 网络卡片层级与交互一致性收敛（2026-04-23）
- 已完成：
  - 网络卡片分割线改为复用公共组件 `ItemDivider`，统一全局视觉线条风格。
  - `Network ID / Assigned IP` 保留竖向分割线，但移除内层“次卡片底板”，收敛为单层卡片信息结构，降低颜色密度。
  - 卡片点击交互从“仅缩放”升级为“缩放 + 系统水波纹”，提升可感知反馈。
  - 列表页背景改为 `ZtTheme.background.baseColor`，与卡片默认/激活背景形成清晰三层关系。
  - 单网络启用约束补齐：
    - UI 层：任一网络处理过程中禁用其他卡片开关。
    - ViewModel 层：若已有其他网络启用或处理中，拦截新启用请求并提示“同一时间只能启用一个网络”。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.25 网络卡片颜色语义修正（2026-04-23）
- 已完成：
  - `NetworkCard` 状态色从“跟随主题单色域”改为“语义优先 + 轻主题融合”：
    - Connected 固定语义绿（再与主题主色轻混合）
    - Requesting 固定语义蓝
    - Authentication Required 固定语义琥珀
    - Error 固定语义红
  - 修复问题：
    - 在浅色紫系主题/动态取色场景下，`Connected` 不再与主色同化成紫灰，状态可识别性显著提升。
  - 卡片底色收敛：
    - 激活态仅做轻微状态色染色（低比例），避免出现“整卡大面积染色”的脏感。
    - 未激活态使用 `surfaceContainerLow`，与列表背景保持可感知层级。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.26 胶囊组件统一收敛（2026-04-23）
- 已完成：
  - 新增公共组件：`app/ui/components/common/Pill.kt`。
  - 统一胶囊容器实现（保持各处视觉参数不变）：
    - `NetworkCard`：`StatusPill / LanChip / P2pChip` 改为复用 `Pill`。
    - `NetworkDetailScreen`：状态胶囊与 `LAN` 胶囊改为复用 `Pill`。
    - `SettingScreen`：`SettingActionRow` 的 trailing 状态胶囊改为复用 `Pill`。
  - 说明：
    - 这次仅统一组件实现层，不统一业务样式 token；不同页面的颜色、字号、内边距、边框粗细继续按原值透传，确保视觉不回归。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.27 胶囊圆角令牌统一（2026-04-23）
- 已完成：
  - `Pill` 默认 shape 从 `CircleShape` 切换为主题令牌：
    - 新增 `ZtTheme.shapes` 访问入口（映射 `MaterialTheme.shapes`）。
    - `Pill` 默认使用 `ZtTheme.shapes.extraLarge`，确保胶囊默认圆角跟随全局主题系统。
  - 现状核查：
    - UI 组件中的 `CircleShape` 仅剩“圆点/圆形色块”场景（非胶囊），不纳入 pill 令牌统一范围。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.28 网络卡片与设置卡片视觉系统对齐（2026-04-23）
- 已完成：
  - `NetworkCard` 卡片容器对齐 `SettingsSectionCard` 基础 token：
    - 默认底色：`surfaceContainerHigh.copy(alpha = 0.95f)`
    - 默认描边：`outlineVariant.copy(alpha = 0.35f)`，宽度 `0.6.dp`
    - 圆角：`MaterialTheme.shapes.extraLarge`
  - 激活态策略收敛：
    - 仅在启用态叠加语义强调（边框加重到 `1.dp` + 轻背景染色），避免整卡重染色导致与全局卡片体系脱节。
  - 状态色来源统一到语义色：
    - `CONNECTED -> ZtTheme.semantic.connected`
    - `REQUESTING_CONFIGURATION -> ZtTheme.semantic.root`
    - `AUTHENTICATION_REQUIRED -> ZtTheme.semantic.warning`
    - `DISCONNECTED -> ZtTheme.semantic.inactive`
    - `ACCESS_DENIED / NOT_FOUND -> ZtTheme.semantic.errorStrong`
  - `LAN` 标签同步改为使用 `ZtTheme.semantic.connected`。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.29 列表重排导致目标漂移修复（2026-04-23）
- 已完成：
  - 修复“点击某项后卡片跳到顶部，用户误判为开关错位”的交互缺陷。
  - 根因处理（稳定列表顺序）：
    - `SqliteNetworkDao.listAll()` 去掉 `lastActivated DESC` 排序，改为仅按 `networkId ASC`，避免开关动作触发列表瞬时重排。
  - 写入时机处理（保留恢复语义）：
    - `NetworksViewModel.requestEnableNetwork()` 不再在点击开启时立即写 `setLastActivated(...)`。
    - 新增 `persistLastActivated(...)`，仅在服务状态进入 `CONNECTED` 且网络 ID 变化时持久化最近激活网络。
  - 结果：
    - 列表位置稳定，用户操作目标不再漂移；
    - `lastActivated` 仍可用于后续恢复策略，但不再破坏点击交互连贯性。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.30 LAN 标识残留显示修复（2026-04-23）
- 已完成：
  - 修复场景：上次连接成功后强制 kill app，重启时未连接但列表仍显示 `LAN` 标签。
  - 根因：
    - `LAN` 判定仅基于历史 `assignedIps`（私网/ULA），未关联当前连接态，导致冷启动时显示残留标签。
  - 处理：
    - `NetworksViewModel.applyEntitiesToUiState()` 中将 `LAN` 可见条件收敛为：
      - `isConnected && entity.isLanNetwork()`
    - 列表项与详情项均复用该条件，保证状态一致。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。
