# Kuudra 架构说明

Kuudra 的当前内核以单一 `Event` 和单一 `KuudraTaskQueue` 驱动。完整的组件契约、会话边界、路由规则与不变量见 [事件与会话架构](kuudra-event-architecture.md)。

模块职责：

| 模块 | 职责 |
| --- | --- |
| `kuudra-api` | Event、EventData、会话、四类组件 SPI 与状态查询 SPI。 |
| `kuudra-runtime` | Event 图、SessionAllocator、共享队列、异步 Actor 和协作式取消。 |
| `kuudra-config` | YAML/JSON/TOML 编译目标的格式无关配置模型。 |
| `kuudra-plugin` | TOML 元数据、隔离类加载、依赖排序、插件家目录和注解组件扫描。 |
| `kuudra-app` | 聚合 Runtime、配置和插件的无框架外观，并维护 App 生命周期。 |
| `kuudra-web` | 唯一 HTTP 适配器：将 App 管理接口适配为 REST、SSE 与后续 WebSocket。 |

`KuudraRuntime` 在一个 App 实例内唯一；Flow 是逻辑隔离和调度单元，不是独立消息队列。插件组件通过 `type/namespace/name` 引用，例如 `event-source/hello-world/loop-emitter`。控制平面、热重载、持久化 StateStore 与 WebSocket 适配是后续在此内核上增加的能力，不能改变 Event 路由和会话不变量。
