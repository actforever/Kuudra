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

Kuudra 将业务消息分为严格单向的三个阶段，不再区分内核级的 Event 与 Command：

```text
RawSignal  →  RootSignal  →  Signal
无会话          会话准入请求       已绑定会话
```

`RawSignal` 仅用于原始事件处理，不含会话；`RootSignal` 由 SessionProcessor 产生，携带会话创建说明；只有经 Runtime 准入后的 `Signal` 才有会话并可进入 Actor。

```java
public record RawSignal(
    UUID messageId,
    String type,                 // 例如 input.key.pressed、gesture.a.double
    Instant occurredAt,
    ComponentRef producer,
    Map<String, Object> payload, // JSON 兼容、不可变
    Trace trace                  // 路径、深度、因果关系
) {}
```

```java
public record RootSignal(RawSignal raw, SessionSpec sessionSpec) {}

public record Signal(
    RawSignal raw,
    UUID sessionId,
    KuudraFlowRef flow,
    ContextDelta contextDelta
) {}
```

约束：

- `type` 是稳定的点分命名空间，插件名称应在前缀内，例如 `jnativehook.key.pressed`。
- `payload` 表达事实；不允许组件修改已经入队的消息。
- `contextDelta` 表达本跳产生的、需要传给后继链路的值；运行时在分发时创建新的上下文视图。
- `messageId` 唯一，`trace.causationId` 指向直接父消息，`trace.rootMessageId` 便于关联观测。
- KuudraFlow 内信号的完整地址为 `flowId:type`；组件配置中只写本 Flow 的 `type`。Signal 不可越过所属 Flow 的图边，也不能伪造或覆盖 `KuudraFlowRef`。
- Payload、上下文和配置值必须是 JSON 值（null、boolean、number、string、array、object）。插件内部对象不可泄漏到消息边界。

### 3.2 五类组件

| 组件 | 输入 | 输出 | 职责 |
| --- | --- | --- | --- |
| `SignalSource` 信号源 | 外部世界 | 0..n RawSignal | Runtime 输入层：监听键鼠、定时器、HTTP、游戏状态等 |
| `SignalAdapter` 信号适配器 | RawSignal 或 Signal | 同阶段 0..n 消息 | 筛选、重命名、投影/补充 payload、限流；无跨消息状态 |
| `SignalProcessor` 信号处理器 | RawSignal 或 Signal | 同阶段 0..n 消息 | 有状态识别、聚合、窗口/超时、分支；Raw 阶段按输入管线隔离，Signal 阶段按会话隔离 |
| `SessionProcessor` 会话处理器 | RawSignal | 0..n RootSignal | 指定会话名称、分组键和准入策略，是唯一的阶段桥接点 |
| `Actor` 行为体 | Signal | 动作结果和 0..n Signal | 按绑定规则异步调用插件动作，后继 Signal 继承原会话 |
| 动作 Action | 参数与执行上下文 | 结果 | 最小的副作用单元，例如 `robot.keyTap` |

`SignalAdapter` 不是“信号源专属”或“Actor 专属”对象，而是图中的普通处理节点。`SignalProcessor` 是手势、时序和聚合策略的承载点：简单策略可单独作为节点串联，复杂策略可由插件提供组合/链式 Processor。SessionProcessor 是 RawSignal 阶段的终点，Actor 永远不能接收 RawSignal。为方便配置，SignalSource 可声明内联 `postAdapters`；装配器将其展开成显式边。

### 3.3 图而不是线

`KuudraFlow` 是以 `SessionProcessor` 为入口的版本化会话图，也是 Runtime 内最小的调度、隔离、健康检查与生命周期单元；下文简称 Flow。它不拥有 SignalSource，也不处理无会话的 RawSignal。Runtime 输入层的原始处理管线把 RawSignal 送至指定 Flow 的 SessionProcessor；准入成功后，才由该 Flow 处理带会话的 Signal。会话可在多条边分裂，也可由任意节点再次入队。允许环，但默认须显式写 `allowCycle: true`，且受最大深度、重复边和速率保护。每个 KuudraFlow 具有自己的会话命名空间、组件实例、会话态 SignalProcessor 状态和上下文。KuudraFlow ID 在控制平面与工作平面之间全局唯一、创建后不可修改；配置加载器接受 UUID 或雪花 ID 形式的字符串，建议由 TUI/API 创建时自动生成 UUIDv7，避免用户手写冲突。

```
Runtime 输入层： SignalSource → SignalAdapter → SignalProcessor ──→ Flow 的 SessionProcessor
                                                                     ↓
KuudraFlow：     Signal ───────────────→ SignalProcessor → Actor → SignalAdapter ──┐
                                            │                                      │
                                            └──────────────→ Actor <───────────────┘
```

SignalProcessor 不是单独、全局的消息队列消费者，而是图节点：它消费消息、维护状态，并产生更高层消息。Runtime 输入层的 RawSignal 管线建议保持无环，避免重复构造 RootSignal；环只允许存在于 KuudraFlow 的会话 Signal 图中。

会话阶段的 SignalProcessor 只能处理并融合**同一 SessionId** 下的 Signal，并将结果保留在该会话中；装配器与 Runtime 均应拒绝跨 SessionId 聚合的 Processor 契约。跨输入、窗口、手势等聚合一律在 Runtime 输入层的 RawSignalProcessor 中完成，然后由 SessionProcessor 决定是否创建会话。这样不会把已有会话退回 RawSignal，也不会发生会话上下文或取消链路的隐式合并。

### 3.4 Flow 间协作

首期不提供携带会话语义的 Flow `imports`/`exports`。一个 Signal 只在创建它的 KuudraFlow 内流转；需要共享会话、上下文或顺序保证的 Actor 必须放进同一个 Flow。这样把会话关系限定为单 Flow 内的树，避免跨 Flow 导入后多父会话、取消传播和上下文所有权不明确的问题。

Flow 间协作走两条显式路径：控制平面 Flow 通过授权的 `control/flow-lifecycle-controller` 请求 Runtime 管理工作 Flow 生命周期；工作 Flow 的准入条件则读取 Runtime 的内存状态视图，例如某个 Flow 是否 `ACTIVE`、某个命名会话是否仍活跃。这个视图不经 HTTP，也不在每个信号上查询 SQLite。未来如确有跨 Flow 原始事件复用需求，可增加不携带 SessionId、只传递 payload 与 trace 的 `RawSignalBridge`；它必须接入 Runtime 输入层，并由目标 SessionProcessor 独立创建会话。

## 4. KuudraRuntime、队列与并发

### 4.1 调度模型

