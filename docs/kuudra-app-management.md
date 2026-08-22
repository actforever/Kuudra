# Kuudra App 管理 API

`kuudra-web` 是唯一的 HTTP 适配层：它注册 `KuudraApp` 外观，并将 App 的生命周期、Flow、Session 与系统事件适配为 REST/SSE。HTTP API 中不出现 Runtime；Runtime 仅是 App 在 `RUNNING` 时内部持有的内核资源。

App 生命周期独立于 Flow 生命周期：`stop` 关闭 Runtime 与插件资源，但 Web 进程仍可接收 `start` 或 `restart`，重新创建 App 内核。

```text
NEW → STARTING → RUNNING → STOPPING → STOPPED
                  └──────────────────────────→ FAILED
```

| 方法 | 路径 | 含义 |
| --- | --- | --- |
| `GET` | `/api/v1/app` | App 状态、队列长度和 Flow 数。 |
| `POST` | `/api/v1/app/start` | 创建并启动内核。 |
| `POST` | `/api/v1/app/stop` | 停止内核，适配器继续运行。 |
| `POST` | `/api/v1/app/restart` | 停止并重新创建内核。 |
| `GET` | `/api/v1/app/flows` | 列出 Flow。 |
| `POST` | `/api/v1/app/flows/{id}/start|pause|resume|stop` | 管理 Flow。 |
| `GET` | `/api/v1/app/sessions/{id}` | 查询 Session。 |
| `POST` | `/api/v1/app/sessions/{id}/cancel` | 请求协作式取消。 |
| `GET` | `/api/v1/app/events` | SSE 系统事件流。 |

未来若将 App、Web、TUI 部署为独立进程，需要为 `KuudraApp` 增加独立的 IPC/HTTP Server 适配；该适配应复用本 API 契约，而不引入第二套 Web 模块或再次暴露 Runtime。
