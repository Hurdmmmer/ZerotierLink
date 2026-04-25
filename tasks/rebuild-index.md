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
- [x] Peers 页面接入真实数据
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
  - 内置默认绕过包名（按当前策略仅保留 Android Auto 直连）：
    - `com.google.android.projection.gearhead`
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

## 10.47 SSID 底层网络判定与无目标启动通知修复（2026-04-25）
- 已完成：
  - 修复网络切换后 SSID 内外网判断错误：
    - 根因：`RoutePolicyEvaluator` 使用 `ConnectivityManager.activeNetwork` 作为判断源，VPN 建立后该默认网络可能变成 VPN 自身，导致把真实 Wi-Fi 误判为非 Wi-Fi 或无法读取 SSID。
    - 处理：策略评估改为解析真实底层网络能力，优先使用非 VPN 的 active network，必要时遍历 `allNetworks` 并过滤 `NET_CAPABILITY_NOT_VPN / INTERNET`，按 Wi-Fi / Ethernet / Cellular 优先级选择。
    - 结果：VPN 前台服务存在时，SSID 策略不再被 VPN 自身网络能力干扰，内网进入 `MONITOR_ONLY`、外网/蜂窝恢复内核的判断源与 `NetworkChangeObserver` 保持一致。
  - 修复通知栏存在但网络卡片开关关闭的假状态入口：
    - 根因：`StartOrResume` 先发 `STARTING` 状态再判断是否存在目标网络；当没有最近激活网络时，前台通知可能被短暂拉起，但 UI 没有对应运行网络。
    - 处理：先解析目标网络，确认存在后才进入 `STARTING`；无目标时直接保持 `STOPPED` 并记录日志。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.48 自动切到蜂窝后恢复内核修复（2026-04-25）
- 已完成：
  - 修复日志复现场景：
    - `ServiceNetworkController` 已识别 `wifi_to_cellular`；
    - 但 `RoutePolicyEvaluator` 重新读取系统网络能力时仍可能命中短暂残留的 Wi-Fi capability；
    - 随后 SSID 读取失败，返回 `current_wifi_unknown + KEEP_RUNNING`，导致 `MONITOR_ONLY` 下没有执行 `ResumeRelay`。
  - 处理：
    - `NetworkChangeObserver` 的 `event.to` 传入 `RoutePolicyCoordinator`；
    - `RoutePolicyEvaluator.evaluate(...)` 接收 `observedTransport`；
    - 当观察到 `CELLULAR` 或 `ETHERNET` 时，直接判定为已离开内网并返回 `RESUME_RELAY`，不再二次读取 SSID。
  - 观测增强：
    - 网络变化日志增加 `from/to`，便于真机日志直接确认切网方向。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.49 点击开启网络后立即启动前台通知（2026-04-25）
- 已完成：
  - 修复通知展示入口语义：
    - 原链路等服务状态进入 `MONITOR_ONLY / CONNECTING / CONNECTED` 后，由 `ServiceStateController` 启动前台通知；
    - 现改为 `ZeroTierVpnService.onStartCommand` 收到 `Join` 命令后立即绑定连接中通知并调用 `startForeground`；
    - 后续真实状态仍由 `ServiceStateController` 覆盖通知内容。
  - 提升系统展示优先级：
    - `ServiceNotificationController` 的基础通知 Builder 增加 `NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE`，请求系统尽快显示前台服务通知。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.50 Peers/Moons 统计格底色统一与老项目图标迁移（2026-04-25）
- 已完成：
  - 统一 Peers 与 Moons 顶部统计小卡片底色：
    - 统计格子统一使用 `surfaceContainerHighest` 半透明背景；
    - 各指标颜色只用于边框与数值强调，避免多色背景造成页面不一致。
  - 抽取统计小卡片公共组件：
    - 新增 `common/SummaryMetricCell.kt`；
    - Peers 与 Moons 删除各自本地重复实现，统一引用公共组件，后续样式调整只需改一处。
  - 启动图标恢复为老项目原图标：
    - `ztlink_launcher*` 资源复制自 `D:\IdeaProject\ZerotierFix` 的原始 launcher 图标；
    - adaptive icon 结构保持背景色独立、前景图层独立，Manifest 继续指向 `@mipmap/ztlink_launcher`。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.51 开机自启前台服务启动链路修复（2026-04-25）