- 每个进程只创建一个 `KuudraRuntime`；它拥有唯一的、共享且有界的 `KuudraTaskQueue`。队列项是 `(message, targetNodeId, targetGraphRevision)`，而不是“广播后让全部组件扫描”。RawSignal 任务指向 Runtime 输入管线，RootSignal/Signal 任务指向确定的 KuudraFlow revision；所有消息都经由它调度。
- `KuudraTaskQueue` 是 Runtime 内部 SPI，不让组件直接操纵：

```java
interface KuudraTaskQueue extends AutoCloseable {
  OfferResult offer(KuudraTask task);
  Optional<KuudraTask> poll(Duration timeout);
  QueueSnapshot snapshot();
  void close();
}
```

  `KuudraTask` 是 `RawSignalTask | RootSignalTask | SignalTask` 的密封类型。首期使用 JDK 并发原语实现，不引入第三方消息中间件：全局容量由 `Semaphore` 控制，每个 KuudraFlow 使用受锁保护的有界 `ArrayDeque`，调度器按加权轮转选取 Flow 队列。这同时满足总背压、Flow 配额与公平性；未来可替换为 Disruptor、持久化队列或远程 Broker 适配器，但这些实现必须保持上述有界、取消、顺序与拒绝语义。
- KuudraFlow 不是物理队列隔离单元，而是**逻辑隔离单元**：RootSignal 在被投递到目标 Flow 的 SessionProcessor 后才绑定 Flow revision 和会话 ID；会话 Signal 只能路由到该 Flow 图内允许的边。不同 Flow 共享背压和工作池，但不共享上下文、会话态 SignalProcessor 状态或组件实例，除非显式使用全局上下文/共享插件资源。
- 为避免一个 Flow 挤占全局队列，调度器按 Flow 维护配额和公平性（建议加权轮转）；每个 Flow 还可声明 `maxQueuedTasks`、每会话上限及其溢出策略。全局队列满时先执行全局策略，再应用 Flow/边级策略并产生诊断事件。
- SignalSource 回调必须极短：封装 RawSignal、按 Runtime 输入管线投递，绝不直接跑用户动作。输入层的 SignalAdapter/RawSignalProcessor 完成处理后，把 RawSignal 路由到指定 Flow 的 SessionProcessor；SessionProcessor 产生 RootSignal 后，KuudraRuntime 的 `SessionAdmissionController` 才创建会话和第一个 Signal。调度器执行轻量处理；RawSignalProcessor 状态由 Runtime 按 `(ingressPipelineId, processorId, partition)` 管理，会话态 SignalProcessor 状态由 Runtime 按 `(flowRevision, sessionId, processorId, partition)` 管理，同一状态分区必须串行化（或由 Processor 声明等价的原子实现）。需要按键/设备等分区时，Processor 声明 `partitionBy` 表达式，Runtime 按其结果分别串行。
- `partitionBy` 是 SignalProcessor 的状态分区键，不是新的消息队列。例如 RawSignalProcessor 的 `partitionBy: "raw.payload.key"` 会让所有 A 键信号共享同一份窗口计数并串行处理，而 B 键信号使用另一份计数、可与 A 并行；未配置时全部信号使用默认分区。它适合双击、按键保持、按设备计数等跨输入聚合策略。
- 同一会话的 Signal 带有递增序号，KuudraRuntime 通过 `SessionLane` 保证默认按序处理；不同会话没有全局顺序保证，可以并行进入调度与执行。显式并行的 Actor 绑定是唯一例外：它允许同会话内多个 Action 并发，因而这些 Action 完成后产生的后继 Signal 按完成先后入队，不承诺彼此的完成顺序。
- 所有 Actor 统一异步执行。KuudraRuntime 拥有有界的 `ActorExecutionPool`（首期为 JDK `ThreadPoolExecutor`）以及独立、只承担延时/超时的 `ScheduledExecutorService`；调度线程绝不等待 Action 完成。Actor 匹配绑定规则后向 ActorExecutionPool 提交 Action，持有会话 work count，待 `CompletionStage<ActionResult>` 完成后才投递结果 Signal 并释放引用。
- 循环不是首期内核编排节点。需要“按下 A 后持续点按 B”的 Action 由对应插件实现循环与调度；它可以创建插件私有的 `ScheduledExecutorService` 或其他资源，但必须经 `PluginContext.resources()` 注册。循环 Action 的 `CompletionStage` 在循环因正常结束、取消或错误退出前不得完成；每次迭代都检查 `CancellationToken`，并在退出时取消 `ScheduledFuture`、释放按键等外部资源。Runtime 不直接调度每一轮循环，但会把整个 Action 视为未完成的会话工作，从而使 stop、drain、reload 不会在循环仍运行时关闭插件。

```java
interface Actor {
  CompletionStage<List<Emission>> act(Signal signal, ActorContext context);
}

interface Action {
  CompletionStage<ActionResult> execute(ActionCall call, ActionContext context);
}
```

- Actor 的并发策略由组件声明：`serial`、`per-session-serial`、`parallel(limit)`、`latest-wins`。同一会话内多条命中绑定默认 `per-session-serial`，按声明顺序执行；不同会话的绑定默认具备并行资格，只有被 Actor/Action 明确声明为 `serial` 或受 `parallel(limit)` 限制时才会等待。只有显式声明 `parallel` 才允许同会话绑定并发。Runtime 通过执行 lane/信号量强制这些策略；涉及键盘/鼠标的 AWT Robot 动作默认 `serial`。线程池和其待执行队列同样有界，饱和时按 Actor 的拒绝策略处理并发布诊断，而不是让执行器无限堆积或阻塞 KuudraTaskQueue。
- 队列满时按入口/边定义 `reject`、`drop-latest`、`drop-oldest` 或 `block-source`；输入钩子默认 `drop-latest` 并计数告警，禁止无限内存队列。

运行时不应只有一个线程，更不能在处理完一个 bundle 后退出循环。异步动作完成后必须通过调度器继续派发结果信号，而不是在动作线程上递归调用下一节点。

### 4.2 分发算法

