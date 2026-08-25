# Kuudra App 管理 API

`kuudra-web` 是唯一的 HTTP 适配层：它注册 `KuudraApp` 外观，并将 App 的生命周期、Flow、Session 与系统事件适配为 REST/SSE。HTTP API 中不出现 Runtime；Runtime 仅是 App 在 `RUNNING` 时内部持有的内核资源。

App 生命周期独立于 Flow 生命周期：`stop` 关闭 Runtime 与插件资源，但 Web 进程仍可接收 `start` 或 `restart`，重新创建 App 内核。

资源清单、generation、observedGeneration、phase 和失败信息属于控制面，查询直接读取 App 持有的 StateStore，因此 Runtime 停止后仍可查询；此时资源运行状态显示为 `NOT_RUNNING`。Session、队列深度和实时 Flow 指标属于执行面，只有 Runtime 存在时才有实时值。

```text
CREATED → STARTING → RUNNING → PAUSING → PAUSED → RESUMING → RUNNING
                     └───────────────→ STOPPING → STOPPED
                     └──────────────────────────────→ FAILED
```

| 方法 | 路径 | 含义 |
| --- | --- | --- |
| `GET` | `/api/v1/app` | App 状态、队列长度和 Flow 数。 |
| `GET` | `/api/v1/app/status` | 当前 App 内核汇总：App 状态、队列、全部 Flow 摘要与活跃 Session 总数。 |
| `GET` | `/api/v1/app/checkpoint` | 查询本次暂停在安全点生成的组件、Flow context、Session 与队列检查点；非暂停状态返回 404。 |
| `GET` | `/api/v1/app/plugins` | 列出当前已加载插件及其版本、命名空间和状态。 |
| `GET` | `/api/v1/app/plugins/{namespace}/{pluginId}` | 按规范插件身份查询插件及其组件引用。 |
| `GET` | `/api/v1/app/plugins/{namespace}/{pluginId}/components` | 查询指定插件的结构化组件文档。 |
| `GET` | `/api/v1/app/resource-documentation` | 查询内核资源规约文档。 |
| `GET` | `/api/v1/app/resource-documentation/{namespace}/{kind}` | 按文档提供方和 kind 查询资源规约；当前包括 `kuudra-official/Flow`。 |
| `GET` | `/api/v1/app/components` | 列出所有已注册插件组件。 |
| `GET` | `/api/v1/app/components/{type}/{namespace}/{name}` | 按完整组件引用查询用途、示例、生命周期和输出事件。 |
| `GET` | `/api/v1/app/resources/components` | 列出清单声明的全部 Component 实例及实际状态。 |
| `GET` | `/api/v1/app/resources/components/{type}` | 按六类组件类型过滤资源实例。 |
| `GET` | `/api/v1/app/resources/components/{type}/{namespace}/{name}` | 查询具体资源实例、期望/实际状态、导入它的 Flow 和生命周期能力。 |
| `GET` | `/api/v1/app/resources/{kind}/{namespace}/{name}` | 按规范 `kind/namespace/name` 查询组件资源。 |
| `POST` | `/api/v1/app/resources/{kind}/{namespace}/{name}/desired-state/{state}` | 先持久化期望状态，再由 App 调谐该组件并更新 observedGeneration。 |
| `GET` | `/api/v1/app/namespaces/{namespace}/resources` | 列出指定命名空间中的组件资源。 |
| `POST` | `/api/v1/app/start` | 创建并启动内核。 |
| `POST` | `/api/v1/app/stop` | 停止内核，适配器继续运行。 |
| `POST` | `/api/v1/app/pause`、`/resume` | 无损冻结/恢复内核事件流转。 |
| `POST` | `/api/v1/app/restart` | 停止并重新创建内核。 |
| `GET` | `/api/v1/app/flows` | 列出 Flow。 |
| `GET` | `/api/v1/app/namespaces/{namespace}/flows` | 列出指定命名空间中的 Flow。 |
| `GET` | `/api/v1/app/namespaces/{namespace}/flows/{name}` | 按 namespace/name 查询 Flow。 |
| `GET` | `/api/v1/app/sessions/{id}` | 查询 Session。 |
| `POST` | `/api/v1/app/sessions/{id}/cancel` | 请求协作式取消。 |
| `POST` | `/api/v1/app/sessions/{id}/pause|resume` | 保留上下文和队列并冻结/恢复该会话。 |
| `GET` | `/api/v1/app/resources/state` | 查询持久 generation 与观测状态。 |
| `GET` | `/api/v1/app/events` | SSE 系统事件流。 |

暂停由 App 编排：先把状态切换为 `PAUSING`，再要求 Runtime 关闭内核执行闸门并等待已经进入节点的工作抵达安全点，随后生成一致检查点。只有这些步骤全部完成后 App 才进入 `PAUSED`，所以 `POST /pause` 成功返回本身就是“内核事件流已经静止”的确认。该流程不调用任何组件的 `pause()/stop()`，也不改写组件或 Session 状态；组件实例、内部状态、上下文和队列均保留。恢复只重新开放内核闸门，不重建组件、Session 或上下文。

`stop` 和 `restart` 在 `PAUSING`、`PAUSED` 期间仍然有效。stop 会把状态抢占为 `STOPPING`，然后执行与 RUNNING 状态相同的正常停止流程：组件与插件按生命周期关闭，Runtime、Session、队列和检查点随本次运行结束而释放；如果 Runtime 仍在等待暂停安全点，关闭信号会唤醒并终止该等待。restart 不包含强制清除分支，而是严格顺序调用同一个 `stop()`，等待进入 `STOPPED` 后再调用 `start()`，最终形成 `PAUSED → STOPPING → STOPPED → STARTING → RUNNING`。

组件资源生命周期由 `desiredState` 单独调谐：`active/inactive` 控制无生命周期组件是否参与事件流；`running/stopped` 控制 `Lifecycle`；实现 `PausableLifecycle` 后才允许 `paused`。组件 PAUSED 会关闭该实例的执行闸门并调用其 `pause()`，恢复到 RUNNING 时调用 `resume()` 后重新开放闸门。内核 PAUSED 与组件 PAUSED 是不同状态轴。

`ExecutionControl.poll()` 同时观察内核、当前组件和当前 Session；结果为 `CANCEL` 时应尽快清理并结束，为 `PAUSE` 时同步组件应快速返回，长时间异步 Handler 可以 `checkpoint()` 并在原调用点等待恢复。等待期间 Runtime 不把它算作在途节点，但 Session 工作租约仍被保留，避免会话被错误结束。内核检查点是进程内一致观测数据，不是崩溃恢复文件，也不会写入 SQLite StateStore。

组件资源查询同时返回三个维度：`status` 是调谐得到的实际组件状态，`effectiveStatus` 叠加内核闸门后的有效状态，`suspensionReasons` 给出当前冻结来源。内核暂停时，一个实际为 `RUNNING` 的组件仍保持 `status: RUNNING`，但会返回 `effectiveStatus: SUSPENDED`、`available: false` 和 `suspensionReasons: [KERNEL]`。

未来若将 App、Web、TUI 部署为独立进程，需要为 `KuudraApp` 增加独立的 IPC/HTTP Server 适配；该适配应复用本 API 契约，而不引入第二套 Web 模块或再次暴露 Runtime。