- 诊断结论：
  - Manifest 已注册 `BootCompletedReceiver`，并声明 `RECEIVE_BOOT_COMPLETED`，不是“没有注册系统广播”。
  - 开机广播派发的是 `StartOrResume`，该动作走 `startForegroundService()`，但服务入口没有立即调用 `startForeground()`。
  - Android 要求前台服务启动后尽快进入前台；该竞态会导致开机自启被系统终止，看起来像广播未生效。
- 已完成：
  - `StartOrResume` 收到命令后立即绑定连接中通知并调用 `startForeground()`；
  - `StartOrResume` 若没有可恢复网络，主动退出前台通知并 `stopSelf()`，避免空前台服务残留。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.52 Wi-Fi 自动切回时 SSID 延迟可读修复（2026-04-25）
- 诊断结论：
  - 真机日志显示 `cellular_to_wifi from=CELLULAR to=WIFI` 已收到，说明网络观察没有漏回调。
  - 策略随后返回 `current_wifi_unknown + KEEP_RUNNING`，导致没有进入仅监听；根因是系统自动切回 Wi-Fi 时 SSID 可能比网络能力更晚可读。
- 已完成：
  - SSID 读取优先使用当前 Wi-Fi `NetworkCapabilities.transportInfo` 中的 `WifiInfo.ssid`，再回退到 `WifiManager.connectionInfo`。
  - `ServiceNetworkController` 在观察到 `to=WIFI` 后增加 1s、3s、6s 的 SSID 稳定复检。
  - `RoutePolicyCoordinator` 对已处于 `MONITOR_ONLY` 的场景增加幂等保护，避免延迟复检重复停内核。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.53 Android 12+ 网络回调 SSID 脱敏修复（2026-04-25）
- 诊断结论：
  - 真机日志显示 1s、3s、6s 的 Wi-Fi SSID 稳定复检均执行，但策略仍返回 `current_wifi_unknown`。
  - 根因不是等待时间不足，而是 Android 12+ 默认 `ConnectivityManager.NetworkCallback()` 会对 `NetworkCapabilities.transportInfo` 中的 `WifiInfo.ssid` 做位置信息脱敏。
  - 因此即使网络已是 Wi-Fi，策略评估仍只能拿到 `<unknown ssid>`，无法命中内网 SSID 并关闭内核。
- 已完成：
  - `NetworkChangeObserver` 在 Android 12+ 使用 `ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO` 注册网络回调。
  - 网络变化事件新增 `wifiSsid` 字段，直接把回调中携带的 SSID 传给路由策略。
  - Wi-Fi 延迟复检改为读取观察器缓存的最近 SSID，避免复检时再次依赖被脱敏的同步读取。
  - 启动阶段 `checkStartPolicy` 改为与自动复检共用同一观察器快照（`observedTransport + observedWifiSsid`），避免“启动判断”和“运行时判断”走不同数据源。
  - 策略日志增加观察 SSID，后续真机日志可直接确认回调是否拿到 SSID。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.54 运行时 SSID 从快照改为实时读取（2026-04-25）
- 诊断结论：
  - 用户侧诉求明确：SSID 判断应为“先判当前传输是 Wi-Fi，再实时读取当前连接 SSID”，不应依赖历史快照。
  - 先前实现里 `currentWifiSsid()` 优先来源是观察器缓存能力，存在“回调滞后即数据滞后”的风险。