1. Runtime 输入层的 SignalSource 生成 RawSignal；其 SignalAdapter/RawSignalProcessor 链可丢弃、重映射或聚合它，并将结果投递给目标 Flow 的 SessionProcessor。
2. SessionProcessor 输出 RootSignal；SessionAdmissionController 按会话策略和 Flow 准入条件创建 Session，初始 work count 为 1，并构造第一个带 SessionId 的 Signal。
3. 根据会话阶段入口边匹配器复制出若干目标任务；每成功入队一个任务，保留一次会话引用。
4. KuudraRuntime 调度线程取出任务，先检查会话是否已经终态、目标 Flow revision 是否仍可执行，再执行目标组件；若有取消请求，将 `CancellationToken` 传给组件。
5. 会话阶段的 SignalAdapter/SignalProcessor 返回零或多个 `Emission`（Signal、上下文增量、可选延迟）；Actor 则异步完成后返回同类 Emission。调度器为每个匹配后继创建新任务并保留引用。
6. 当前任务的 `finally` 中释放一次引用。引用归零时，若组件链已确认取消则标记 `CANCELLED`，否则正常标记 `COMPLETED`。

一个任务只拥有一个引用；创建后继前 retain、入队失败立即 release。这使“分裂 + 引用计数归零结束”成为内核不变量而不是插件约定。

### 4.3 环路与保护

环路是有用能力，但不能靠“用户小心”。部署校验器必须：

- 计算强连通分量；有环时要求显式 `allowCycle` 和 `maxHops`。
- 默认 `maxHops: 64`、单会话 `maxMessages: 10_000`、单边可选节流。
- 记录 `visitedEdge` 次数；超过边限额终止会话并产生 `runtime.session.guardTriggered`。
- 不以相同 event type 自动去重；双击、循环动作等合法场景会被误伤。去重应为可选 SignalAdapter。

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

`SessionId` 是全局唯一的 UUIDv7；`sessionName` 则由 SessionProcessor 定义，是用于会话准入和控制选择器的逻辑名称，不是唯一 ID。SessionProcessor 为每个 RootSignal 提供：

```yaml
session:
  name: double-a-action
  admissionKey: "${raw.payload.key}"   # 可选；缺省为全局默认键
  policy: PARALLEL                       # PARALLEL | QUEUED | TOGGLE | IGNORE
```

实际准入分组键为 `(kuudraFlowId, sessionName, admissionKey)`。`PARALLEL` 为每个 RootSignal 创建会话；`QUEUED` 在同组有活动会话时将 RootSignal 存入该组有界 FIFO，当前会话终态后才创建下一会话；`TOGGLE` 请求取消已有会话并丢弃当前 RootSignal；`IGNORE` 直接丢弃当前 RootSignal。`QUEUED` 默认最多暂存 64 个 RootSignal，满后按 `drop-latest` 处理，且允许在配置中覆盖容量与溢出策略。

控制命令可用精确 `SessionId` 操作单个会话，或用 `(flowId, sessionName, admissionKey)` 选择一组会话。会话暂停后仍视为该组的活动会话：QUEUED 继续按容量积压 RootSignal，IGNORE 丢弃，TOGGLE 发出协作式取消请求。

会话状态为 `ACTIVE → PAUSING → PAUSED → ACTIVE`，以及 `ACTIVE/PAUSED → CANCELLATION_REQUESTED → {COMPLETED | CANCELLED | FAILED}`；`CANCELLATION_REQUESTED` 不是终态。按 SessionId 或会话选择器执行 `pause_session` 时，Runtime 允许在途节点完成，但将该会话的后继 Signal 存入会话 Continuation 表；`resume_session` 后按原顺序继续。取消是协作式的：Runtime 记录请求并将 `CancellationToken` 传给每个后续/在途组件调用，但不会强制线程中断、丢弃队列任务或禁止后继信号。组件可选择三种响应：`CONTINUE`（忽略请求并继续链路）、`DRAIN_THEN_CANCEL`（完成当前安全边界后不再派生后继）、`CANCEL_NOW`（停止后继并确认取消）。只有组件链确认取消且 work count 归零时，会话才进入 `CANCELLED`；否则即使收到请求，也可以正常进入 `COMPLETED`。首期会话不跨 Flow 派生父子关系。

会话由目标 Flow 的 RootSignal 准入创建，而不是由 Actor 创建。会话阶段中一个 SignalProcessor 的超时仍属于触发它的会话；定时器仅持有会话引用。Runtime 输入层的 RawSignalProcessor 默认状态域是输入管线，因此“双击”等多个原始输入的聚合自然可行；Flow 内的 SignalProcessor 默认绑定当前会话，也可显式声明 `stateScope: session`。全局状态只能经明确的 `global` ContextStore 访问，不能悄悄混入 Processor 状态。

### 5.2 RuntimeStateTable 与准入条件

`KuudraRuntime` 维护唯一的、线程安全的内存 `RuntimeStateTable`。它是即时运行判断的权威缓存，不做会话状态持久化，也不依赖 SQLite 的读写延迟。表中至少维护 Flow phase/health、每个会话的 phase、`sessionName`、所属 Flow、活动会话计数及准入分组索引。

```java
interface RuntimeStateView {
  FlowSnapshot flow(String flowId);
  int activeSessionCount(String flowId, String sessionName);
  boolean hasActiveSession(String flowId, String sessionName);
  Optional<SessionSnapshot> session(UUID sessionId);
}
```

SessionProcessor 可在创建 RootSignal 前或准入时声明无副作用条件；条件由 RuntimeStateView 原子求值并登记会话，避免“先判断 A 存在、随后 A 已结束、却启动 B”的竞态。条件失败即丢弃 RootSignal 并产生可采样诊断，不创建会话。例如 B 仅能在 A 循环存活时运行：`when: "runtime.hasActiveSession('combat', 'a-loop')"`。如 B 已经创建，还可声明 `requires` 与 `onLost: cancel`，使 A 会话结束后向 B 发出协作式取消请求。

### 5.3 上下文作用域

| 作用域 | 生命周期 | 写权限 | 用途 |
| --- | --- | --- | --- |
| `global` | Runtime 生命周期 | 受 ACL/原子操作约束 | 开关、用户设定、共享状态 |
| `flow` | 某 Flow revision 激活期间 | 启动后只读 | 常量、默认参数 |
| `session` | 根信号到会话终止 | 当前会话 | 关联输入、临时状态 |
| `message` | 当前消息/分支 | 只读 | payload、trace、局部映射结果 |
| `action` | 一次动作调用 | 只读 | 已解析动作参数、动作元数据 |

上下文必须是持久化/不可变视图：分支得到同一父上下文加自己的 delta，不能共享一个可变 `Map`。显式 `ContextStore` 操作才能写 session/global，避免并发分支隐式覆盖。建议用 CAS 版本号，冲突策略为 `fail`、`last-write-wins` 或插件提供的合并器。

### 5.4 状态存储与状态投影

