# Kuudra 配置与使用指南

本文面向 Kuudra 使用者，给出一套可以实际启动的配置，并解释 App 配置、资源清单、命名空间、Flow、Session 与组件状态的关系。架构或清单字段发生变化时，应同步更新本文中的说明、示例和验证步骤。

## 1. 准备运行目录

首次启动后，Kuudra 会在程序旁创建 `.kuudra`：

```text
.kuudra/
  config.yaml               # 内核配置
  plugins/                  # 插件 JAR
  manifests/                # K8s 风格资源清单
  locale/                   # 用户提供的 xx_XX.json 语言文件
  logs/latest.log
  state/kuudra.db
```

本例需要把以下官方插件 JAR 放进 `.kuudra/plugins/`：

- `kuudra-hello-world-plugin`：提供周期 EventSource；
- `kuudra-default-plugin`：提供 `plain-ingress`；
- `kuudra-logging-plugin`：提供日志 EventHandler。

`plugins/` 中每一个 JAR 都会被严格加载，损坏或不是 Kuudra 插件的 JAR 会导致启动失败。

## 2. 配置内核

将 `.kuudra/config.yaml` 设置为：

```yaml
home-directory: .kuudra
banner-enabled: true

runtime:
  queue-capacity: 1024
  worker-threads: 2
  max-event-hops: 256
  dispatcher-poll-interval-ms: 200
  shutdown-session-drain-timeout-ms: 5000
  session-coordinator:
    default-policy: parallel
    default-group-scope: flow-binding
    max-parallel-sessions: 64
    queue-capacity: 256

# 清单和 StateStore 仍保留全部声明，但本次运行只实例化这些 namespace。
resource-selection:
  namespace-mode: INCLUDE
  namespaces:
    - macro
    - automation
    - system

reconciliation:
  enabled: true
  interval-ms: 1000

state-store:
  busy-timeout-ms: 5000

logging:
  level: info
  console-enabled: true
  file-enabled: true

i18n:
  preferred-locale: en_US

global-context:
  environment: development
  application-name: kuudra-demo
```

`resource-selection.namespace-mode` 可取：

- `ALL`：激活清单中的全部资源命名空间；
- `INCLUDE`：只激活 `namespaces` 中列出的一个或多个命名空间。

跨命名空间 import 不会隐式激活目标 namespace。选中的 Flow 引用了未选中 namespace 的组件时，启动会失败。

## 3. 声明组件与 Flow

在 `.kuudra/manifests/hello-world.yaml` 中写入以下多文档 YAML：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macro
  name: hello-world-source
spec:
  component: kuudra-official/hello-world
  desiredState: running
  options:
    intervalMillis: 1000
---
apiVersion: kuudra.io/v1alpha1
kind: Ingress
metadata:
  namespace: automation
  name: plain-ingress
spec:
  component: kuudra-official/plain-ingress
  desiredState: active
  options:
    groupKey: "${event#hello-world.message}"
    sessionLabels:
      role: hello-world-job
---
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata:
  namespace: automation
  name: event-logger
spec:
  component: kuudra-official/event-logger
  desiredState: running
  options:
    level: INFO
    message: "Received ${event#hello-world.message}"
    includeData: true
---
apiVersion: kuudra.io/v1alpha1
kind: SessionCoordinationPolicy
metadata:
  namespace: automation
  name: serial-hello-world-jobs
spec:
  selector:
    matchLabels:
      role: hello-world-job
  scheduling:
    policy: SERIAL
    maxParallelSessions: 1
    queueCapacity: 32
---
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata:
  namespace: automation
  name: hello-world-flow
spec:
  session:
    executionClass: DATA
  imports:
    source:
      kind: EventSource
      namespace: macro
      name: hello-world-source
    ingress:
      kind: Ingress
      name: plain-ingress
    logger:
      kind: EventHandler
      name: event-logger
  edges:
    - from: source
      to: ingress
    - from: ingress
      to: logger
