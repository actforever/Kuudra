# Kuudra App 管理 API

`kuudra-web` 是唯一的 HTTP 适配层：它注册 `KuudraApp` 外观，并将 App 的生命周期、Flow、Session 与系统事件适配为 REST/SSE。HTTP API 中不出现 Runtime；Runtime 仅是 App 在 `RUNNING` 时内部持有的内核资源。

App 生命周期独立于 Flow 生命周期：`stop` 关闭 Runtime 与插件资源，但 Web 进程仍可接收 `start` 或 `restart`，重新创建 App 内核。

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

暂停由 App 编排：先把状态切换为 `PAUSING`，再要求 Runtime 关闭执行闸门并等待所有已经进入节点执行的工作抵达安全点；随后 Runtime 调用实现了 `PausableLifecycle` 的组件、暂停仍活跃的 Session，并生成一致检查点。只有这些步骤全部完成后 App 才进入 `PAUSED`，所以 `POST /pause` 成功返回本身就是“内核已经静止”的确认。普通 `Lifecycle.stop()` 不参与暂停，避免销毁实例或丢失插件队列；未实现可暂停生命周期的组件仍被 Runtime 闸门隔离，并在资源状态中显示为 `QUIESCED`。恢复按相反方向进行，不重建组件、Session 或上下文。

`stop` 和 `restart` 在 `PAUSING`、`PAUSED` 期间仍然有效。stop 会把状态抢占为 `STOPPING`，关闭 Runtime、插件、Session、队列、上下文和检查点；如果 Runtime 仍在等待暂停安全点，关闭信号会唤醒并终止该等待。restart 等待上述破坏性停止完成后，从 `STOPPED` 创建一套全新的内核资源。也就是说 pause/resume 是非破坏性运行态子流程，而 stop/restart 始终采用“全部丢弃再重建”的语义。

`CancellationToken.isPauseRequested()` 和 `awaitResumed()` 同时观察内核级与当前 Session 级暂停。长时间运行的 Handler 应在合适的协作点检查它们；Runtime 不会强杀插件线程。内核检查点是进程内一致观测数据，不是崩溃恢复文件，也不会写入 SQLite StateStore。

未来若将 App、Web、TUI 部署为独立进程，需要为 `KuudraApp` 增加独立的 IPC/HTTP Server 适配；该适配应复用本 API 契约，而不引入第二套 Web 模块或再次暴露 Runtime。
