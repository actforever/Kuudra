# Kuudra 资源控制模型

Kuudra 的配置和控制面遵循面向资源的风格：组件以声明式 YAML 装配，运行时以稳定的资源身份查询和操作。它借鉴 Docker Compose 的“组件集合 + 显式生命周期”体验，但不把 Flow 当作一个进程或容器。

## 身份与边界

资源的逻辑身份和规范路由地址为 `kind/namespace/name`，例如 `EventSource/macros/keyboard-hook`。`namespace` 是 App 内强制执行的资源隔离边界；Flow 只能导入同一 namespace 的资源，并在自身路由图中为它们分配局部别名。插件组件定义是另一层身份，格式为 `type/plugin-namespace/component-name`，例如 `event-source/hello-world/loop-emitter`。

这两层身份必须区分：一个插件组件定义可被多个 Flow 声明为独立资源，而每个资源各自拥有启停状态和目标节点。

## 首期资源

当前已经支持查询全部六类组件资源，EventSource 额外支持启动和停止。Interpreter、Adapter、Ingress、Handler 和 Egress 仍由 App 装配及插件生命周期管理，暂不支持单组件热卸载。

未来新增资源种类时，应沿用同一身份、状态和 App API，而不是为 CLI 或 Web 单独发明控制逻辑。

## 跨 Flow 组件复用方向

Component 资源是 App 所有的命名实例；同一个 `kind/namespace/name` 被任意 FlowBinding 引用时始终复用该实例。不同 `metadata.name` 才表示不同实例。

后续应引入“组件定义、命名实例、Flow 绑定”三层模型，而不是按 Java 类型或插件组件引用隐式全局单例：

```text
插件组件定义 ──创建──→ App 级命名实例 ──绑定──→ Flow A / node input
                                      └──绑定──→ Flow B / node input
```

- 插件组件定义描述实现、实例数量限制、并发能力和建议的默认作用域；
- App 级配置显式声明命名实例及 options，实例 ID 才是复用身份；
- Flow 只声明对命名实例的绑定和目标节点，同一 EventSource 可以向多个 Flow 投递；
- App 启动共享实例一次，并在全部绑定解除或 App 停止时停止一次；Flow 启停仍不隐式改变资源生命周期；
- 多个资源可以引用同一个插件组件定义，但它们是不同实例；只有资源身份相同才表示复用。

EventHandler 同样采用显式资源复用。插件声明 `threadSafe` 能力；非线程安全实例可被多个 Flow 引用，但 Runtime 会按实例串行调用。对于 `awt.Robot` 一类稀缺对象，可以让多个 binding 引用同一个命名 Handler 资源；若要隔离状态，则声明多个不同名称的资源。

这套三层模型已经进入当前 YAML schema：插件注册定义，具体 kind 清单声明 App 级命名实例，Flow 通过 import 建立绑定。SQLite 已持久化启动期调谐状态；运行期写 API、后台持续调谐和热卸载仍是后续工作。完整格式见 [资源清单与调谐模型](kuudra-resource-manifests.md)。

## 状态语义

- `RUNNING`：事件源已在 Runtime 注册，允许向目标节点投递 Event。
- `STOPPED`：事件源未注册；其外部监听或循环应随 `stop` 释放。
- Flow 是路由声明，没有状态机。App 暂停会冻结全部后续路由，Session 暂停只冻结对应会话；两者都保留组件实例、上下文、状态和已排队事件。

## 配置与控制

使用资源清单的具体 `kind`（例如 `EventSource`）声明资源，以 `metadata.namespace/name` 隔离和命名，并通过 `spec.desiredState` 设置期望状态。App 的 REST 适配器提供列表、详情、`start`、`stop` API；未来 `kuudractl get event-source -n <namespace>` 应直接调用这些 App API。