Kuudra 引入类似 etcd 的 `StateStore` 抽象，而不把运行时变更回写 YAML。它保存全局上下文、插件托管数据、配置版本和低频状态投影；**它不是 Signal 调度或会话状态的权威来源**。即时运行状态只存在于 `RuntimeStateTable`。

```java
interface StateStore {
  VersionedValue get(Key key);
  boolean compareAndSet(Key key, Revision expected, Value next);
  TransactionResult transact(Transaction transaction);
  Watch watch(KeyPrefix prefix, Revision from);
  Lease grant(Duration ttl);
}
```

它至少支持版本化读取、CAS、原子事务、watch 和 TTL lease。首期采用 **SQLite** 实现单机嵌入式 StateStore：其事务、查询、备份和人工诊断能力适合本地自动化内核；未来可通过适配器接入 etcd 等外部一致性 KV 存储，而不改变 Runtime API。建议键空间如下：

```text
/context/global/...                    # 可持久化全局上下文
/config/runtime/...                    # Runtime 配置版本与元数据
/config/flows/{flowId}/...              # Flow 已激活配置版本与校验记录
/status/runtime/latest                  # 可选、低频 Runtime 状态快照
/status/flows/{flowId}/latest           # 可选、低频 Flow 状态投影
/plugins/{pluginId}/state/...           # 插件托管状态
```

Flow/session 上下文默认仍为内存态；只有显式请求持久化的值写入 `StateStore`。配置中的 `globalContext` 只提供首次初始化默认值；之后的变更写入 `/context/global`，绝不修改 `kuudra.yaml`。`globalContext` 默认对所有组件只读；只有控制平面 Flow 和在 `kuudra.yaml` 中被显式授予某个键前缀写权限的插件/工作 Flow，才能通过 CAS 修改该前缀。这些写入须携带调用方身份并记录审计 SystemEvent。Runtime 可异步投影 Flow phase、健康和活跃会话计数用于诊断，但不得将逐条 Signal 或单个活跃会话写入 SQLite，更不得让 Actor/SessionProcessor 通过轮询 SQLite 作即时判断。

进程异常退出后，Runtime 不恢复活跃会话、更不重放未完成 Signal；内存状态表随进程消失。下次启动只发布恢复诊断，键盘、鼠标、网络等副作用只能由新的根信号重新发起。这一规则避免重启后意外继续旧动作。

### 5.5 Runtime 状态机

Runtime 也维护独立状态机：

```text
NEW → INITIALIZING → STARTING_CONTROL → STARTING_WORK → RUNNING → STOPPING → STOPPED
                         │                                      │
                         └──────────────→ DEGRADED ←────────────┘
                                          │
                                          └── retry-control ──→ STARTING_CONTROL
```

- `STARTING_CONTROL`：先启动、健康检查所有控制平面 Flow。
- 控制平面任一必需 Flow 启动失败时，Runtime 先协作停止本次已启动的其他控制平面 Flow，再进入 `DEGRADED`：管理 API、诊断和 `retry-control` 保持可用，但所有工作 Flow 均不得启动，避免半启动控制平面作出不完整控制。
- 控制平面成功后进入 `STARTING_WORK`，再启动已声明为启用的工作 Flow。工作平面的启动行为由 `runtime.startup.mode` 决定：`strict-all` 要求所有启用工作 Flow 均健康，否则已启动的工作 Flow 也协作停止、Runtime 进入 `DEGRADED`；`allow-degraded` 允许健康工作 Flow 继续运行，不健康 Flow 进入 `FAILED/DEGRADED`，Runtime 进入 `DEGRADED` 并保留管理能力。
- `runtime.stop` 从 `RUNNING` 或 `DEGRADED` 进入 `STOPPING`，先停止工作平面，再停止控制平面；所有安全 drain 完成后为 `STOPPED`。
- 已运行的控制平面 Flow 后续变为 `UNHEALTHY` 时，Runtime 进入 `DEGRADED` 并发布高优先级诊断，但不自动停止工作 Flow；管理 API 与仍健康的控制 Flow 保持可用，是否停止工作平面由管理员或健康控制 Flow 的显式命令决定。

控制平面 Flow 与工作 Flow **不是主从关系**。两者都由 Runtime 调度，均不能直接持有或修改其他 Flow 的内部状态；控制平面仅凭授权的 `WorkFlowControlCommand` 请求 Runtime 执行操作。Runtime（其中的 RuntimeStateTable）才是即时状态与调度的权威来源，StateStore 仅保存配置和投影。

不将控制平面 Flow 设计为“主节点”的原因是：它本身是用户配置、可热重载且可能失败的图；若 Runtime 的启动、调度、状态一致性或权限判定依赖它，会形成“主节点尚未启动便无法启动”“主节点误操作自身”“主节点失败导致状态权威丢失”的循环。Kubernetes 式控制平面—节点关系适合未来的多进程/多机器执行层：届时可把 Runtime 的工作执行部分演化为受核心控制服务协调的 agent，而不是把某一个控制平面 Flow 变成主节点。

控制平面 Flow 的职责是观测、决策和发送受限控制命令，而不是执行一般自动化动作。Runtime 输入管线与 Flow plane 无关，可把任意 RawSignal 投递给控制 Flow；装配器只对 Flow 内组件按 plane 校验：控制平面中的会话态 SignalAdapter、SignalProcessor 可使用通用类型，但 Actor 节点必须使用 `control/` 命名空间下的类型。插件注册组件/动作时声明 `allowedPlanes`，配置中即使手写普通 Actor 或 AWT Robot 动作也会被拒绝。这样限制的是产生副作用的权限边界，不会迫使输入或通用处理插件为控制平面重复实现一份组件。

核心默认提供 `control/flow-lifecycle-controller` Actor。它是控制平面管理工作 Flow 的唯一内置入口，封装 `enable`、`pause`、`resume`、`stop`、`reload` 与 `session.cancel` 等 `WorkFlowControlCommand`；用户可在 Runtime 输入管线中定义任意 SignalSource/RawSignalProcessor，再把结果投递给控制 Flow 的 SessionProcessor，并通过匹配规则将会话 Signal 路由到该 Actor。它只接受 `target` 为工作 Flow/工作会话的选择器，并在装配与执行两阶段校验控制平面身份、`runtime.work-flow.*` 权限和目标范围。其他控制副作用应以独立的 `control/` Actor 提供，例如 `control/system-event-publisher`；`actor/action-bindings`、`awt-robot.*` 等普通 Actor 只能存在于工作 Flow。

### 5.6 Flow 健康检查与启动探针

每个 Flow 由 Runtime 提供 `FlowHealthProbe`，汇总其必需组件的启动探针与运行状态：

