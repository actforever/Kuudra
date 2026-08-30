# Kuudra v0.5 配置与使用指南

本文是 `v0.5.1-alpha-1` 的用户入口。v0.5 只接受 `kuudra.io/v1alpha2` 清单；
旧的 `v1alpha1`、`Flow`、`EventHandler` 资源、`spec.component` 与 `desiredState`
不会被静默兼容，加载器会给出迁移提示。

## 1. 运行目录与启动

打包后的 Web JAR 固定使用其同目录下的 `.kuudra`：

```text
.kuudra/
  config.yaml
  plugins/                  # 所有 JAR 都会被严格加载
  manifests/                # 仅允许 Resource
  abilities/                # 仅允许 Ability
    profiles/               # 仅允许 AbilityProfile
  locale/
  logs/latest.log
  state/kuudra.db
```

启动命令：

```powershell
java -jar kuudra-web-v0.5.1-alpha-1.jar
```

首次启动会补齐以上目录和缺失的 `config.yaml`，不会覆盖已有配置。插件 home
仍为 `<plugins>/<plugin-namespace>/<plugin-id>`，仅在插件进入初始化时创建。

## 2. App 配置

配置按“包内默认值、`<home>/config.yaml`、显式配置”的顺序深度合并。键名使用
lowercase-kebab-case。一个可运行的配置如下：

```yaml
home-directory: .kuudra
banner-enabled: true

runtime:
  queue-capacity: 1024
  worker-threads: 2
  max-event-hops: 256
  dispatcher-poll-interval-ms: 200
  shutdown-session-drain-timeout-ms: 5000
  ability-drain-timeout-ms: 5000
  cancel-grace-timeout-ms: 5000
  resource-lifecycle-timeout-ms: 120000

ability-profiles:
  - default

abilities:
  - automation/suspend-ping

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

global-context: {}
```

`ability-profiles` 决定本次启动选择哪些全局 Profile；`abilities` 可以使用完整的
`namespace/name` 直接选择少量 Ability。两者产生的启动 claim 取并集。列表中的重复值、
格式错误或不存在的 Ability 会使启动失败；高优先级配置中的列表整体替换低优先级列表。
v0.5 已移除 `resource-selection` 和根级 `runtime.session-coordinator`：部署选择由这两类 claim 完成，
Session 调度由每个 CREATE Ingress 节点声明。
Profile 文件存在并不等于被启用；示例 Ability 要自动运行时必须显式写入对应名称，例如
`ability-profiles: [default]`，也可以直接写 `abilities: [automation/suspend-ping]`。两个列表均为空时，
没有运行时 direct override 的 Ability 保持 DISABLED；`inherit` 恢复配置与 Profile 合并后的状态。

三个新超时分别约束 Ability 排空、取消后的宽限期和单次 Resource 生命周期调用。
所有值以毫秒计，必须为非负数。

## 3. ResourceTemplate 与 Resource

插件发布 `ResourceTemplate`，清单声明 `Resource` 实例。支持的 kind 是
`EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`Controller` 和
`Egress`。静态初始化配置只允许写在 Resource 的 `spec.options` 中，且不得包含
`${...}` 占位符。

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata:
  namespace: automation
  name: process-control
spec:
  template: actforever/process-control/process-controller
  options:
    allowElevation: true
    targets:
      ping:
        executablePath: 'C:\Windows\System32\PING.EXE'
```

`spec.template` 固定为 `plugin-namespace/plugin-id/template-name`。App 结合 kind
形成完整引用，例如 `controller/actforever/process-control/process-controller`。
Resource 不再声明 `desiredState`；它是否物化及应处于 RUNNING、PAUSED 或 DESTROYED，
完全由有效 Ability claim 推导。

所有 v0.5 Resource 都使用统一的 `ResourceLifecycle`：
`initialize/start/pause/resume/stop/destroy`。App 是唯一生命周期所有者。
ResourceTemplate 的 `maxInstances`、`APP/ABILITY` 限额、`exclusivityDomain` 和
`allowParallel` 在物化与调度时生效。
对 EventSource，App 会在 `start()` 前完成 Ability 注册和 emitter 绑定，并在同批
Resource 中最后启动 Source。

EventAdapter 用于单个 Event 的无状态过滤/映射；EventInterpreter 用于连击、序列和时间窗。
Interpreter 可以在窗口到期后主动输出，不要求等待下一次输入。它的状态按 Ability 节点隔离，
即使多个 Ability claim 同一个 Resource，也不会共享点击计数。暂停、禁用或注销 Ability 会
清空未完成窗口，恢复后从零开始。