```

启动后，`macro` 中唯一的 EventSource 每秒产生一个事件。`automation` Flow 跨 namespace 导入该实例，经 Ingress 创建 Session，再由日志 Handler 输出 `Received hello-world`。

### 3.1 接入平台无关的键盘和鼠标事件

键鼠业务插件不属于内核基础设施，使用个人插件身份：

- `actforever/user-interaction-spec`：不注册组件，只提供平台无关的键码、鼠标和位置类型；
- `actforever/jnativehook`：依赖上述契约并提供键盘、鼠标按钮、鼠标移动和滚轮 EventSource。

插件 namespace 与资源 namespace 是两条独立维度。例如下面的资源实例属于用户的 `macro` 资源命名空间，但实现来自 `actforever` 插件：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macro
  name: keyboard
spec:
  component: actforever/jnativehook-keyboard
  desiredState: running
```

键盘 Source 输出 `user-interaction.keyboard.pressed` 和 `user-interaction.keyboard.released`，中立键值位于 `event#user-interaction.key`。`jnativehook` EventData namespace 只保存 JSON 标量形式的诊断数据，不携带第三方事件对象。依赖契约插件的组件可以调用：

```java
KeySpec key = event.data().get("user-interaction", "key", KeySpec.class);
```

筛选属于 EventAdapter，连击、长按、组合键和序列状态机属于 EventInterpreter，不应重新塞回设备 EventSource。

鼠标移动默认不会监听：只有声明 `actforever/jnativehook-mouse-motion` 资源后才启用。它支持 `COALESCE`（窗口首个及最新位置）、`THROTTLE`（仅窗口首个位置）和 `UNLIMITED`（全部原生事件）：

```yaml
spec:
  component: actforever/jnativehook-mouse-motion
  desiredState: running
  options:
    output:
      strategy: COALESCE
      intervalMillis: 16
```

完整的 keyboard → plain-ingress → logging 示例位于外部插件仓库 `examples/user-interaction-logging`。必须同时部署 spec 与 JNativeHook 插件；后者的强制依赖和版本范围会在 ClassLoader 创建前校验。JNativeHook 已被打入插件归档，不要把独立第三方 JAR 放进严格加载的 `.kuudra/plugins/`。

`actforever/awt-robot` 在 SESSION 域提供宏执行 Handler。它既能从 YAML 对象读取 `KeySpec`，也能直接接收事件中的对象占位符：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata:
  namespace: macro
  name: replay-key
spec:
  component: actforever/awt-robot
  desiredState: running
  options:
    maxTotalSteps: 10000
    steps:
      - action: keyTap
        key: "${event#user-interaction.key}"
        holdMillis: 50
      - action: emit
        eventType: macro.completed
        copyInputData: true
        data:
          macro:
            status: completed
```

宏还支持 `if/else`、有限 `loop`、`break`、`return` 和 `cancelSession`。其中 `return` 只结束当前 Handler，`cancelSession` 通过 `CurrentSessionControl` 请求取消整个当前会话。长时间循环应使用 `ref: session#...` 条件，它会在每次判断时读取最新上下文。暂停会安全释放已按下的输入，恢复后重建逻辑保持状态。Robot 注入会登记到共享交互契约，JNativeHook 默认丢弃匹配的回捕事件，避免宏递归触发。

## 4. 理解两层资源模型

Kuudra 的可部署资源分为两层：

1. **内核基础设施资源**：`Flow`、`SessionCoordinationPolicy`。它们由 App 直接解析和编译，没有 `desiredState`；
2. **插件组件资源**：`EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`。插件提供 ComponentTemplate，App 根据组件能力创建和调谐实例。

每个资源由 `kind/namespace/name` 唯一标识。Flow 中的 alias 只是节点名称，不会复制或隔离实例；多个 Flow 导入同一资源身份时共享同一个配置、状态、生命周期和 Java 实例。

## 5. Flow 与执行类别

Flow 使用 `imports` 将资源身份绑定为局部 alias，使用 `edges` 连接 alias。省略 import 的 `namespace` 时，默认引用 Flow 自身 namespace；显式填写时可以引用另一个已激活 namespace。

`spec.session.executionClass` 可取：

- `DATA`：默认业务执行类别；内核暂停时停止新事件准入，并等待在途 DATA 工作到达安全点；
- `CONTROL`：使用独立执行器，内核暂停后仍可处理恢复、停止、Session 控制和诊断事件。

例如，可以在 `system` 中建立一条复用 `macro` EventSource 的 CONTROL Flow：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata:
  namespace: system
  name: control-observation