```java
interface FlowHealthProbe {
  FlowHealth check();
}

record FlowHealth(HealthStatus status, List<ComponentHealth> components) {}
// HealthStatus: STARTING, READY, DEGRADED, UNHEALTHY
```

插件组件可在 `start()` 后报告 `READY`，也可实现异步探针以确认外部依赖真正可用；例如 Runtime 输入层中的 JNativeHook SignalSource 只有在全局监听注册成功后才为 `READY`。Flow 聚合规则是“所有必需 Flow 组件 READY 才为 READY”；可选组件失败使 Flow 为 `DEGRADED`，必需组件失败为 `UNHEALTHY`。每次探针结果均发布 `SystemEvent`，供 API、TUI 和 Dashboard 显示原因。

`kuudra.yaml` 允许配置工作平面的启动策略与探针超时：

```yaml
runtime:
  startup:
    mode: strict-all            # 可选 allow-degraded
    probeTimeout: 30s
```

无论该选项为何，控制平面始终使用 `strict-all`；只有所有必需控制 Flow 为 `READY` 后，Runtime 才会开始启动工作 Flow。

### 5.7 Flow 生命周期、暂停与协作式停止

每个 Flow 由 Runtime 维护独立状态机：

```text
DISCOVERED → VALIDATED → INACTIVE → STARTING → ACTIVE → PAUSING → PAUSED
                         │              │                 │          │
                         └──────────────┴─────────────────┴──────────┴──→ FAILED
                                                         PAUSED → ACTIVE
ACTIVE/PAUSING/PAUSED → STOPPING → STOPPED
ACTIVE → RELOADING_DRAIN → STARTING
```

- `enable`：`INACTIVE/STOPPED → STARTING → ACTIVE`；创建 Flow 组件、恢复插件资源并打开其 SessionProcessor 入口。KuudraRuntime 启动时必须先按依赖顺序完成所有控制平面 Flow 的启动与健康检查，任一控制平面 Flow 启动失败则不得启动工作 Flow；控制平面就绪后才启动工作 Flow。
- `disable` 或 `stop`：`ACTIVE → STOPPING`；先关闭该 Flow 的 SessionProcessor 入口接收新根信号，再对该 Flow 的会话发送协作式取消请求，等待组件链 drain 后进入 `STOPPED`。超时不是强制停止：Flow 保持 `STOPPING`，发布超时诊断，且不得卸载仍可能执行的插件/组件；管理员可继续等待、重试请求或使用明确标注为不安全的强制隔离操作。
- `pause`：`ACTIVE → PAUSING`；关闭该 Flow 的 SessionProcessor 入口接收新根信号，但不停止可能同时服务其他 Flow 的 Runtime SignalSource。正在执行的节点允许完成当前处理；调度器将该 Flow 已排队但未执行的任务及当前节点产生的后继任务，以不可变的 `Continuation`（目标节点、Signal、上下文视图、Flow revision）写入暂停续延表，而不再执行。所有在途任务到达这一边界后转为 `PAUSED`。暂停期间投递到该 Flow 的新 RawSignal**不暂存**，一律按 Flow 的入口溢出策略拒绝或丢弃，并发布诊断。
- `resume`：`PAUSED → ACTIVE`；按原有顺序/公平调度策略将暂停续延表中的任务重新投递，因此从暂停所在节点的下一跳继续。暂停会话仍持有 work count，可被查询或收到取消请求；暂停状态下由组件的取消回调决定是否确认取消并删除续延，未确认时续延会保留到 `resume`。
- `reload`：只作用于被指定的目标 Flow，不暂停其他工作 Flow。先在不产生副作用的前提下解析、校验并编译候选 revision；随后旧 revision 进入 `RELOADING_DRAIN`，关闭其 SessionProcessor 入口接收新根信号，但允许其已有会话及后继链路自然排空。只有旧 revision 的活跃会话归零、组件和资源安全关闭后，才创建并启动新 revision。默认不设超时、不发送取消请求，语义等同于 Docker Compose 的“停止旧服务后再启动新服务”；reload 期间投递给该 Flow 的新 RawSignal 按入口策略拒绝或丢弃。候选 revision 在实际启动阶段失败时，Flow 直接进入 `FAILED` 并保留诊断，**不尝试回滚或重新启动旧 revision**；管理员修复后再次 reload。
- `FAILED`：停止接收新输入，保留诊断；管理员可修复配置后 `reload`，或显式 `disable` 清理资源。

Kuudra 将 Flow 分为两个层级：**控制平面 Flow** 与**工作 Flow**。控制平面 Flow 能通过受权限保护的 `control/` Actor（默认是 `control/flow-lifecycle-controller`）派发 `WorkFlowControlCommand`，如 `work-flow.enable`、`work-flow.stop`、`work-flow.reload`、`work-session.cancel`；工作 Flow 永远不能加载这些 Actor。此类命令只作用于工作 Flow，不能停止、暂停、取消或重载控制平面 Flow，从而避免控制逻辑误伤自身。

控制平面 Flow 的生命周期由独立的、仅供管理端调用的 `ControlPlaneCommand` 管理；它不会被任意控制平面 Flow 派发的工作 Flow 命令影响。两类命令都不进入用户定义的 edges，也不能被普通 SignalAdapter/SignalProcessor 误消费。KuudraRuntime 在状态变更时向目标 Flow 的组件发送生命周期回调和 `CancellationToken`；取消回调与每次执行上下文都必须允许组件作出 `CONTINUE`、`DRAIN_THEN_CANCEL` 或 `CANCEL_NOW` 的响应。所有命令结果和状态变化都会发布为 `SystemEvent` 供前端观测。

`runtime.stop` 是唯一允许同时停止控制平面与工作 Flow 的系统级操作，且总是先停止工作 Flow、再停止控制平面 Flow；它同样遵循协作式停止与安全 drain 规则。普通 Flow 控制命令无法跨越平面边界。

### 5.8 占位符与表达式

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

`PluginContext` 提供插件家目录、日志、受限的 Runtime 服务，以及 `PluginResourceRegistry`。插件创建的线程池、定时任务、输入钩子、socket、原生句柄等长期资源必须注册为具名资源；不得只创建守护线程后依赖 JVM 退出回收。

```java
interface PluginResourceRegistry {
  <T extends AutoCloseable> T register(String name, T resource);
  <T extends ExecutorService> T registerExecutor(String name, T executor);
  void retainActionWork();
  void releaseActionWork();
}
```