official default plugin 的通用计数窗口可用于单击、双击、三击：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: EventInterpreter
metadata: {namespace: input, name: click-window}
spec:
  template: kuudra-official/default/count-window-event
  options:
    timeoutMs: 400
    maxCount: 3
    debounceMs: 50
    outputTypes:
      1: input.click.single
      2: input.click.double
      3: input.click.triple
    includeMatchedEvents: true
```

达到 `maxCount` 会立即输出并清空；没有达到时在 `timeoutMs` 到期后按照实际数量输出。
`outputTypes` 必须完整覆盖 `1..maxCount`。输入类型和鼠标按钮等无状态筛选应放在上游
EventAdapter，不应耦合进计数解释器。

## 4. Controller 的具名入口

一个 Controller 可以公开多个 `@EventHandler` 方法。Ability 节点必须通过
`handler` 指定入口，所以“路由到哪个对象”和“调用对象的哪项功能”是两个显式选择。
处理方法签名固定为：

```java
CompletionStage<Void> handle(KuudraEvent event, EventHandlerContext context)
```

动态参数写在节点的 `arguments`，处理器通过 `context.arguments()` 读取；这里允许
Event、Session、Ability 和 Global 上下文占位符。不要把事件相关值写入 Resource
的静态 `options`。

## 5. Ability 与 Profile 示例

下面的 Resource 文档放入 `.kuudra/manifests/`，Ability 文档放入
`.kuudra/abilities/`。它们创建一个 Session，并调用 process-control Controller 的
`suspend` 入口：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Ingress
metadata:
  namespace: automation
  name: admission
spec:
  template: kuudra-official/default/plain-ingress
---
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata:
  namespace: automation
  name: process-control
spec:
  template: actforever/process-control/process-controller
  options:
    allowElevation: true
    targets:
      ping: {executablePath: 'C:\Windows\System32\PING.EXE'}
```

`.kuudra/abilities/suspend-ping.yaml`：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Ability
metadata:
  namespace: automation
  name: suspend-ping
spec:
  executionClass: DATA
  resources:
    admission: Ingress/automation/admission
    process: {kind: Controller, namespace: automation, name: process-control}
  nodes:
    admit:
      resource: admission
      arguments: {group: ping}
      session:
        mode: CREATE
        scheduling:
          policy: PARALLEL
          groupScope: INGRESS
          maxParallelSessions: 1
          queueCapacity: 8
    suspend:
      resource: Controller/automation/process-control
      handler: suspend
      arguments:
        target: ping
        pid: '${event#pid}'
        durationMillis: 2000
  edges:
    - {from: admit, to: suspend}
```

`spec.resources` 是可选 alias 表，值既可以是 `kind/namespace/name` 字符串，也可以是
`{kind, namespace, name}` 对象。节点的 `resource` 同样接受两种完整引用；不含 `/` 的
字符串才按 alias 解析。Resource 引用必须显式包含 namespace，绝不继承 Ability
namespace。未使用 alias 合法但不产生 claim。

Resource 和 Ability 自身没有显式 `metadata.namespace` 时均使用 `default`，与 Kubernetes
习惯一致。同一 Resource 被多个节点或 alias 引用时，claim 按完整三元组去重。

把 Profile 单独放入 `.kuudra/abilities/profiles/default.yaml`：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: AbilityProfile
metadata:
  name: default
spec:
  abilities: [automation/suspend-ping]
  namespaces: []
  exclude: []
```

Profile 是全局资源，没有 namespace。`abilities` 精确选择 Ability，`namespaces`
选择整个 Ability namespace，`exclude` 再排除具体项。多个被选 Profile 的 claim
取并集。运行时直接控制优先于 Profile；`inherit` 清除直接覆盖并重新继承 Profile。

Ability 可使用同 namespace 的 `dependsOn` 与 `mutexWith`。依赖禁用或暂停会级联到
依赖方；互斥 Ability 同时被 claim 时拒绝收敛。

## 6. CREATE 与 JOIN Session

Ingress 节点必须显式声明：

- `CREATE`：建立 Session，并就地声明 scheduling 与 dependencies；
- `JOIN`：通过 `targetIngress` 指向同一 Ability 内一个 CREATE Ingress，将工作加入
  唯一匹配的活动 Session。

