# Kuudra 资源控制模型

Kuudra 的配置和控制面遵循面向资源的风格：组件以声明式 YAML 装配，运行时以稳定的资源身份查询和操作。它借鉴 Docker Compose 的“组件集合 + 显式生命周期”体验，但不把 Flow 当作一个进程或容器。

## 身份与边界

资源的逻辑身份为 `(flowId, type, id)`。其中 Flow 提供命名空间、路由图和 Session 归属；`type` 标识资源种类；`id` 是 Flow YAML 中 `components` 的键。插件组件引用则是另一层身份，格式为 `type/namespace/name`，例如 `event-source/hello-world/loop-emitter`。

这两层身份必须区分：一个插件组件定义可被多个 Flow 声明为独立资源，而每个资源各自拥有启停状态和目标节点。

## 首期资源

首期实现 `event-source` 的资源查询、启动和停止。它适合首批纳入控制面的原因是事件源通常持有监听器、定时器或设备句柄，且其注册／解除注册已是明确的生命周期边界。Adapter、Processor、Actor 仍由 App 装配和插件生命周期管理，暂不支持单组件热卸载。

未来新增资源种类时，应沿用同一身份、状态和 App API，而不是为 CLI 或 Web 单独发明控制逻辑。

## 状态语义

- `RUNNING`：事件源已在 Runtime 注册，允许向目标节点投递 Event。
- `STOPPED`：事件源未注册；其外部监听或循环应随 `stop` 释放。
- Flow 的 `ACTIVE`、`PAUSED`、`STOPPING` 等状态只决定 Flow 的路由／会话闸门，不改变资源状态。

因此，暂停或停止 Flow 后，事件源可能仍显示 `RUNNING`；这是有意的可观测状态，调用方可以随后显式停止该资源。反之，启动 Flow 也不会自动重启已停止的事件源。

## 配置与控制

使用 `components.<id>.type: event-source` 声明资源，并通过 `enabled` 设置初始状态。App 的 REST 适配器提供列表、详情、`start`、`stop` API；未来 `kuudractl get event-source` 应直接调用这些 App API。