- 已完成：
  - `NetworkChangeObserver.currentWifiSsid()` 调整为实时查询优先：
    - 先实时读取当前底层网络能力（active + allNetworks）并取 Wi-Fi transportInfo SSID；
    - 最后回退到 `WifiManager.connectionInfo`；
    - 明确移除“观察器缓存能力”兜底路径，避免陈旧 SSID 参与策略判断。
  - 自动策略复检入口在 `observedTransport == WIFI` 时强制实时再次读取 `currentWifiSsid()`，覆盖外部传入值。
  - 启动策略与自动复检继续共用同一运行时读取链路，保证判定行为一致。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.55 移除手动保护窗口并收敛为单启动闸门（2026-04-25）
- 诊断结论：
  - 真实日志已证明 SSID 可读且命中内网，但被 `manual_protection_window` 拦截，导致“内网打开开关仍会启动内核”。
  - 该窗口逻辑与“runtime 启动前单闸门”架构冲突。
- 已完成：
  - 完整移除 `manual_protection_window` 相关状态、常量和分支。
  - `checkStartPolicy` 改为仅按实时网络状态判断，不再使用 `observedTransport`，避免启动瞬间事件抖动误判。
  - `StartOrResume` 链路删除手动保护窗口设置逻辑。
  - 自动策略在进入仅监听前仅保留幂等判断（已是 `MONITOR_ONLY` 时跳过）。
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
3. 接入 Moons 页面真实数据（走 `RuntimeFacade` 查询链路）。
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

## 10.31 Peers 页面真实数据接入与卡片风格统一（2026-04-23）
- 已完成：
  - 新增 `PeersViewModel`：
    - 订阅 `ServiceStateStore.state/effects`。
    - 消费 `ServiceEffect.PEER_SNAPSHOT_UPDATED` 并映射为 `PeerListItem`。
    - 支持自动查询（进入 `CONNECTED/MONITOR_ONLY`）与手动刷新（`QueryPeers`）。
  - 新增 `PeersUiStatus` 模型：
    - `PeerRoleType` / `PeerPathType` / `PeerListItem` / `PeerSummary`。
    - 支持 `direct/relay/planet/moon/leaf` 计数聚合与 Root Server IP 提取。
  - 重构 `PeersScreen`：
    - 使用 `Scaffold + AppTopBar + BouncyOverScroll + LazyColumn`。
    - 接入 `ObserveUiEvents` 与刷新动作。
    - 空态区分“未连接网络”和“已连接但暂无 peers”。
  - 新增 `PeerCard`，视觉层级对齐网络卡片：
    - 结构：`状态行 -> 元信息行 -> 路径行`。
    - 复用公共组件：`Pill`、`ItemDivider`。
    - 语义色区分 `direct/relay/root`。
  - 文案资源补齐（`values/strings.xml`）：
    - `peers_*`、`peers_card_*` 系列键。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.32 Peers 数量异常与中英文本地化修复（2026-04-23）
- 已完成：
  - 修复 Peers 可能“只显示 1 条”的核心风险点：
    - `RuntimeService.toRuntimePeer()` 改为“首选路径优先（preferred path）”，与老项目行为一致。
    - `peerId` 统一改为固定宽度无符号十六进制（`Long.toUnsignedString(...).padStart(10)`），避免不同格式导致列表 key 不稳定。
    - `PeersScreen` 列表改为 `itemsIndexed` + 含索引兜底 key，避免重复 key 造成条目复用错位/丢失。
    - 新增页面前台补刷机制：每次进入 Peers 页面触发“立即刷新 + 两次短间隔补刷”，降低连接初期快照不完整导致的长期显示偏少问题。
  - 对齐老项目展示语义：
    - 继续保留地址/角色/版本/延迟/路径信息结构，路径来源改为 preferred path 优先。
  - 补齐中英文文案：
    - `values-zh-rCN/strings.xml` 新增全量 `peers_*` 与 `peers_card_*`。
    - `values/strings.xml` 更新 `screen_peers_subtitle` 为真实语义文案。
  - 注释补齐：
    - `PeersViewModel`、`PeersScreen`、`PeersUiStatus`、`RuntimeService` 关键逻辑新增中文注释，解释“为什么这样做”。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.33 Peers 查询风暴与网络配置变更语义修复（2026-04-23）