循环 Action 使用插件私有调度器时，在创建循环前 `retainActionWork()`，在循环 CompletionStage 的最终完成回调中 `releaseActionWork()`；定时任务及其 Future 必须由 Action 自身在取消/失败/正常结束时关闭。`PluginResourceRegistry` 的作用不是替插件管理业务循环，而是使 Runtime 能列出资源、拒绝带活跃工作的卸载、并在异常路径按注册逆序兜底关闭资源。

注册项包含类型名、JSON Schema、配置到实例的工厂、执行模型、能力声明和版本。核心内置的类型也走同一注册表。建议首批插件：

- `kuudra-input-jnativehook`：键盘、鼠标原始 SignalSource；不做双击等业务手势。
- `kuudra-action-awt-robot`：键鼠 Action。
- `kuudra-processor-patterns`：计数窗口、顺序、并集、保持、限流等通用 SignalProcessor。
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
- 依赖按有向无环图解析；父插件先启动、反向停止。循环依赖和版本范围不满足均拒绝激活。插件状态为 `RESOLVED → STARTING → ACTIVE → DRAINING → STOPPING → STOPPED`，启动失败进入 `FAILED`；只有依赖全部 `ACTIVE` 时才允许实例化其组件或 Action。
- 共享 API 包必须 parent-first；插件私有依赖 child-first，并禁止插件导出核心 API 的重复副本，避免 `ClassCastException`。
- 插件/Flow reload 是**候选配置预校验 → 旧 revision drain → 关闭旧资源 → 启动新 revision**，不是在原 ClassLoader 中替换类。插件树的停止顺序为：先停止依赖该插件的 Flow 新会话入口并 drain 其活跃会话，再停止依赖它的子插件，随后调用当前插件 `stop`，由资源注册表按逆注册顺序关闭残留资源，最后关闭 ClassLoader；父插件最后停止。默认 drain 不取消、不超时；存在活跃 Action work、非终止线程或未关闭资源时不得 close ClassLoader，并进入 `DRAINING`/诊断状态。
- 插件可选实现 `PluginStateMigration`：声明来源/目标插件版本，并在新插件启动、旧版本资源释放前，通过受限 `PluginMigrationContext` 迁移插件家目录与 `/plugins/{pluginId}/state` 数据。迁移必须可预检（dry-run）、具备原子提交或可恢复备份，并在失败时保持旧插件版本与数据可继续运行。
- ClassLoader 不是安全沙箱。插件防护的首期措施包括：受 Runtime 管理的安装目录、插件 ID/版本/内容哈希清单、可配置 allowlist、签名校验预留、能力声明及对核心敏感 API 的授权审计、生命周期超时与资源泄漏检测。它们能防止误装、篡改和越权使用 Kuudra API，但不能阻止恶意 Java 代码直接访问操作系统；不可信扩展应通过受 OS 隔离的外部桥接进程运行。

## 7. 配置与装配

配置模型分为 `KuudraConfig`（Runtime 总配置）与 `KuudraFlowConfig`（单个 KuudraFlow 配置），默认序列化格式为 YAML。`KuudraConfig` 还定义 Runtime 输入层的原始信号管线；Flow 文件只定义从 SessionProcessor 开始的会话图。它是普通的声明式 YAML 文档，不采用 Kubernetes 的 `apiVersion`/`kind` 风格，也不应允许在配置内执行 Groovy/Java。复杂逻辑由 SignalProcessor/Action 插件实现。配置模型与格式解耦：首期实现 YAML 读取器，后续可以增加 JSON、TOML 读取器；它们必须编译为相同的内部 `KuudraFlowDefinition`。

`kuudraFlow.plane` 默认为 `work`。只有被 `kuudra.yaml.controlFlows` 显式登记且声明 `plane: control` 的 Flow 才能成为控制平面 Flow；其余文件即使声明了该字段也不得获得控制权限。

运行目录结构如下。每个普通 Flow 一份文件，因而可独立校验、加载、启停和热重载；`kuudra.yaml` 是 Runtime 总清单，不承载用户 Flow 图。

```text
kuudra-home/
  kuudra.yaml                 # 全局运行时、插件、全局上下文、全局 Flow 清单
  flows/
    double-a-to-c.yaml
    rapid-fire.yaml
  control-flows/              # 控制平面 Flow 定义
    emergency-stop.yaml
  plugins/                    # 插件 Fat JAR
  jnativehook-input/          # 由该插件创建并拥有的插件家目录
    config.yaml
    data/                     # 插件持久化数据
    resources/                # 插件私有资源
```

插件家目录由 `PluginContext.pluginHome(pluginId)` 提供，位于 `flows/` 同级目录且受 Runtime 创建/权限控制。插件只能写自己的目录；其 `config.yaml` 是插件运行配置，不得替代或隐式修改 Flow YAML。

`kuudra.yaml` 示例：

```yaml
runtime:
  signalQueue: { capacity: 4096, overflow: drop-latest }
  workerPool: { threads: 4 }
stateStore:
  type: sqlite
  file: data/kuudra-state.db
plugins:
  directory: plugins
  enabled: [io.kuudra.input.jnativehook, io.kuudra.processor.patterns, io.kuudra.action.awt-robot]
flows:
  directory: flows
  autoLoad: true
  watch: false                 # 配置变更不会自动重载；仅 API/TUI 显式 reload
globalContext:
  values:
    features: { rapidFire: false }
  writeGrants:
    - subject: control-flow:emergency-stop
      prefixes: [/context/global/features]
controlFlows:
  - file: control-flows/emergency-stop.yaml
    privileges: [runtime.work-flow.stop]
ingressPipelines:
  - id: desktop-keyboard
    components:
      - id: keyboard
        type: signal-source/jnativehook.keyboard
        config: { listen: [key.pressed] }
      - id: only-a
        type: signal-adapter/json
        config:
          when: "raw.type == 'jnativehook.key.pressed' && raw.payload.key == 'A'"
          emit: { type: input.a.pressed, payload: { key: "${raw.payload.key}" } }
      - id: double-a
        type: signal-processor/window-count
        config:
          inputType: input.a.pressed
          count: 2
          within: 500ms
          emitType: gesture.a.doublePressed
          reset: on-match
          partitionBy: "raw.payload.key"
    edges:
      - { from: keyboard, to: only-a }
      - { from: only-a, to: double-a }
    outputs:
      - { from: double-a, to: { flow: double-a-to-c, component: begin-double-a-session } }
  - id: emergency-keyboard
    components:
      - id: escape
        type: signal-source/jnativehook.keyboard
        config: { listen: [key.pressed], key: ESCAPE }
    outputs:
      - { from: escape, to: { flow: emergency-stop, component: begin-control-session } }
```

