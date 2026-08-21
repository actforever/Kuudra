# Kuudra：事件流自动化内核架构设计

## 1. 目标与边界

Kuudra 是一个本地自动化事件流内核。它不再把“宏”当作一等抽象，而是让用户用可组合的组件描述：**信号从哪里来、如何变换、如何识别状态/模式，以及信号出现后执行什么动作**。

首期目标是桌面输入与 GTAV 自动化，但内核不得依赖 GTAV、JavaFX、JNativeHook 或 AWT Robot。它必须支持插件提供这些能力，并通过 HTTP API 被 TUI、JavaFX Dashboard 或其他客户端控制。

非目标：分布式消息系统、远程不可信插件执行、通用工作流编排。内核首先是单机、低延迟、可取消、可观察的自动化引擎。

## 2. 现有仓库的结论

仓库处于三次架构演进并存的状态：

- `eventflow-core/.../automation` 是旧的 `InputSource → ActivationPolicy → Trigger → Macro → Action` 桥接模型；大量 GTA 行为类直接固化在其中。
- `eventflow-*` 已尝试转为 `Event → Mapper → Command → Action`，并出现了 `Runtime`、队列、路由、插件模板、会话引用计数等正确方向的概念。
- 根 `pom.xml` 当前只构建 `orcana-core/runtime/plugin/config`，但这四个模块基本只有脚手架；旧 `eventflow-*` 已脱离当前反应堆。Web 控制器也是空壳。

这说明不能把旧代码直接“搬入”新模块。特别是下面的边界不应继承：

1. `Trigger` 同时承担输入监听、手势识别、并发控制和业务触发，导致桥接两侧和宏彼此耦合。
2. `Command` 是事件到动作之间的硬编码中间层；动作若产生后继信号又需绕回特殊路径，无法自然表达网状链路。
3. `DefaultRuntimeSession`、`SessionContext`、部署和插件管理尚未完成，且 `DefaultRuntime` 仍是单工作线程原型，不能作为新内核的兼容基础。
4. `eventflow-web` 的 API 仍面向旧“宏配置”持久化，不是内核运行管理 API。

旧的按键解析、JNativeHook 适配、AWT Robot 操作、手势测试可以作为插件实现的参考和迁移素材；其架构接口不应成为新内核 API。

## 3. 核心模型

### 3.1 统一消息

所有组件只接收和发出 `SignalEnvelope`。不再区分内核级的 Event 与 Command。

```java
public record SignalEnvelope(
    UUID messageId,
    UUID sessionId,
    String type,                 // 例如 input.key.pressed、gesture.a.double
    Instant occurredAt,
    ComponentRef producer,
    Map<String, Object> payload, // JSON 兼容、不可变
    ContextDelta contextDelta,   // 本跳新增/修改的会话上下文
    Trace trace                  // 路径、深度、因果关系
) {}
```

约束：

- `type` 是稳定的点分命名空间，插件名称应在前缀内，例如 `jnativehook.key.pressed`。
- `payload` 表达事实；不允许组件修改已经入队的消息。
- `contextDelta` 表达本跳产生的、需要传给后继链路的值；运行时在分发时创建新的上下文视图。
- `messageId` 唯一，`trace.causationId` 指向直接父消息，`trace.rootMessageId` 便于关联观测。
- Payload、上下文和配置值必须是 JSON 值（null、boolean、number、string、array、object）。插件内部对象不可泄漏到消息边界。

### 3.2 五类组件

| 组件 | 输入 | 输出 | 职责 |
| --- | --- | --- | --- |
| 信号源 Source | 外部世界 | 0..n 信号 | 监听键鼠、定时器、HTTP、游戏状态等 |
| 过滤器 Filter | 信号 | 0..n 信号 | 筛选、重命名、投影/补充 payload、限流；无跨消息状态 |
| 路由器 Router | 信号流 | 0..n 信号 | 有状态识别、聚合、窗口/超时、分支；每个会话隔离状态 |
| 执行器 Executor | 信号 | 动作结果和 0..n 信号 | 按绑定规则调用插件动作，可生成后继信号 |
| 动作 Action | 参数与执行上下文 | 结果 | 最小的副作用单元，例如 `robot.keyTap` |

过滤器不是“信号源专属”或“执行器专属”对象，而是图中的普通处理节点。为方便配置，Source 和 Executor 可声明内联 `postFilters`；装配器将其展开成显式边。