- 已完成：
  - `PeersViewModel` 查询节流与去重：
    - 新增前台进入防抖（同网络短时间重复 `ON_START` 不重复拉取）。
    - 非用户触发查询增加最小时间间隔，避免密集重复 `QueryPeers`。
    - 查询进行中禁止再次发起，避免并发 Query 叠加。
    - `screen started` 补刷改为“仅在 peers<=1 时触发”，且补刷过程中一旦 peers>1 或网络切换即提前停止。
  - `SyncNetworkConfig` 变更语义修复：
    - `ServiceAction.SyncNetworkConfig` 增加 `configChanged` 字段，并通过 Intent 透传到 `ZeroTierVpnService`。
    - `ZeroTierVpnService.handleSyncNetworkConfig(...)` 发射 `NETWORK_CONFIG_CHANGED` 时使用真实 `configChanged`（且要求 runtime 配置可读），不再固定 `changed=true`。
  - `ServiceNetworkController` 收敛：
    - 先计算 `configChanged`，再派发 `SyncNetworkConfig(configChanged=...)`。
    - 对 `VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY` 不再派发 `SyncNetworkConfig`，避免离网收尾阶段的无效同步日志噪声。
  - 观测日志增强：
    - `ZeroTierVpnService.handleQueryPeers()` 新增 peers 数量日志，便于定位“只显示 1 个”是否来自 runtime 快照本身。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.34 Peers 列表末项被底栏遮挡修复（2026-04-23）
- 已完成：
  - 修复场景：Peers 页面滚动到列表底部时，最后一个 Leaf 卡片可能被底部 Tab 栏遮挡，无法完整显示。
  - 根因：
    - Peers 页面未消费外层主壳 `Scaffold` 的底栏占位（`tabBottomPadding`），仅使用了页面内 `Scaffold` 的 `innerPadding`。
  - 处理：
    - `PeersScreen` 新增 `externalBottomPadding` 参数。
    - `LazyColumn` 的底部 `contentPadding` 叠加 `externalBottomPadding`，确保底栏上方保留可见滚动空间。
    - `ZerotierNavHost` 在 `Peers` Tab 路由中传入 `tabBottomPadding`。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.35 首次 CONFIG_UPDATE 误触发重建修复（2026-04-23）
- 已完成：
  - 修复场景：
    - 某些会话中 `VIRTUAL_NETWORK_CONFIG_OPERATION_UP` 已是完整 `OK` 配置，
      但随后第一条 `CONFIG_UPDATE` 仍被判定为“变化”，导致一次不必要隧道重建。
  - 根因：
    - 配置指纹缓存此前仅在 `CONFIG_UPDATE` 时初始化；当首条 `CONFIG_UPDATE` 到来时旧值为空，天然判定为变化。
  - 处理：
    - 在 `ServiceNetworkController` 的 `OP_UP` 分支预热指纹缓存（仅写入，不判定变化）。
    - 保持 `OP_UP` 仍可同步配置，但避免后续“同内容首条 UPDATE”误判。
  - 额外清理：
    - 移除 `when(op)` 中冗余分支，消除该文件相关编译警告噪声。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.36 Peers 底部可见性兜底增强（2026-04-23）
- 已完成：
  - 修复场景：
    - 在存在顶部 `peer summary` 卡片与底部 Tab 栏叠加时，部分设备上最后一个 Leaf 卡片仍可能“滚不全”。
  - 处理：
    - `PeersScreen` 改为显式尾部占位项：
      - 计算 `listBottomSpacerHeight = externalBottomPadding + innerPaddingBottom + space24`；
      - 在 `LazyColumn` 末尾追加 `peer-list-bottom-spacer`。
    - 同时将 `LazyColumn` 的 `contentPadding.bottom` 收敛为基础小间距，避免仅依赖 padding 在某些布局组合下失效。
  - 结果：
    - 最后一项可见空间由“隐式 padding”改为“显式可滚动占位”，底部遮挡风险显著降低。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.37 Peers 卡片紧凑化重排（2026-04-23）