CREATE 的本地默认值是 `PARALLEL`、`INGRESS`、`64`、`256`；group scope 只能是
`INGRESS` 或 `ABILITY`。JOIN 不得重复声明 scheduling/dependencies；零匹配或多匹配
都会拒绝路由。加入的工作共享目标 Session 的失败、取消和工作租约。

## 7. Ability 控制与 HTTP 验证

Ability 控制是异步请求，接口立即返回 `202 Accepted`：

```http
GET  /api/v1/runtime/abilities
GET  /api/v1/runtime/abilities/{namespace}/{name}
POST /api/v1/runtime/abilities/{namespace}/{name}/enable
POST /api/v1/runtime/abilities/{namespace}/{name}/pause
POST /api/v1/runtime/abilities/{namespace}/{name}/resume
POST /api/v1/runtime/abilities/{namespace}/{name}/disable
POST /api/v1/runtime/abilities/{namespace}/{name}/inherit

GET  /api/v1/runtime/resources
GET  /api/v1/runtime/resources/{kind}/{namespace}/{name}
GET  /api/v1/runtime/sessions

GET  /api/v1/plugin/resource-templates
GET  /api/v1/plugin/resource-templates/{type}/{namespace}/{pluginId}/{name}
```

旧的 `/runtime/flows`、`/runtime/components` 和
`/plugin/component-templates` 不再存在。Knife4j 位于 `/doc.html`，OpenAPI 分组仍为
`all`、`kuudra`、`runtime`、`plugin`、`system-events`。

禁用 Ability 时先关闭新事件闸门，再等待活动 Session 排空；超时后请求协作取消并
等待宽限期，最后注销图并停止无 claim Resource。暂停 Ability 不销毁 Resource，
也不会覆盖用户对单个 Session 设置的暂停状态。

## 8. Windows 原生能力边界

`actforever/windows-native-host` 是类型化 Windows 原生能力宿主，不是 PowerShell 或
任意 Shell 执行器。加载宿主 JAR 不触发 UAC；只有某个被 Ability claim 的下游
Resource 初始化且 `allowElevation: true` 时才启动提权 broker。可执行目标必须来自
静态绝对路径 allowlist，Event/arguments 只能选择别名、PID、类型化动作和有界时长。

`actforever/process-control` 在 v0.5 中是包含 `suspend`、`resume` 两个具名入口的
Controller。未来软断网、硬断网与恢复网络也应作为宿主提供的类型化能力，由下游
Controller 二次封装；不得通过配置植入任意 PowerShell 命令。

## 9. 音频提示能力

`actforever/audio-host` 提供无提权的跨平台音频租约，`actforever/audio-player` 将其封装为
具有 `play`、`play-random`、`pause`、`resume`、`stop`、`set-volume` 入口的 Controller。
音频文件固定从 `<plugins>/actforever/audio-player/audio` 读取，支持 WAV、MP3 和 OGG。

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata: {namespace: notification, name: audio}
spec:
  template: actforever/audio-player/audio-player
  options: {directory: audio, recursive: true, defaultVolume: 0.8}
```

Ability 节点使用 `handler: play` 和动态 `arguments.track` 选择提示音；`awaitCompletion: true`
表示播完后再执行下游。完整的租约、暂停和文件边界见 `docs/kuudra-audio.md`。

## 10. 状态库与故障定位

每次启动都以磁盘上的 v1alpha2 清单为权威集合，并事务写入
`state/kuudra.db`。首次打开旧 v0.4 数据库时，Kuudra 只重建自身的核心
`resources` 表并写入 schema version；其他插件表不会被删除。Resource、Ability
和 AbilityProfile 会保留 generation/observedGeneration 观测数据。

常见启动错误：

- `migrate ... to ...`：仍在使用 v1alpha1/Flow/EventHandler 清单；
- `Unknown ResourceTemplate`：插件 JAR 缺失或 `spec.template` 写错；
- `Controller node must select handler`：Controller 节点未写具名入口；
- `JOIN targetIngress`：JOIN 没有指向同一 Ability 内的 CREATE 节点；
- `Mutually exclusive Abilities`：当前 Profile/直接覆盖同时 claim 了互斥项；
- `maxInstances exceeded`：ResourceTemplate 的 APP 或 ABILITY 实例限额被突破。

日志默认写入 `.kuudra/logs/latest.log`。正常停止会归档为日期序号 `.log.gz`；
修改 manifests、abilities 或 profiles 后使用内核 restart 重新读取，修改根 `config.yaml` 后重启
Web 进程。