### 3.3 图而不是线

一个 Flow（流定义）是版本化有向图：节点为组件，边为订阅规则。一个 Source 发出的信号是会话根；同一会话可在多条边分裂，也可由任意节点再次入队。允许环，但默认须显式写 `allowCycle: true`，且受最大深度、重复边和速率保护。

```
Source ──> Filter ──> Router ──> Executor ──> Filter ──┐
                  │                                     │
                  └────────────> Executor <──────────────┘
```

“路由”应理解为选择后继处理器的规则层，不是单独、全局的消息队列消费者。Router 可同时是图节点：它消费信号、维护状态，并产生更高层信号。

## 4. 运行时、队列与并发

### 4.1 调度模型

- 整个 Runtime 只维护一个有界的、共享的 `SignalQueue`。队列项是 `(envelope, targetNodeId, flowRevision)`，而不是“广播后让全部组件扫描”。所有信号源、过滤器、路由器和执行器产生的信号都经由它调度。
- Flow 不是物理队列隔离单元，而是**逻辑隔离单元**：每项任务都携带确定的 Flow revision、会话 ID 和目标节点；只能路由到该 Flow 图内允许的边。不同 Flow 共享背压和工作池，但不共享上下文、路由器状态或组件实例，除非显式使用全局上下文/共享插件资源。
- 为避免一个 Flow 挤占全局队列，调度器按 Flow 维护配额和公平性（建议加权轮转）；每个 Flow 还可声明 `maxQueuedTasks`、每会话上限及其溢出策略。全局队列满时先执行全局策略，再应用 Flow/边级策略并产生诊断事件。
- Source 回调必须极短：创建根会话、封装消息、按入口边投递，绝不直接跑用户动作。
- 调度器从队列取项，按节点的执行模型投递到工作池。默认使用有界 `ExecutorService`；同一会话内的有状态 Router 默认串行，跨会话可以并行。
- Executor 的并发策略由组件声明：`serial`、`per-session-serial`、`parallel(limit)`、`latest-wins`。涉及键盘/鼠标的 AWT Robot 动作默认 `serial`。
- 队列满时按入口/边定义 `reject`、`drop-latest`、`drop-oldest` 或 `block-source`；输入钩子默认 `drop-latest` 并计数告警，禁止无限内存队列。

运行时不应只有一个线程，更不能在处理完一个 bundle 后退出循环。异步动作完成后必须通过调度器继续派发结果信号，而不是在动作线程上递归调用下一节点。

### 4.2 分发算法

1. Source 生成根消息，创建 `Session`，初始 work count 为 1。
2. 根据入口边匹配器复制出若干目标任务；每成功入队一个任务，保留一次会话引用。
3. 工作线程取出任务，先检查会话取消标志、目标 Flow revision 是否仍可执行，再执行目标组件。
4. 组件返回零或多个 `Emission`（信号、上下文增量、可选延迟）。调度器为每个匹配后继创建新任务并保留引用。
5. 当前任务的 `finally` 中释放一次引用。引用归零时正常完成会话；取消请求下，引用归零时标记取消完成。

一个任务只拥有一个引用；创建后继前 retain、入队失败立即 release。这使“分裂 + 引用计数归零结束”成为内核不变量而不是插件约定。

### 4.3 环路与保护

环路是有用能力，但不能靠“用户小心”。部署校验器必须：

- 计算强连通分量；有环时要求显式 `allowCycle` 和 `maxHops`。
- 默认 `maxHops: 64`、单会话 `maxMessages: 10_000`、单边可选节流。
- 记录 `visitedEdge` 次数；超过边限额终止会话并产生 `runtime.session.guardTriggered`。
- 不以相同 event type 自动去重；双击、循环动作等合法场景会被误伤。去重应为可选 Filter。

## 5. 会话与上下文

### 5.1 Session

```java
interface Session {
  UUID id(); String flowId(); String flowRevision();
  Status status(); boolean cancellationRequested(); int outstandingWork();
  CompletionStage<SessionResult> completion();
  void requestCancel(CancelReason reason);
}
```

状态为 `ACTIVE → {COMPLETED | CANCELLING → CANCELLED | FAILED}`。取消是协作式的：调度器不再派生新工作；尚未开始的任务被跳过；插件在 `ExecutionContext.cancellation()` 处检查。不能安全中止的本地调用允许跑完，但不得再发后继信号。

