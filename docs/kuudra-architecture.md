# Kuudra 事件流架构

本文是当前内核实现的架构基线，配套图为 [flow-arch.png](flow-arch.png)。

## 1. 事件实体与执行域

`KuudraEvent` 只保存业务身份、类型、发生时间、不可变 `EventData` 和 `EventLineage`。它不保存 Session，也不存在“Session 可空”的模糊状态。

Runtime 使用封闭的 `KuudraEventWrapper` 表达执行域：

- `RawEventWrapper`：Ingress 之前或 Egress 之后；
- `SessionEventWrapper`：Ingress 准入后，绑定唯一 `SessionReference`。

Wrapper 是 Runtime 路由状态，不是插件应写入上下文的数据。Flow revision 固定在 Runtime task 和 Session 上，已进入旧 revision 的工作不会误投递到新图。跨 Egress 时丢弃 Session 绑定，但 `EventLineage` 记录事件和会话因果关系。

## 2. 组件职责

| 组件 | 输入域 | 输出域 | 状态与职责 |
| --- | --- | --- | --- |
| `EventSource` | 外部 | RAW | 生命周期组件，采集键盘、计时器、网络等原始输入 |
| `RawEventInterpreter` | RAW | RAW | 可维护状态机、窗口和计时器，进行跨事件解释 |
| `EventAdapter` | RAW 或 SESSION | 同输入域 | 通常无状态，过滤、分类和重映射事件 |
| `Ingress` | RAW | SESSION | 判断准入，计算会话组键和初始 Session context |
| `EventHandler` | SESSION | SESSION | 异步业务处理，输出继承同一 Session，支持协作式取消 |
| `Egress` | SESSION | RAW | 唯一出域边界，解除 Session/Flow 执行绑定并保留谱系 |

`EventAdapter` 在 Component options 中声明绑定域，不能改变域。`RawEventInterpreter` 与 Adapter 不合并：前者表达持有资源和跨事件状态的解释过程，后者表达局部映射。Flow 不引入 port；需要分支时使用 Adapter，Handler 通过 `ActionContext.emit` 显式输出业务阶段事件。

插件可注册以上组件。常见 Ingress/Egress 使用 `core/default`；`SessionManager` 和 `SessionCoordinator` 只能由 Runtime 提供，不属于资源清单或路由节点。

## 3. FlowBinding 与静态校验

Flow 仍是 K8s 风格资源，通过 `spec.imports` 导入 Component，并通过 `edges` 定义无条件路由。`FlowBinding` 是 App 将资源实例、Flow revision、节点别名、边、域和预编译配置组合后的内部概念，不是用户资源。

Flow 构造时验证每条边：

- Source 只能绑定 RAW 输入节点；
- RAW 节点不能直接连接 SESSION 节点；
- 只有 Ingress 可 RAW→SESSION；
- 只有 Egress 可 SESSION→RAW；
- Adapter 必须保持部署时声明的域。

Runtime 另外保留最大 256 跳的硬限制。静态循环分析适合在后续清单编译器中给出警告；动态上限是最终安全阀。

## 4. Ingress、SessionManager 与 SessionCoordinator

Ingress 返回 `IngressDecision`：拒绝，或接受并给出 `groupKey`、输出事件和初始 Session context。它不创建、复用、停止或排队 Session。

Runtime 将接受结果交给 `SessionCoordinator`。会话组由 scope、Ingress 身份和 `groupKey` 组成；scope 可为跨 Flow binding 的 `INGRESS`，或默认隔离到具体 Flow revision 的 `FLOW_BINDING`。当前调度策略为：

- `PARALLEL`：有界并行，达到 `maxParallelSessions` 后拒绝；
- `SERIAL`：每组单活跃会话，其余进入有界队列；
- `IGNORE`：组内繁忙时丢弃新事件；
- `CANCEL_AND_REPLACE_PENDING`：请求取消活跃会话，待处理槽只保留最新事件；
- `CANCEL_AND_KEEP_PENDING`：请求取消活跃会话，待处理槽保留第一个事件；
- `TOGGLE`：空闲时启动，繁忙时只请求取消且不积压。

`SessionManager` 是单 Runtime 唯一会话事实源，负责 ID、Flow revision、Ingress/group、上下文、取消、快照和工作租约。Coordinator 只维护组调度，不直接修改 Session。

## 5. 租约、结束与取消

每个进入 SESSION 节点的任务在入队前取得一份工作租约，节点完成、失败或被取消跳过后释放。Handler 返回的 `CompletionStage` 完成前租约保持有效；完成后再调用 emitter 属于契约错误。最后一个租约归零时，Session 进入 `COMPLETED`、`CANCELLED` 或 `FAILED`，Runtime 发布 SystemEvent 并通知 Coordinator 启动下一个待处理事件。

Handler 显式发出的 Event 只表示业务阶段，不承担内核完成通知。Runtime 的 `event-handler.completed` 是可观测 SystemEvent，不进入业务 Flow。取消是协作式：取消标记阻止新工作，组件通过 `CancellationToken` 清理自身资源。

Session 不建立父子生命周期。Egress 后再次进入 Ingress 会创建独立 Session；二者只通过 EventLineage 保留因果关系，因此不存在父子取消、合并或引用计数冲突。

## 6. 上下文与占位符

逻辑作用域仍为 Event、Session、Flow、Global，但可见性由节点域决定：

| 区域 | 可读取作用域 |
| --- | --- |
| Source 之后、Ingress 之前 | Event、Flow、Global |
| Ingress 准入判断 | Event、Flow、Global |
| Ingress 之后、Egress 输入 | Event、Session、Flow、Global |
| Egress 输出之后 | Event、Flow、Global |

统一使用 `${event#...}`，不再存在 `${rawEvent#...}`。`${path}` 在 RAW 域按 Event→Flow→Global，在 SESSION 域按 Event→Session→Flow→Global 查询。Runtime 注册 Flow 时为每个节点调用 `PlaceholderResolver.compileMap(template, domain)`，一次完成正则扫描、表达式切分、JSON 静态解析和域合法性校验；事件热路径只查值并组装结果。

Session、Flow、Global 通过代码接口写入，YAML 只读。默认 `ContextCodec` 把 POJO 编码为不可变 JSON 兼容树，读取方用 `get(key, Type.class)` 按需反序列化。插件共享 POJO 必须来自声明的上游依赖，确保双方使用同一 `Class<?>`。

## 7. 控制面与演进

会话查询和取消链路固定为 `kuudra-web -> KuudraApp -> KuudraRuntime -> SessionManager`，Web 不暴露 Runtime 类型。SystemEvent 覆盖准入、会话活跃/终态、Handler 完成和失败，可用于未来会话流转 UI。

当前资源 API 版本为 `kuudra.io/v1alpha1`。后续资源独立版本、热重载、revision 迁移、静态循环诊断、持久化 Session 状态和更丰富的 Handler 执行策略，都必须保持上述域转换、租约和控制面边界。

Runtime 和 App 内核边界产生的失败统一以运行时异常 `KuudraException` 对外传播并保留 cause，使 Web、宿主框架和插件能够把内核拒绝与普通 IO、JDK 或容器环境异常区分开。