控制平面 Flow 与工作 Flow 使用同一 `KuudraTaskQueue`、拥有自己的会话和生命周期；区别是它们由 `kuudra.yaml` 显式登记，并可被授予受限的 KuudraRuntime 权限。它们不是所有输入的默认接收者：Runtime 输入管线必须显式把 RawSignal 投递至目标 Flow 的 SessionProcessor。控制平面 Flow 可通过受权限保护的 `control/` Actor 派发 `WorkFlowControlCommand`，例如紧急停止所有工作 Flow；它也可通过专用控制 Actor 发布 `SystemEvent` 供 Dashboard 观测。`SystemEventBus` 本身仍保持只读，不能被普通 Flow 直接消费或反向控制。

```yaml
kuudraFlow:
  id: double-a-to-c
  name: 双击 A 时单击 C
  version: 1
plugins:
  - id: io.kuudra.action.awt-robot
    version: "^1.0"
components:
  - id: begin-double-a-session
    type: session-processor/default
    config:
      session:
        name: double-a-action
        policy: PARALLEL
  - id: robot
    type: actor/action-bindings
    config:
      bindings:
        - when: "message.type == 'gesture.a.doublePressed'"
          action: awt-robot.key-tap
          with: { key: C }
edges:
  - { from: begin-double-a-session, to: robot }
policies:
  queue: { maxQueuedTasks: 1024, overflow: drop-latest, weight: 1 }
  session: { maxHops: 64, maxMessages: 10000 }
```

Flow 条件与会话依赖例子：

```yaml
# flows/b-loop.yaml：只有 combat Flow 中名为 a-loop 的活动会话存在时，B 才能准入。
kuudraFlow: { id: b-loop, version: 1 }
components:
  - id: begin-b-session
    type: session-processor/default
    config:
      session:
        name: b-loop
        policy: PARALLEL
        when: "runtime.hasActiveSession('combat', 'a-loop')"
        requires:
          - flow: combat
            sessionName: a-loop
            onLost: cancel
  - id: b-actor
    type: actor/action-bindings
    config: { bindings: [] }
edges:
  - { from: begin-b-session, to: b-actor }
```

输入管线可把 B 的 RawSignal 投递给 `b-loop:begin-b-session`。准入条件在 RuntimeStateTable 中原子计算；若 A 已结束，B 的 RootSignal 直接被拒绝，绝不会通过轮询 Web API 或 SQLite 后再决定。

全局紧急停止 Flow 的最小示例：

```yaml
# control-flows/emergency-stop.yaml
kuudraFlow: { id: emergency-stop, plane: control, version: 1 }
components:
  - id: begin-control-session
    type: session-processor/default
    config:
      session: { name: emergency-stop, policy: IGNORE }
  - id: stop-all
    type: control/flow-lifecycle-controller
    config:
      routes:
        - when: "message.type == 'jnativehook.key.pressed'"
          command: stop
          target: { scope: all-work-flows }
edges:
  - { from: begin-control-session, to: stop-all }
```

其中 `control/flow-lifecycle-controller` 只能在 `kuudra.yaml` 为该控制平面 Flow 授予相应的 `runtime.work-flow.stop` 权限后装配成功；默认不允许工作 Flow 调用。

装配流水线：读取 `KuudraConfig` → Parse YAML/JSON/TOML → 编译 Runtime 输入管线与 `KuudraFlowDefinition` → schema 验证 → 插件/版本解析 → 表达式编译 → 组件工厂实例化 → 图校验（ID、边、类型、阶段、环、入口目标、能力）→ 生成不可变 KuudraFlow revision → 原子激活。失败不得影响当前活跃版本。

## 8. 系统事件管线、管理 API 与客户端

信号队列与系统事件管线是两条严格分离的管线：

| 管线 | 数据 | 消费者 | 语义 |
| --- | --- | --- | --- |
| `KuudraTaskQueue` | RawSignal、RootSignal、Signal 及其后继任务 | SignalSource、SignalAdapter、SignalProcessor、SessionProcessor、Actor | 参与 KuudraFlow 执行；有会话、背压、取消和重试语义 |
| `SystemEventBus` | 运行状态、会话变化、插件/Flow 生命周期、诊断、指标采样 | Web、TUI、Dashboard、日志/监控适配器 | 仅观测，不得反向改变 Flow；允许按订阅者丢弃或采样 |

`SystemEventBus` 的事件至少包含 `eventId`、`occurredAt`、`severity`、`category`、`flowId`（可选）、`sessionId`（可选）、`subject`、`data` 和 `traceId`。它不是业务 Signal 的镜像：默认不投递每一个原始按键，避免观测本身拖垮自动化；调试模式下可对指定 Flow/会话开启采样后的信号追踪。

`kuudra-app` 以框架无关的实时订阅端口暴露 `SystemEventBus`，例如 `SystemEventSubscriptionService.subscribe(filter)` 返回 `java.util.concurrent.Flow.Publisher<SystemEvent>`；它不依赖 WebSocket、SSE、Spring 或 JavaFX。`kuudra-web` 负责将该订阅端口适配为 WebSocket 推送，并可额外提供 SSE 轻量备选。该通道纯用于实时观测，不写入 SQLite。每个订阅者有独立有界缓冲区，慢客户端只丢自己的低优先级事件，不得阻塞 KuudraTaskQueue。KuudraRuntime 可保留一个短期内存环形缓冲区以支持瞬时重连；超过该窗口时客户端回退到 REST 状态快照，而不是查询历史系统事件。

Web 模块是薄适配层，只调用 application service；它不得依赖 JavaFX，也不得直接访问插件实例。

| HTTP API | 含义 |
| --- | --- |
| `GET /api/v1/runtime`、`POST /start`、`POST /stop` | 内核运行状态与生命周期 |
| `GET /api/v1/work-flows`、`PUT /{id}`、`POST /{id}/enable|pause|resume|stop|reload` | 管理工作 Flow 状态机与配置版本 |
| `GET /api/v1/control-flows`、`PUT /{id}`、`POST /{id}/enable|pause|resume|stop|reload` | 管理控制平面 Flow；不受工作 Flow 控制命令影响 |
| `GET /api/v1/components` | 活跃图、类型、健康状态 |
| `GET /api/v1/sessions`、`GET /{id}`、`POST /{id}/cancel` | 会话查询与协作取消 |
| `GET /api/v1/plugins`、`POST /reload`、`POST /{id}/enable|disable` | 插件管理 |
| `GET /api/v1/system-events` | 查询当前诊断与短期内存事件窗口 |
| `GET /api/v1/system-events/stream` | SSE 备用实时流 |
| `GET /api/v1/ws` | WebSocket：系统事件订阅、过滤和重连 |