会话由根信号创建，而不是由执行器创建。一个 Router 的超时也仍属于原会话；定时器仅持有会话引用。跨会话聚合（例如“任意用户的最近十次点击”）必须明确使用全局状态 Router，不能悄悄混入会话状态。

### 5.2 上下文作用域

| 作用域 | 生命周期 | 写权限 | 用途 |
| --- | --- | --- | --- |
| `global` | Runtime 生命周期 | 受 ACL/原子操作约束 | 开关、用户设定、共享状态 |
| `flow` | 某 Flow revision 激活期间 | 启动后只读 | 常量、默认参数 |
| `session` | 根信号到会话终止 | 当前会话 | 关联输入、临时状态 |
| `message` | 当前消息/分支 | 只读 | payload、trace、局部映射结果 |
| `action` | 一次动作调用 | 只读 | 已解析动作参数、动作元数据 |

上下文必须是持久化/不可变视图：分支得到同一父上下文加自己的 delta，不能共享一个可变 `Map`。显式 `ContextStore` 操作才能写 session/global，避免并发分支隐式覆盖。建议用 CAS 版本号，冲突策略为 `fail`、`last-write-wins` 或插件提供的合并器。

### 5.3 占位符与表达式

配置中的参数允许插值，但表达式语言必须刻意小且无副作用：

```yaml
key: "${message.payload.key}"
delayMs: "${coalesce(session.combo.delayMs, 80)}"
enabled: "${global.features.rapidFire}"
```

解析规则：

- 纯 `${...}` 保留原始 JSON 类型；夹在字符串中的插值结果转字符串。
- 支持路径读取、字面量、`??`/`coalesce`、比较和有限白名单函数；禁止反射、类加载、任意 Java/脚本执行。
- 在 Flow 编译阶段解析语法和静态路径；运行阶段解析值并报告节点、字段、表达式、会话 ID。
- 缺失值默认失败，而非静默变 `null`；可显式写 `${optional(path)}`。

## 6. 插件系统

### 6.1 插件契约

插件贡献**组件类型和动作类型**，不直接操纵 Runtime 内部队列。

```java
interface KuudraPlugin {
  PluginDescriptor descriptor();
  void register(PluginRegistry registry);
  CompletionStage<Void> start(PluginContext context);
  CompletionStage<Void> stop(StopReason reason);
}
```

注册项包含类型名、JSON Schema、配置到实例的工厂、执行模型、能力声明和版本。核心内置的类型也走同一注册表。建议首批插件：

- `kuudra-input-jnativehook`：键盘、鼠标原始 Source；不做双击等业务手势。
- `kuudra-action-awt-robot`：键鼠 Action。
- `kuudra-router-patterns`：计数窗口、顺序、并集、保持、限流等通用 Router。
- `kuudra-bridge-socket`：将信号/动作请求桥接到 TCP、Unix Domain Socket 或命名管道上的外部可信进程。

### 6.2 包、隔离与依赖

每个插件是一个 Fat JAR，根目录含 `META-INF/kuudra/plugin.yaml`：

```yaml
id: io.kuudra.input.jnativehook
version: 1.0.0
apiVersion: 1
entrypoint: io.kuudra.jnativehook.JNativeHookPlugin
requires: []
optional: []
capabilities: [input.global.keyboard, input.global.mouse]
```

- 每个已解析插件集合由专用、可关闭的 `URLClassLoader` 加载；父加载器仅暴露 `kuudra-api`、JDK 和日志 API。
- 依赖按有向无环图解析；父插件先启动、反向停止。循环依赖和版本范围不满足均拒绝激活。
- 共享 API 包必须 parent-first；插件私有依赖 child-first，并禁止插件导出核心 API 的重复副本，避免 `ClassCastException`。
- “热重载”是**新类加载器 + 新 Flow revision + 停旧版本**，不是在原 ClassLoader 中替换类。旧会话可选择 drain、cancel 或等待超时；确认无实例/线程/资源后才 close ClassLoader。
- ClassLoader 不是安全沙箱。仅应加载本地可信插件；插件签名/哈希校验、目录白名单和能力审计是未来增强项。

## 7. 配置与装配