spec:
  session:
    executionClass: CONTROL
  imports:
    source:
      kind: EventSource
      namespace: macro
      name: hello-world-source
    ingress:
      kind: Ingress
      namespace: automation
      name: plain-ingress
    logger:
      kind: EventHandler
      namespace: automation
      name: event-logger
  edges:
    - from: source
      to: ingress
    - from: ingress
      to: logger
```

该示例用于展示 CONTROL 路由；实际控制链通常将能够产生控制语义的 EventSource 连接到官方 `system-control` Handler。CONTROL 只绕过内核 DATA 暂停闸门，不绕过组件或 Session 自身的暂停、取消，也不会在 App 停止后继续执行。

## 6. Ingress 与 SessionCoordinationPolicy

Ingress 是唯一的 RAW→SESSION 边界。它负责判断准入，并返回：

- 会话组键 `groupKey`；
- 初始 Session context；
- Session 标签。

这些字段属于具体 Ingress 组件的 `spec.options`，应通过 ComponentTemplate 文档确认。Ingress 不引用某个协调策略。

`SessionCoordinationPolicy` 在当前 Flow 和同一策略 namespace 内按 Session 标签自动选择。零个策略匹配时使用 `config.yaml` 中的默认策略；一个匹配时应用该策略；多个匹配属于歧义并拒绝准入。调度策略包括 `PARALLEL`、`SERIAL`、`IGNORE`、`CANCEL_AND_REPLACE_PENDING`、`CANCEL_AND_KEEP_PENDING` 和 `TOGGLE`。

## 7. desiredState 与实际状态

合法的 `desiredState` 由插件组件实现的接口自动推导：

- 无生命周期接口：`active/inactive`；
- `Lifecycle`：`running/stopped`；
- `PausableLifecycle`：在上面基础上增加 `paused`。

`STARTING`、`STOPPING`、`PAUSING`、`RESUMING` 是观测到的过渡状态，不能写入 `desiredState`。Flow 与 SessionCoordinationPolicy 是声明式基础设施，不接受 `desiredState`。

## 8. 占位符与作用域

支持以下写法：

```yaml
message: "${event#hello-world.message}"
session-value: "${session#key}"
flow-value: "${flow#key}"
global-value: "${global#application-name}"
automatic: "${key}"
```

RAW 节点可读取 Event、Flow、Global；SESSION 节点还可读取 Session。`${key}` 按当前可用作用域从内向外查找。占位符在 Flow 注册时预编译并校验作用域，错误的 `${session#...}` 不会进入 RAW 事件热路径后才暴露。

## 9. 启动与验证

启动 Web 发行包：

```powershell
java -jar kuudra-web-v0.4.4.jar
```

随后检查：

```text
GET /api/v1/kuudra/status
GET /api/v1/runtime/flows
GET /api/v1/runtime/components
GET /api/v1/runtime/session-coordination-policies
GET /api/v1/plugin
```

控制台和 `.kuudra/logs/latest.log` 应周期出现 `Received hello-world`。Knife4j 页面位于 `/doc.html`，可查询插件、ComponentTemplate、组件支持的 `desiredState`、配置规约和事件说明。

修改 `manifests/` 后，调用 App restart 会正常停止并重新读取完整清单。修改根 `config.yaml` 中的 namespace 选择、日志或 Runtime 参数后，应重启 Web 进程。

## 10. 常见错误

- **Unknown component**：缺少提供该 ComponentTemplate 的插件 JAR，或 `spec.component` 写错；
- **imports unavailable Component**：资源不存在，或者跨 namespace 目标没有被 `resource-selection` 激活；
- **Flow domain mismatch**：RAW/SESSION 节点连接错误，必须通过 Ingress 或 Egress 跨域；
- **unsupported desiredState**：组件实现的生命周期能力不支持该目标状态；
- **多个 SessionCoordinationPolicy 匹配**：策略 selector 重叠，需要收窄 Session 标签选择条件。

更深入的设计细节见 [事件流架构](kuudra-architecture.md)、[启动与配置](kuudra-bootstrap.md)、[资源清单与调谐](kuudra-resource-manifests.md)、[宏定义与执行](kuudra-macro.md)、[端到端验证](kuudra-e2e-verification.md) 和 [App 管理](kuudra-app-management.md)。
