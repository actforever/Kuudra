# Kuudra 最小完整内核实施约束

本文定义 `refactor/kuudra-kernel` 分支的首个**完整但最小**内核，而不是最终产品功能清单。目标是证明所有核心抽象可组合、可取消、可测试；JNativeHook、AWT Robot、HTTP 和 Fat JAR 加载器可以作为后续插件/适配器接入。

## 1. 信号阶段与不可逆边界

```text
SignalSource / RawSignalSource ──> RawSignal
RawSignalAdapter / RawSignalProcessor ──> RootSignal
RootSignalSource 或 SessionProcessor ──> RootSignal
SessionAdmission ──> Signal
SignalAdapter / SignalProcessor / Actor ──> Signal
```

- `RawSignal` 没有会话，允许跨输入聚合，例如双击识别。
- `RootSignal` 是会话创建请求，携带目标 Flow、会话规格和原始信号；它不可回退到 RawSignal。
- `Signal` 已绑定 `sessionId` 与 Flow；会话阶段 Processor 只能处理同一会话的 Signal，不能合并不同 `sessionId`。
- `SessionProcessor` 是常用的 Raw→Root 桥接点；`RootSignalSource` 用于内置定时器或系统触发等无需 Raw 处理的根入口。

## 2. 组件最小契约

| 组件 | 输入 | 输出 | 首期实现 |
| --- | --- | --- | --- |
| SignalSource | 外部世界 | RawSignal | `start/stop` 生命周期与 `RawSignalEmitter` |
| RawSignalAdapter | RawSignal | 0..n RawSignal | 过滤、重命名与 payload 投影 |
| RawSignalProcessor | RawSignal | 0..n RawSignal | 有状态窗口/聚合 |
| SessionProcessor | RawSignal | 0..n RootSignal | 会话名、准入键和策略 |
| RootSignalSource | 外部世界 | RootSignal | 手工、定时或系统根入口 |
| SignalAdapter | Signal | 0..n Signal | 会话内无状态变换 |
| SignalProcessor | Signal | 0..n Signal | 会话内有状态变换；不跨会话融合 |
| Actor | Signal | 0..n Signal | 按绑定串行/并行调用 Action |
| Action | ActionCall | ActionResult | 最小副作用；异步完成并可发出后继 Signal |

所有组件不得直接操作 Runtime 内部队列；只能通过各自 Context/Emitter 返回消息或排放后继信号。

## 3. 会话、上下文与策略

`SessionProcessor` 为 RootSignal 提供 `SessionSpec(name, admissionKey, policy)`。分组键恒为 `(flowId, name, admissionKey)`：

- `PARALLEL`：每个 RootSignal 都创建会话。
- `QUEUED`：活动会话存在时按有界 FIFO 保留 RootSignal；前一个终态后准入一个。
- `TOGGLE`：活动会话存在时只协作取消它，不创建新会话。
- `IGNORE`：活动会话存在时丢弃新 RootSignal。

每个会话有不可变父上下文加 CAS 更新的 session values。每个 Signal 复制上下文视图；分支不可共享可变 `Map`。会话终态为 `COMPLETED`、`CANCELLED` 或 `FAILED`。取消只设置 token，组件/Action 自主决定安全边界；Runtime 不强制中断线程。

## 4. Runtime 与图

`KuudraTaskQueue` 是有界、可替换的内部队列。任务类型为 Raw、Root、Signal；所有阶段均由 Runtime 调度。Raw 与 Root 按入口/Flow 路由，Signal 按 Flow 内显式图边路由。会话内默认串行；Actor 显式 `parallel` 才可并行。Actor/Action 异步完成前维持会话工作引用，引用归零才结束会话。

Flow 生命周期首期实现 `INACTIVE → ACTIVE → STOPPING → STOPPED`；停止关闭入口、对活动会话发送协作取消并 drain。系统事件总线发布 Flow、Session、队列拒绝和组件异常事件，但不参与业务信号流。

## 5. 验收矩阵

1. Source start/stop 只向 Runtime 排放 RawSignal，队列满时按策略拒绝。
2. Raw adapter/processor 可以过滤、重映射并完成双击窗口聚合。
3. SessionProcessor 与 RootSignalSource 都能创建会话；四种策略均有单元测试。
4. Signal adapter/processor 只在会话内传播；分支上下文隔离。
5. Actor 的顺序绑定、并行绑定、Action 失败和协作取消都有单元测试。
6. Flow stop/drain、SystemEventBus 订阅及完整“输入 A×2 → 动作 C”端到端测试均通过。

## 6. 有意不在本阶段实现的内容

完整 YAML/JSON/TOML 解析器、SQLite StateStore、插件 ClassLoader、控制平面 Flow、REST/WebSocket、外部桥接与真实键鼠插件都保留模块边界与 SPI，但不作为最小完整内核的阻塞项。

## 7. 本分支实现结果

- API 已提供 `RawSignal`、`RootSignal`、带会话的 `Signal`，以及 Source、Processor、Actor、Action、Context 和系统事件 SPI。
- Runtime 使用可替换的有界 `KuudraTaskQueue` 统一调度三阶段任务；SessionProcessor 创建会话，带会话的图节点只在原 Flow/Session 内继续路由。
- 四种会话准入策略、会话内顺序、跨会话并行、Action 上下文写入、Action 失败、协作取消、Flow pause/resume/stop drain 都有单元测试。
- Plugin Manager 对已经注册的 Java 插件进行依赖排序，创建插件家目录，并按依赖正序初始化/启动、逆序停止/销毁。插件自行拥有长期资源和循环调度器；隔离 ClassLoader 与 Fat JAR 发现仍不在最小内核范围。
- `kuudra-app` 的双击 A 到模拟 C Demo 从 YAML 子集读取参数，并经 RawSignal、RawSignalProcessor、SessionProcessor、Actor/Action 端到端运行。