- 已完成：
  - 设计目标：
    - 解决“卡片高度偏大、信息量偏少”的视觉密度问题，在不丢字段前提下提升扫读效率。
  - `PeerCard` 重构：
    - 从三段大区块（标题/双列元信息/路径）改为紧凑三行：
      - 第一行：`status dot + peerId + role pill`
      - 第二行：`path pill + latency chip + version chip`
      - 第三行：`endpoint 单行`
    - 删除两条 `ItemDivider` 与竖向分栏，减少冗余留白。
    - 卡片内边距从 `14x14` 收敛到 `12x10`，纵向间距从 `12` 收敛到 `8`。
    - 新增 `CompactInfoPill`，统一 latency/version 的轻量信息胶囊样式。
  - 结果：
    - 单卡高度明显下降，列表同屏可见条目增加；
    - 关键信息（角色/路径/延迟/版本/端点）完整保留，阅读路径更短。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.38 Peers 左右平衡布局优化（2026-04-23）
- 已完成：
  - `PeerSummaryCard` 从“左对齐 pills”改为“左右平衡统计面板”：
    - 顶部：标题 + 总数 badge。
    - 中部：`NETWORK` 左侧标签 + 右侧 networkId 右对齐。
    - 底部：两行三列等宽统计格（Direct / Relay / Planet / Moon / Leaf / Total），解决视觉重心偏左问题。
  - `PeerCard` 从“信息都堆在左侧”改为“左主信息 + 右指标”：
    - 第二行：左侧 `Path`，右侧 `Latency`。
    - 第三行：左侧 `Endpoint`，右侧 `Version`。
    - 保持紧凑高度的同时，卡片信息分布更均衡，扫读路径更清晰。
  - 文案补齐：
    - `values/strings.xml`、`values-zh-rCN/strings.xml` 新增
      - `peers_summary_count_badge`
      - `peers_summary_total_short`
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.39 Peers Summary 数量 Badge 轻量化（2026-04-23）
- 已完成：
  - 修复场景：
    - `PeerSummary` 右上角总数 badge 视觉占位偏大，抢占标题层级。
  - 处理：
    - 文案由“数字+单位”改为纯数字（减少横向占位）。
    - badge 内边距从 `8x3` 收敛到 `6x1`。
    - 边框从 `0.5dp` 收敛到 `0.4dp`，边框/底色透明度同步下调。
    - 文本样式下调到 `10sp + Medium`，降低视觉权重。
  - 结果：
    - badge 更像“辅助计数”，不会压过标题与统计网格。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.40 Peers SummaryMetricCell 高度紧凑化（2026-04-23）
- 已完成：
  - 修复场景：
    - `PeerSummary` 统计网格中的 `SummaryMetricCell` 纵向占位仍偏大，导致 Summary 区块视觉重量过高。
  - 处理：
    - `SummaryMetricCell` 从“上下两行（数字+标签）”改为“同一行（数字+标签）”。
    - 内边距由 `6x4` 收敛为 `6x3`，进一步压缩高度。
    - 数字样式由 `titleSmall 13sp` 下调为 `labelLarge 12sp`。
    - 标签样式由 `10sp` 下调为 `9sp`，并保持单行省略。
  - 结果：
    - 指标格高度明显降低，Summary 卡片更轻更紧凑，同时保留直连/中继/角色统计信息完整性。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.41 Peers Summary 视觉回调（2026-04-23）
- 已完成：
  - 右上角计数 badge 还原：
    - 文案从纯数字恢复为“数字 + peers/节点”。
    - 胶囊内边距、边框和字号恢复到更醒目的层级。
  - `SummaryMetricCell` 密度回调：
    - 从“单行极小”改回“上下两行中等密度”。
    - 提升可读性并保持网格节奏，避免摘要卡片显得过轻。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.42 Moons 功能按老项目补全（2026-04-23）