配置模型称为 `kuudra-flow`，默认序列化格式为 YAML。它是普通的声明式 YAML 文档，不采用 Kubernetes 的 `apiVersion`/`kind` 风格，也不应允许在配置内执行 Groovy/Java。复杂逻辑由 Router/Action 插件实现。配置模型与格式解耦：首期实现 YAML 读取器，后续可以增加 JSON、TOML 读取器；它们必须编译为相同的内部 `FlowDefinition`。

```yaml
flow:
  id: double-a-to-c
  name: 双击 A 时单击 C
  version: 1
plugins:
  - id: io.kuudra.input.jnativehook
    version: "^1.0"
  - id: io.kuudra.action.awt-robot
    version: "^1.0"
components:
  - id: keyboard
    kind: source/jnativehook.keyboard
    config: { listen: [key.pressed] }
  - id: only-a
    kind: filter/json
    config:
      when: "message.type == 'jnativehook.key.pressed' && message.payload.key == 'A'"
      emit:
        type: input.a.pressed
        payload: { key: "${message.payload.key}" }
  - id: double-a
    kind: router/window-count
    config:
      inputType: input.a.pressed
      count: 2
      within: 500ms
      emitType: gesture.a.doublePressed
      reset: on-match
  - id: robot
    kind: executor/action-bindings
    config:
      bindings:
        - when: "message.type == 'gesture.a.doublePressed'"
          action: awt-robot.key-tap
          with: { key: C }
edges:
  - { from: keyboard, to: only-a }
  - { from: only-a, to: double-a }
  - { from: double-a, to: robot }
policies:
  queue: { maxQueuedTasks: 1024, overflow: drop-latest, weight: 1 }
  session: { maxHops: 64, maxMessages: 10000 }
```

装配流水线：Parse YAML/JSON/TOML → 统一 `FlowDefinition` → schema 验证 → 插件/版本解析 → 表达式编译 → 组件工厂实例化 → 图校验（ID、边、类型、环、能力）→ 生成不可变 Flow revision → 原子激活。失败不得影响当前活跃版本。

## 8. 系统事件管线、管理 API 与客户端

信号队列与系统事件管线是两条严格分离的管线：

| 管线 | 数据 | 消费者 | 语义 |
| --- | --- | --- | --- |
| `SignalQueue` | 业务信号及其后继任务 | Source、Filter、Router、Executor | 参与 Flow 执行；有会话、背压、取消和重试语义 |
| `SystemEventBus` | 运行状态、会话变化、插件/Flow 生命周期、诊断、指标采样 | Web、TUI、Dashboard、日志/监控适配器 | 仅观测，不得反向改变 Flow；允许按订阅者丢弃或采样 |

`SystemEventBus` 的事件至少包含 `eventId`、`occurredAt`、`severity`、`category`、`flowId`（可选）、`sessionId`（可选）、`subject`、`data` 和 `traceId`。它不是业务 Signal 的镜像：默认不投递每一个原始按键，避免观测本身拖垮自动化；调试模式下可对指定 Flow/会话开启采样后的信号追踪。

运行时将 `SystemEventBus` 适配为 WebSocket 推送，SSE 可作为轻量备选；REST 查询 API 提供当前快照和断线后的补偿查询。每个订阅者有独立有界缓冲区，慢客户端只丢自己的低优先级事件，不得阻塞 SignalQueue。可选的 `sinceEventId`/保留窗口用于客户端重连续传，第一版也可先只保证实时推送并让客户端回退到快照查询。

Web 模块是薄适配层，只调用 application service；它不得依赖 JavaFX，也不得直接访问插件实例。

| HTTP API | 含义 |
| --- | --- |
| `GET /api/v1/runtime`、`POST /start`、`POST /stop` | 内核运行状态与生命周期 |
| `GET /api/v1/flows`、`PUT /{id}`、`POST /{id}/activate` | 校验、保存、激活配置版本 |
| `GET /api/v1/components` | 活跃图、类型、健康状态 |
| `GET /api/v1/sessions`、`GET /{id}`、`POST /{id}/cancel` | 会话查询与协作取消 |
| `GET /api/v1/plugins`、`POST /reload`、`POST /{id}/enable|disable` | 插件管理 |
| `GET /api/v1/system-events` | 按条件查询系统事件/诊断快照 |
| `GET /api/v1/system-events/stream` | SSE 备用实时流 |
| `GET /api/v1/ws` | WebSocket：系统事件订阅、过滤和重连 |