TUI 仅是 HTTP/WebSocket 客户端，支持 `runtime status`、`flow validate/apply`、`session list/cancel`、`plugin list/reload`、`events tail`。Dashboard 同样走 REST + WebSocket（SSE 备用）；JavaFX 只能依赖客户端 DTO，绝不可引用 runtime/core 的实现类。

本地 API 默认绑定 `127.0.0.1`，使用随机 token 或本地凭据文件认证；若未来暴露到网络，必须有 TLS、认证、授权和审计。

## 9. 可观察性、错误与测试

- 结构化日志字段至少包括 flow、revision、sessionId、messageId、nodeId、pluginId、耗时与异常摘要。
- 指标：入队/丢弃数、队列长度、端到端延迟、动作耗时、活跃会话、取消/失败/保护触发数、插件健康状态。
- `SystemEventBus` 应当是真正可订阅的内部事件总线，并由 Web 转为 WebSocket/SSE；不能只是空接口或注释占位。
- 系统事件不进入 SQLite；日志由独立的 `LogSink` 写入本地结构化文件（建议 JSON Lines），并按日期和文件大小滚动，保留数量/总容量/最长保留期均可配置。前端只通过 `SystemEventBus` 获得实时摘要与状态变化；本地排错直接读取滚动日志文件，必要时再由受限 REST 接口下载或检索。
- Action 失败默认不自动重试：Runtime 发布诊断并按 Actor 的 `onError: continue | cancel-session | disable-component` 策略处理当前会话，不杀死 KuudraRuntime。只有用户在 KuudraFlow 中明确建模重试 Signal/延时 SignalProcessor，并且目标 Action 声明可安全重入时，才允许再次调用。
- 使用虚拟时钟/可控调度器测试窗口 SignalProcessor；使用假的 SignalSource/Action 测试图分支、引用计数、取消、环路限制和部署原子切换；插件 ClassLoader 使用集成测试验证卸载后无线程泄漏。

## 10. 模块划分

```text
kuudra-api              稳定模型、SPI、DTO；插件唯一可见的核心依赖
kuudra-runtime          共享队列、调度、会话、图执行、SystemEventBus（依赖 api）
kuudra-config           YAML/JSON/TOML、schema、表达式、Flow 编译（依赖 api）
kuudra-state            StateStore、嵌入式存储与外部存储适配器（依赖 api）
kuudra-plugin-manager   描述符、依赖解析、ClassLoader、生命周期（依赖 api）
kuudra-app              用例服务：Flow、会话、插件、运行时，以及框架无关的实时事件订阅端口（依赖上述模块）
kuudra-web              Spring Boot REST/WebSocket/SSE 适配器（依赖 application）
kuudra-tui              HTTP/WebSocket 客户端（不依赖 runtime）
plugins/*               独立构建和发布的插件 Fat JAR
```

现有 `orcana-core` 应重命名/演化为 `kuudra-api`，避免把实现称为 core。`orcana-runtime`、`orcana-plugin`、`orcana-config` 目前的空模块应按 Kuudra 职责重建，并在切换时统一重命名为 `kuudra-*`。旧 `eventflow-*` 在迁移完成前可留作归档，但不得同时作为生产构建模块。

## 11. 实施顺序

### 11.1 当前最小实现状态

`refactor/kuudra-kernel` 分支上的首个可运行内核已建立 `kuudra-api`、`kuudra-runtime`、`kuudra-config`、`kuudra-plugin`、`kuudra-app` 与 `kuudra-web` 模块，并验证了“内存 RawSignal → 双击 RawSignalProcessor → SessionProcessor → 多节点会话图 → 异步 Actor/Action”的无插件 Demo。它使用共享有界队列、内存 RuntimeStateTable、会话内串行 lane、`PARALLEL`/`QUEUED`/`TOGGLE`/`IGNORE` 会话准入、协作取消，以及 pause/resume/stop drain。`kuudra-plugin` 已实现对已加载插件的依赖拓扑、私有目录与 `initialize → start → stop → destroy` 生命周期顺序；Demo 的 YAML 读取器只支持嵌套标量映射，目的是验证配置到装配的链路，**不是**正式 YAML 实现。

首个最小内核尚未实现通用 YAML/JSON/TOML 适配器、声明式完整图编译、持久化 StateStore、Fat JAR 隔离 ClassLoader、控制平面、HTTP/WebSocket 或真实输入/Robot 插件。它们仍按本章后续顺序演进，不应因为最小内核存在而被视为已完成。

1. 建立 `kuudra-api` 的 Signal、Session、Component SPI、SystemEvent、StateStore 模型和错误模型；先完成不依赖真实输入的单元测试。
2. 实现 `kuudra-state` 的嵌入式 StateStore，以及 KuudraRuntime（状态机、可替换的共享有界队列、KuudraFlow 公平调度、分支引用计数、取消、Actor 异步执行、SystemEventBus），以假的 SignalSource/Action 验证不变量。
3. 实现 Config 编译器、schema、表达式和图校验；完成双击示例的端到端测试。
4. 实现 Plugin Manager 和一个 JNativeHook SignalSource 插件、一个 AWT Robot Action 插件；先支持冷加载，再实现 drain 型重载。
5. 实现 `kuudra-app` 的用例与事件订阅端口，再实现 `kuudra-web` 的 REST/WebSocket/SSE 适配器与 TUI；Dashboard 最后接入。
6. 从旧项目逐个迁移行为：先把原 Action 拆成 Robot/延迟等原子动作，再用 Flow 配置复现功能。每迁移一个宏都保留输入—输出回放测试。

## 12. 已确认的扩展边界

- 动作实现保持 Java 插件模型；YAML 只声明组件及其参数，不承载任意脚本。
- 将来通过桥接插件支持跨语言执行 Flow：桥接器把 Kuudra 信号和动作请求编码为稳定协议，经 TCP、Unix Domain Socket 或 Windows 命名管道发送给外部进程；回传结果/信号再由桥接器投递到共享 KuudraTaskQueue。
- 跨进程桥接必须有协议版本、消息大小/超时限制、请求—响应关联 ID、取消帧、重连策略和能力声明。外部进程不取得 Runtime 内存访问权，也不能绕开队列、会话和权限校验。
- 按键租约及取消后的补偿释放是 AWT Robot 插件的具体设计，暂不固化为核心会话语义；内核只保证取消信号与动作结束通知可被插件可靠接收。
