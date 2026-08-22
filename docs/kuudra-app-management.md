# Kuudra App 管理与部署

## 进程边界

默认部署中，App Daemon、Web 与 TUI 是独立进程：

```text
TUI ── REST ──┐
              ├── kuudra-app-http (:8081) ── KuudraApp ── KuudraRuntime
Web ── REST ──┘               │
                              └── SSE /api/v1/app/events
```

`kuudra-web` 不创建、不持有、也不关闭 `KuudraApp`。它只通过 App Daemon 的 HTTP API 获取状态与发起控制命令，因此 Web 的重启、崩溃或升级不会影响正在运行的 App。TUI 使用同一 API；未来 `kuudra-bundle` 可在一个 JVM 内启动多个模块，但仍必须经相同的网络契约通信。

## App 生命周期

```text
NEW → STARTING → RUNNING → STOPPING → STOPPED
                  └──────────────────────────→ FAILED
```

`stop` 关闭 Runtime、插件与其资源，但 App Daemon 保持存活，因而可以继续接收 `start` 或 `restart`。`terminate` 才请求关闭 App Daemon 进程；调用方应把它视为异步命令。

## App Daemon API

所有资源使用 `/api/v1/app` 前缀，禁止以 `runtime` 命名外部 API。

| 方法 | 路径 | 含义 |
| --- | --- | --- |
| `GET` | `/api/v1/app` | App 生命周期状态、队列长度和 Flow 数。 |
| `POST` | `/api/v1/app/start` | 创建并启动 App 内核。 |
| `POST` | `/api/v1/app/stop` | 停止内核但保留 Daemon。 |
| `POST` | `/api/v1/app/restart` | 停止后重新创建内核。 |
| `POST` | `/api/v1/app/terminate` | 请求关闭 Daemon 进程。 |
| `GET` | `/api/v1/app/flows` | 列出 Flow。 |
| `POST` | `/api/v1/app/flows/{id}/start|pause|resume|stop` | 管理 Flow。 |
| `GET` | `/api/v1/app/sessions/{id}` | 查询 Session。 |
| `POST` | `/api/v1/app/sessions/{id}/cancel` | 请求协作式取消。 |
| `GET` | `/api/v1/app/events` | SSE 系统事件流。 |

Web 默认访问 `http://127.0.0.1:8081`，由 `kuudra.app.base-url` 配置覆盖。App 不可达时，Web 的 App 状态接口返回 `UNREACHABLE`，其自身 Web 进程仍然存活。