- 已完成：
  - 数据层接入：
    - 新增 `moon_orbits` 表与 DAO：
      - `MoonOrbitDbEntity`
      - `MoonOrbitDao`
      - `SqliteMoonOrbitDao`
    - `ZtAppDbHelper` 数据库版本升级到 `2`，补充 `oldVersion < 2` 的建表迁移。
    - `DatabaseDiManage` 注入 `MoonOrbitDao`。
  - 服务链路补全：
    - `ZeroTierVpnService` 注入 `MoonOrbitDao`。
    - `handleJoin` 成功后自动读取已保存 Moon 并执行 `orbit`，对齐老项目“Join 后自动应用 Moon”行为。
  - 页面与交互补全：
    - `MoonsScreen` 从占位页改为完整业务页：
      - 顶栏：刷新 + 新增入口。
      - 摘要卡：总数、来源（File/Orbit）、缓存状态（Cached/Wait）。
      - 列表卡：World ID、Seed、来源、缓存状态。
    - 新增 `MoonsViewModel`：
      - 列表加载与摘要聚合。
      - 手动 Orbit 入轨（World ID + Seed）。
      - 文件导入入轨（校验 moon 文件头 `0x7f`，提取 World ID，写入 `moons.d/%016x.moon`）。
      - 复制 World ID、删除入轨、删除缓存。
      - 已连接场景下新增后立即派发 `OrbitMoons`；未连接则仅持久化，等待后续 Join 自动应用。
    - 新增 `MoonCard` 与 `MoonsUiStatus`，复用公共组件（`AppTopBar` / `BouncyOverScroll` / `Pill` / `ItemDivider`）。
    - `ZerotierNavHost` 的 `MoonsScreen` 接入 `tabBottomPadding`，避免末项遮挡。
  - 文案补齐：
    - `values/strings.xml`
    - `values-zh-rCN/strings.xml`
    - 补全 moons 页面、动作、校验、导入、菜单与提示文案。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.43 监控态开关恢复与自动切网恢复修复（2026-04-24）
- 已完成：
  - 修复“手动关闭后重进仍默认开启”：
    - 根因：手动 `Leave` 后未清理 `lastActivated`，后续 `StartOrResume`（如开机自启链路）仍会回选该网络。
    - 处理：新增 `NetworkRepository.clearLastActivated()`，在 `ZeroTierVpnService.handleLeave` 的 `noNetworksLeft=true` 分支中清理最近激活网络。
  - 修复“WiFi -> 蜂窝未自动恢复内核”：
    - 根因：策略评估器把“当前非 WiFi 网络”归类为 `current_wifi_unknown + KEEP_RUNNING`，监控态下不会触发 `ResumeRelay`。
    - 处理：`RoutePolicyEvaluator` 新增非 WiFi 活跃网络判定，返回 `RESUME_RELAY`（`inIntranet=false`），从监控态切回转发。
  - 修复“离网后被网络回调误拉入监控态”：
    - 处理 1：`RoutePolicyCoordinator` 在自动复检前增加 `service_not_running` 短路。
    - 处理 2：`ZeroTierVpnService.handleEnterMonitorOnly` 增加保护：服务已 `STOPPED` 且无活动网络时忽略该动作，避免竞态回流。
  - 稳定性增强：
    - SSID 读取归一化时过滤空值和 `<unknown ssid>`，避免误判触发策略切换。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.44 开机自启重复触发去重（2026-04-24）
