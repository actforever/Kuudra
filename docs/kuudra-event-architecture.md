# Kuudra 事件与会话架构

## 目标

Kuudra 是单 Runtime、队列驱动的事件编排内核。插件和核心只使用一种业务消息 `Event`；会话不是第二条管线，而是 Event 在进入 Actor 执行域后携带的运行时上下文。

## 四类插件组件

| 组件 | 输入 | 输出 | 职责 |
| --- | --- | --- | --- |
| `@EventSource` | 无 | 无会话 Event | 从键盘、计时器、网络等外部系统采集事件。 |
| `@EventAdapter` | Event | Event | 过滤、事件名与属性重映射；可任意串联，并通过 EventContext 接收 Runtime 已解析的节点配置。 |
| `@EventProcessor` | 无会话 Event | 无会话 Event | 手势识别、窗口计数、聚合、解释或丢弃。 |
| `@Actor` | 带会话 Event | Event | 异步执行动作；可随时通过 `ActionContext.emit` 产生 Event，默认继承输入会话。 |

`SessionAllocator` 是核心节点而不是插件 SPI。它以 `SessionSpec` 对无会话 Event 执行准入，创建 Session，并输出带会话 Event。

## 单一事件模型

```text
EventSource / Actor
        ↓
   EventAdapter* ──→ EventProcessor* ──→ SessionAllocator ──→ Actor*
        │                    ▲                    │
        └────────────────────┴────────────────────┘
```

`Event` 包含不可变的 `EventData`、可选的 `SessionReference` 和始终存在的 `EventLineage`。

- 无会话 Event 可以经过 Adapter、Processor 和 Allocator，但不能进入 Actor。
- Actor 的 Event 可直接传向 Actor 或 Adapter，保持同一 Session。
- 带会话 Event 一旦路由到 Processor 或 Allocator，Runtime 生成脱离会话的副本：移除当前 `SessionReference`，并将该 Session ID 追加进 `EventLineage.parentSessionIds`。
- Processor 的聚合输出收集所有输入的父会话集合；Allocator 创建的新 Session 因而可拥有零到多个父 Session。
- Adapter 不能伪造、删除或替换会话；是否脱离会话由目标边的语义决定。

## 路由边语义

KuudraFlow 是带节点和边的有向图。目标节点决定投递变换：

| 目标节点 | 投递行为 |
| --- | --- |
| Adapter | 原样保留会话状态。 |
| Processor | 剥离会话，并保留父会话谱系。 |
| SessionAllocator | 剥离会话，再按准入策略创建新会话。 |
| Actor | 要求 Event 已绑定 Session，且保留该 Session。 |

Runtime 在装配时校验节点和边，在执行时再次校验，拒绝无会话 Event 进入 Actor、跨 Flow 伪造会话，以及超过最大跳数的循环事件。

## 会话

Session 是一次 Actor 执行链的上下文，包含：唯一 ID、Flow ID、名称、准入键、取消标记、上下文变量、父会话 ID 集合和活动工作引用数。

同一 Session 内的 Actor 投递按序执行；不同 Session 可以并行。Actor 分裂出多个 Event 时，这些分支共享 Session；最后一个工作引用结束时 Session 完成。

取消是协作式的：Runtime 不再推进已取消 Session 的新 Actor 工作，Actor 通过 `CancellationToken` 自行清理资源。父 Session 对子 Session 的取消传播由 `SessionSpec.parentTerminationPolicy` 配置：`NONE`、`ON_PARENT_CANCELLATION` 或 `ON_PARENT_TERMINAL`。

## 配置与插件引用

组件引用格式固定为：

```text
<type>/<plugin-namespace>/<component-name>
```

例如：

```text
event-source/hello-world/loop-emitter
event-adapter/input/key-normalizer
event-processor/gesture/double-press
actor/awt-robot/key-press
```

Flow 配置描述节点、边、SessionAllocator 及其策略；Source 绑定描述一个 EventSource 要投递到哪个 Flow 节点。省略 Adapter 等价于内置 identity adapter。

## 不变量

1. `EventSource` 只能发出无会话 Event。
2. `EventProcessor` 只观察和产生无会话 Event。
3. `Actor` 只接收带会话 Event；它通过 `ActionContext.emit(Event)` 随时输出事件，Runtime 自动附加输入会话与血缘。
4. 只有 `SessionAllocator` 能创建 Session。
5. 会话剥离由 Runtime 路由边完成，不由插件决定。