TUI 仅是 HTTP/WebSocket 客户端，支持 `runtime status`、`flow validate/apply`、`session list/cancel`、`plugin list/reload`、`events tail`。Dashboard 同样走 REST + WebSocket（SSE 备用）；JavaFX 只能依赖客户端 DTO，绝不可引用 runtime/core 的实现类。

本地 API 默认绑定 `127.0.0.1`，使用随机 token 或本地凭据文件认证；若未来暴露到网络，必须有 TLS、认证、授权和审计。

## 9. 可观察性、错误与测试

- 结构化日志字段至少包括 flow、revision、sessionId、messageId、nodeId、pluginId、耗时与异常摘要。
- 指标：入队/丢弃数、队列长度、端到端延迟、动作耗时、活跃会话、取消/失败/保护触发数、插件健康状态。
- `SystemEventBus` 应当是真正可订阅的内部事件总线，并由 Web 转为 WebSocket/SSE；不能只是空接口或注释占位。
- 单节点异常默认仅失败当前会话并发布诊断，不杀死 Runtime；可配置 `onError: continue | cancel-session | disable-component`。
- 使用虚拟时钟/可控调度器测试窗口 Router；使用假的 Source/Action 测试图分支、引用计数、取消、环路限制和部署原子切换；插件 ClassLoader 使用集成测试验证卸载后无线程泄漏。

## 10. 模块划分

```text
kuudra-api              稳定模型、SPI、DTO；插件唯一可见的核心依赖
kuudra-runtime          共享队列、调度、会话、图执行、SystemEventBus（依赖 api）
kuudra-config           YAML/JSON/TOML、schema、表达式、Flow 编译（依赖 api）
kuudra-plugin-manager   描述符、依赖解析、ClassLoader、生命周期（依赖 api）
kuudra-application      用例服务：Flow、会话、插件、运行时（依赖上述模块）
kuudra-web              Spring Boot REST/WebSocket/SSE 适配器（依赖 application）
kuudra-tui              HTTP/WebSocket 客户端（不依赖 runtime）
plugins/*               独立构建和发布的插件 Fat JAR
```

现有 `orcana-core` 应重命名/演化为 `kuudra-api`，避免把实现称为 core。`orcana-runtime`、`orcana-plugin`、`orcana-config` 目前的空模块应按 Kuudra 职责重建，并在切换时统一重命名为 `kuudra-*`。旧 `eventflow-*` 在迁移完成前可留作归档，但不得同时作为生产构建模块。

## 11. 实施顺序

1. 建立 `kuudra-api` 的 Signal、Session、Component SPI、SystemEvent 模型和错误模型；先完成不依赖真实输入的单元测试。
2. 实现 Runtime（共享有界队列、Flow 公平调度、分支引用计数、取消、串并行执行、SystemEventBus），以假 Source/Action 验证不变量。
3. 实现 Config 编译器、schema、表达式和图校验；完成双击示例的端到端测试。
4. 实现 Plugin Manager 和一个 JNativeHook Source 插件、一个 AWT Robot Action 插件；先支持冷加载，再实现 drain 型重载。
5. 实现 Application + REST/WebSocket API + TUI；Dashboard 最后接入。
6. 从旧项目逐个迁移行为：先把原 Action 拆成 Robot/延迟等原子动作，再用 Flow 配置复现功能。每迁移一个宏都保留输入—输出回放测试。

## 12. 已确认的扩展边界

- 动作实现保持 Java 插件模型；YAML 只声明组件及其参数，不承载任意脚本。
- 将来通过桥接插件支持跨语言执行 Flow：桥接器把 Kuudra 信号和动作请求编码为稳定协议，经 TCP、Unix Domain Socket 或 Windows 命名管道发送给外部进程；回传结果/信号再由桥接器投递到共享 SignalQueue。
- 跨进程桥接必须有协议版本、消息大小/超时限制、请求—响应关联 ID、取消帧、重连策略和能力声明。外部进程不取得 Runtime 内存访问权，也不能绕开队列、会话和权限校验。
- 按键租约及取消后的补偿释放是 AWT Robot 插件的具体设计，暂不固化为核心会话语义；内核只保证取消信号与动作结束通知可被插件可靠接收。
