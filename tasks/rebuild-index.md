# ZerotierLink 重构索引（功能不减、稳定性不降）

## 1. 目标与红线
- 目标：把 `D:\IdeaProject\ZerotierFix` 的核心能力重构到 `D:\AndroidProject\ZerotierLink`。
- 红线：
  - 不丢功能
  - 不降稳定性
  - 不引入不可观测状态（失败必须可定位）

## 2. 当前阶段（2026-04-14）
- 已完成：
  - Compose UI 主框架与页面骨架
  - 设置模块（DataStore）
  - 开机广播与开机启动服务骨架
  - Join 第一批领域实体定义（`domain/network`）
  - 入网授权状态建模（`ZeroTierStatusMapper`、`ConnectionState`）
- 未完成（核心缺口）：
  - Join/Leave 真正连接链路
  - Runtime 管理与隧道编排
  - 网络切换策略恢复
  - 运行状态通知联动
  - Peers/Moons 真数据联动

## 3. 架构清单（最终落地）

### 3.1 服务与核心组件
- [ ] `ZeroTierConnectionService`（唯一前台服务，连接生命周期编排）
- [ ] `RuntimeFacade`（唯一内核调用入口）
- [ ] `ServiceCommandQueue`（Join/Leave/Pause/Resume/Stop 串行命令）
- [ ] `ServiceStateStore`（单一状态源，StateFlow）
- [ ] `ServiceNotificationController`（运行中/暂停中转/停止/错误通知）
- [ ] `NetworkChangeObserver`（网络变化监听组件，非独立 Android Service）
- [ ] `BootCompletedReceiver`（开机自启入口）

### 3.2 数据与用例层
- [ ] `NetworkRepository`（网络配置持久化，upsert）
- [ ] `JoinNetworkUseCase`
- [ ] `LeaveNetworkUseCase`
- [ ] `PauseRelayUseCase`
- [ ] `ResumeRelayUseCase`

### 3.3 状态与事件模型（简化且不丢能力）
- [ ] `ServiceState` 定义：
  - `Stopped`
  - `Starting`
  - `Connecting(networkId)`
  - `Connected(networkId)`
  - `MonitorOnly`
  - `Stopping`
  - `Error(code, message)`
- [ ] `ServiceEffect` 定义：
  - `JoinSuccess`
  - `JoinFailed`
  - `LeaveDone`
  - `RuntimeRecovered`

## 4. 功能清单（对齐老项目）

### 4.1 连接与网络管理
- [ ] 加入网络（Join）
- [ ] 离开网络（Leave）
- [ ] 网络列表状态实时刷新
- [ ] 网络详情读写（default route / dns mode）
- [ ] 默认路由开关生效
- [ ] DNS 模式生效（none/network/custom）

### 4.2 路由策略与恢复
- [ ] 暂停中转（Monitor Only）
- [ ] 恢复中转
- [ ] 网络切换自动检测与策略执行
- [ ] 手动连接保护窗口（防止刚连上就被自动策略回切）

### 4.3 启动与生命周期
- [ ] 开机自启（受设置开关控制）
- [ ] 前台服务通知状态同步
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
- [ ] 输入校验：16 位十六进制 networkId
- [ ] DNS 输入校验（custom 时强校验）
- [ ] 落库（upsert）
- [ ] 发命令到 `ZeroTierConnectionService`
- [ ] Runtime 执行 `ensureStarted + joinNetwork`
- [ ] 回写状态到 `ServiceStateStore`
- [ ] UI 展示 connecting/connected/failed
- [ ] 通知状态联动

## 6. 迁移映射（老 -> 新）
- [ ] `ZeroTierOneService` -> `ZeroTierConnectionService`
- [ ] `RuntimeCoreController` -> `RuntimeFacade` + `ServiceCommandQueue`
- [ ] `RoutePolicyEngine` -> `RoutePolicyEngine`（保留决策职责）
- [ ] `NetworkChangeObserver` -> `NetworkChangeObserver`（组件化复用）
- [ ] `ServiceNotificationController` -> `ServiceNotificationController`
- [ ] `StartupReceiver` -> `BootCompletedReceiver`

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