- 已完成：
  - 修复场景：
    - 调试阶段（Android Studio 反复重启进程）出现 `boot_autostart` 重复触发，导致“进程重启后开关又被自动拉起”。
  - 根因：
    - `BootCompletedReceiver` 仅按 `startOnBoot` 开关决策，缺少“同一次开机只处理一次”的幂等保护。
  - 处理：
    - `SettingsStore` 新增 `lastHandledBootCount` 读写能力；
    - `BootCompletedReceiver` 读取 `Settings.Global.BOOT_COUNT`，若与已处理值一致则跳过；
    - 首次成功派发 `boot_autostart` 后持久化当前 `bootCount`。
  - 结果：
    - 同一次开机内重复收到开机广播（或调试重启导致的重复触发）不会再次自动拉起网络。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.45 后台耗电提示收敛（2026-04-24）
- 已完成：
  - 修复 `ZeroTierVpnService` 停止后仍可能被系统按 `START_STICKY` 空命令重建的问题：
    - 无法解析有效服务动作时，立即 `stopSelf(startId)` 并返回 `START_NOT_STICKY`。
    - 手动 Stop 且 `keepServiceAlive=false` 后主动 `stopSelf()`。
    - 最后一个网络 Leave 后进入 `STOPPED` 时主动 `stopSelf()`，避免服务实例继续保留网络观察器。
  - 修复 TUN 输入异常后的低间隔轮询风险：
    - 旧逻辑在输入流未切换时每 30ms 返回同一个 `FileInputStream` 重试；
    - 若 FD 已失效，可能形成后台反复读同一坏 FD 的小轮询；
    - 现改为只等待新输入流或线程中断，避免空闲/异常状态持续唤醒。
- 说明：
  - 未发现显式 `WakeLock`、`AlarmManager` 或重复定时任务；系统耗电提示更可能来自 VPN 前台服务常驻、服务空重建、TUN/UDP/native runtime 线程活跃。
  - 连接态 VPN 本身仍会被系统归类为高耗电应用，这是常驻隧道类 App 的正常风险；本次处理的是可避免的空转与停止后驻留。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。

## 10.46 对齐 ClashMetaForAndroid 的灭屏省电策略（2026-04-24）
- 参考结论：
  - `D:\IdeaProject\ClashMetaForAndroid\service\src\main\java\com\github\kr328\clash\service\clash\module\SuspendModule.kt`
    - 根据 `PowerManager.isInteractive` 和 `ACTION_SCREEN_ON/OFF` 控制 `Clash.suspendCore(...)`。
  - `D:\IdeaProject\ClashMetaForAndroid\service\src\main\java\com\github\kr328\clash\service\clash\module\DynamicNotificationModule.kt`
    - 灭屏后停止 1 秒流量通知刷新，仅亮屏时动态更新可视通知。
  - `D:\IdeaProject\ClashMetaForAndroid\service\src\main\java\com\github\kr328\clash\service\clash\module\NetworkObserveModule.kt`
    - 网络监听使用 `NET_CAPABILITY_NOT_VPN / INTERNET / NOT_RESTRICTED` 过滤真实底层网络，避免 VPN 自身干扰网络变化策略。
- 已完成：
  - `ServiceTrafficController` 新增灭屏降频：
    - 连接态仍可亮屏刷新流量通知；
    - 灭屏后不再按 1.5s/3s 刷新可视通知，改为 60s 保守等待，减少锁屏后台唤醒。
  - `NetworkChangeObserver` 改为监听真实底层网络：
    - 使用 `NetworkRequest` 过滤非 VPN、可联网、非受限网络；
    - 缓存回调中的 `NetworkCapabilities` 并按 Wi-Fi / Ethernet / Cellular 优先级选择当前传输类型；
    - 回退读取默认网络时同样排除 VPN，避免 VPN 建立后把自身误判为底层网络。
- 未直接照搬：
  - 未实现 `suspendCore(true)` 等价能力，因为 ZeroTier SDK 当前没有“暂停核心但保持节点会话”的明确 API；强行停 Node 会改变在线状态与恢复语义。
  - 当前只停可视通知刷新和策略噪声，核心隧道仍保持在线，保证 VPN 功能不被锁屏破坏。
- 验证结果：
  - `:app:compileDebugKotlin` 通过。
