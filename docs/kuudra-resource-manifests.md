# Kuudra 资源清单与调谐模型

本文定义并记录 Kuudra 的资源与编排模型。当前版本从 `<home-directory>/manifests/**/*.yaml` 加载具体组件 kind 与 Flow；Flow 通过 `spec.imports` 引用本命名空间或其他已激活命名空间的组件资源并配置路由，组件资源不引用 Flow。组件 desired-state 写入和后台失败重试已经接入同一调谐链路；通用资源 apply/delete 仍是后续工作。

当前 App/Web 通过统一的 `GET /api/v1/runtime/components` 查询所有清单 Component，而不再为 EventSource 维护重复的专用 HTTP API。结果返回类型、插件 ComponentTemplate 引用、期望/实际状态、导入它的 Flow 和真实生命周期能力，也可按类型、命名空间或 `kind/namespace/name` 定位。Component 期望状态统一通过 Runtime 资源域控制，不伪造与能力不匹配的 start/stop 操作。

## 设计目标

1. 组件实例与 Flow 编排分离，避免为了复用一个 EventSource 而把不相关自动化逻辑写进同一个 Flow。
2. 资源是否复用由稳定资源身份决定，不根据 Java 类型或插件组件引用进行隐式单例推断。
3. 插件声明实现能力和实例数量约束，部署清单声明具体实例；内核联合校验两者。
4. 文件启动和未来的 `kuudractl apply` 使用同一种资源对象及调谐链路。
5. 生命周期控制表达期望状态，App 负责把实际状态收敛到期望状态并报告失败条件。

## 两层资源基础设施

Kuudra 的可部署资源分为两层：

- **内核基础设施资源**：`Flow`、`SessionCoordinationPolicy` 等由 App 直接解析、校验和编译，描述路由及会话协调规则，不由插件实例化，也没有 `desiredState`；
- **插件组件资源**：`EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress` 由插件提供 ComponentTemplate，由 App 创建实例并按组件能力调谐 `desiredState`。

两层共用 K8s 风格信封、资源身份、StateStore、命名空间选择与查询入口。插件扫描产生的 ComponentTemplate 是上层资源的类型定义，不是第三种可部署资源。

## 定义与实例化链路

```text
插件组件定义                 资源声明                         Flow 编排
annotation/metadata   →   Component   ← import/绑定 →   Flow + edges
能力、约束、实现类           身份、options、期望状态           路由、会话边界
```

### 插件组件定义

插件扫描得到的定义描述“能创建什么”，不代表实例。除类型、名称和实现类外，后续注解元数据至少需要表达：

- 是否允许共享同一实例；
- 是否保证并发安全；
- 实例数量上限；
- 数量限制的作用域；
- 互斥域；
- 支持的生命周期能力；
- options schema、输入输出和组件说明。

### Component 资源

具体组件资源描述“创建哪个实例”。两个 Flow 只有导入相同 `kind/namespace/name` 资源身份时才共享同一 Java 实例。仅仅使用相同的 `spec.component` 不会触发复用。

### Flow 配置

Flow 资源描述“实例如何连接”。它是具体组件资源的消费者：`imports` 以 kind/namespace/name 引用资源并分配 Flow 内别名，`edges` 只引用这些别名。资源不允许引用或导入 Flow；Flow 也不再内嵌组件构造信息或拥有导入实例的生命周期。`imports.*.namespace` 可省略并默认继承 Flow 的 `metadata.namespace`；显式填写其他 namespace 则形成跨命名空间绑定。无论被多少 Flow 导入，相同资源身份始终对应同一个 App 所有实例。

## 清单格式

资源清单采用 K8s 风格的信封结构，并遵循其 camelCase 字段惯例；App 根配置仍使用 kebab-case，两类配置不应混用：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macros
  name: keyboard-hook
  labels: {}
  annotations: {}
spec:
  component: native-input/jnativehook/jnativehook-keyboard
```

资源身份固定为 `(apiVersion, kind, metadata.namespace, metadata.name)`，规范路由地址为 `kind/namespace/name`。`metadata.namespace` 是部署选择与资源身份边界，不等于插件 namespace、上下文 namespace 或实例互斥域。Flow 默认引用自身 namespace，但允许显式引用其他已激活 namespace；缺省资源 namespace 可使用 `default`。

一个文件既可以只包含一个资源，也可以像 Kubernetes 一样使用 `---` 分隔多个 YAML 文档。加载器会按“文件路径 + 文档序号”定位错误，并在全部文件和文档范围内检查重复资源身份。独立文件仍更便于原子更新和人工管理，但不再是格式限制。

### 具体组件资源示例

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macros
  name: keyboard-hook
spec:
  component: native-input/jnativehook/jnativehook-keyboard
  desiredState: running
  options:
    capture-mouse: false
```

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata:
  namespace: macros
  name: keyboard-robot
spec:
  component: awt-input/robot/keyboard-robot
  desiredState: running
  options: {}
```

`kind` 直接决定资源类型，支持 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`；不再存在 `kind: Component` 或 `spec.type`。`spec.component` 使用严格的 `plugin-namespace/plugin-id/component-name` 三段式身份指向插件组件定义，例如 `kuudra-official/default/plain-ingress`。资源 `kind` 已经表达组件类型，因此不应把 `event-source` 等类型前缀写进 `spec.component`。旧的两段式写法不会被兼容，以免同一插件命名空间中的不同插件发生组件名碰撞。options 只属于资源实例；同一实例被多个 Flow 导入时不能在 Flow 中分别覆盖 options，否则共享身份将失去确定语义。

Component 的 `desiredState` 会先持久化到 SQLite，再在启动调谐阶段收敛：

- 实现 `Lifecycle` 的组件：稳定目标为 `running/stopped`；内核调用标准 `start/stop` 并记录观测状态；
- 同时实现 `PausableLifecycle` 的组件：在上述状态外增加 `paused`；从 `stopped` 调谐到 `paused` 时先启动再非破坏性暂停；
- 未实现运行生命周期的组件：稳定目标为 `active/inactive`。两者都保留资源声明和 Flow 绑定；`inactive` 关闭 Runtime 执行闸门，不再接收、转换或输出事件；
- `Flow` 是纯路由声明，不接受 `desiredState`。

插件扫描会根据实现类型生成 `supportedDesiredStates`，并随组件结构化文档经 App/Web API 暴露。清单校验和 App 调谐读取的正是同一份能力数据，不再按 kind 硬编码状态。`STARTING/STOPPING/PAUSING/RESUMING` 是 observedState 的瞬时过渡态，不是可收敛目标，不能写入 `desiredState`。

App 在状态操作成功后同步更新 Runtime 组件闸门：`INACTIVE`、`STOPPED` 与 `PAUSED` 的已绑定 Adapter/Ingress/Egress/Handler/Interpreter 都不再接收后续事件，恢复到 `ACTIVE` 或 `RUNNING` 才重新开放；组件级 `PAUSED` 也不会被一次内核级 pause/resume 意外恢复。EventSource 则通过注册状态和自身 pause/resume 能力控制事件准入。EventSource 的生命周期不依赖 Flow：声明为 `running` 后即使尚未被任何 Flow 导入也会启动，只是其事件没有投递目标；后续 Flow 绑定负责路由而非启动资源。

其他状态会令启动失败。运行期间可以通过 App 的通用 desired-state API 修改单个组件：App 先把完整期望资源集事务性写入 SQLite，再调整组件生命周期或执行闸门，成功后推进 `observedGeneration`，失败则保留新期望并记录 `FAILED`。后台调谐器按 `reconciliation.interval-ms` 固定延迟扫描未收敛 generation 和 `FAILED` 资源并重试；磁盘文件仍只在 start/restart 时重新导入。

### Flow 示例

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata:
  namespace: macros
  name: combat
spec:
  session:
    executionClass: DATA
  imports:
    keyboard:
      kind: EventSource
      name: keyboard-hook
    allocate:
      kind: Ingress
      name: combat-session
    robot:
      kind: EventHandler
      name: keyboard-robot
  edges:
    - from: keyboard
      to: allocate
    - from: allocate
      to: robot
```

`spec.session.executionClass` 默认为 `DATA`：内核暂停会阻止新事件进入并等待在途工作到达安全点。`CONTROL` Flow 使用独立执行器，内核处于 `PAUSED` 时仍可承载恢复、停止、Session 控制和诊断事件；组件级与 Session 级暂停/取消仍然有效，App 停止则会终止两种执行类别。CONTROL 只应用于有界控制路径。

以下引用允许 `system` Flow 复用 `macro` 中的全局钩子实例：

```yaml
metadata:
  namespace: system
  name: keyboard-control
spec:
  session:
    executionClass: CONTROL
  imports:
    keyboard:
      kind: EventSource
      namespace: macro
      name: global-hook
```

启动配置必须同时选择 `system` 和 `macro`。如果选中的 Flow 引用了不存在或未选中 namespace 中的资源，启动会在创建任何执行绑定前失败，而不会隐式激活遗漏的 namespace。

同一 namespace 的另一个 Flow 可以再次 import `EventSource/macros/keyboard-hook`。App 只创建并启动一个 EventSource，Runtime 为它安装一对多 emitter/binding，将产生的 Event 分别投递到两个 Flow 的目标别名。Flow 本身没有生命周期；暂停发生在 App 或 Session 层。

Flow 的结构化规约由内核以文档提供方 `kuudra-official` 注册，可通过 `GET /api/v1/kuudra/resource-documentation/kuudra-official/Flow` 查询。这里的文档提供方 namespace 不限制 Flow 实例的 `metadata.namespace`；例如文档中的 Flow 实例仍可位于 `dev`。`spec.imports` 是别名到资源引用的映射，`edges.from/to` 引用的是别名而不是资源真实名称。

Ingress 必须显式声明为 `kind: Ingress` 资源。官方无条件准入实现为 `ingress/kuudra-official/plain-ingress`，对应的透传出口为 `egress/kuudra-official/plain-egress`；加载插件本身不会隐式创建任何资源实例。Flow 是内核拥有的路由资源而非插件组件：其 `metadata/imports/edges` 由 App 校验，Runtime 将其编译为调度图，因此不应注册成与 Ingress 同级的插件实现。

## 实例数量与互斥域

“是否允许多例”应升级为通用实例策略，而不是单一布尔值：

```text
maxInstances: 1
limitScope: app
exclusivityDomain: native-input/jnativehook
threadSafe: true
```

- `maxInstances`：同一限制键允许存在的最大实例数；缺省为不限制。
- `limitScope`：首期支持 `app` 和 `flow`。App 范围覆盖全部 Flow；Flow 范围只在单个 Flow 内计数。
- `exclusivityDomain`：把不同组件定义纳入同一个资源冲突域，例如所有会安装 JNativeHook 全局钩子的实现。
- `threadSafe`：同一资源实例经多个 binding 调用时是否可并行；为 false 时 Runtime 按实例串行化全部调用。

有效限制键为 `(limitScope-owner, exclusivityDomain)`，不是实现类名。互斥域必须使用限定名称 `<authority>/<name>`；插件自己的域默认以插件 namespace 为 authority，跨插件协议则由共同依赖的契约插件或 `kuudra.system/*` 保留域定义。这样既避免无意同名冲突，也允许两个不同插件明确声明它们竞争同一个全局钩子。

插件元数据是硬约束，资源清单不能放宽它。清单可以通过不同 `metadata.name` 声明多个独立资源，但不能突破 `maxInstances` 与互斥域限制。Flow 重复引用同一 `kind/namespace/name` 时始终复用同一个 App 所有实例；alias 不会创建或隔离实例。调谐前必须先对完整期望状态执行数量与能力校验，违反约束时不创建任何一半成功的实例。

Flow 中 alias 的唯一性由 `spec.imports` 映射保证，edge 只连接 alias。一个 alias 可以拥有多个入边或出边，分别表达 fan-in/fan-out；完全相同的 `from -> to` 重复 edge 会被拒绝，避免同一事件被意外重复投递。同一 EventSource 资源在一个 Flow 中只能导入一次，需要扇出时应从该 source alias 声明多条不同出边。其他组件资源可以通过多个 alias 或多个 Flow 绑定，所有绑定仍共享同一实例。

对于 AWT Robot 等稀缺对象，可以只声明一个 EventHandler 资源并让多个 binding 引用它；插件若声明 `threadSafe=false`，Runtime 会跨 Flow 串行化该实例的调用。需要并行且相互隔离时，应使用不同 `metadata.name` 声明不同资源。

## 生命周期能力

所有资源都应具有统一的 `metadata/spec/status` 和创建、观察、更新、删除流程，但不应伪造完全相同的 start/stop 语义。

| 资源 | 期望状态 | 调谐行为 |
| --- | --- | --- |
| 实现 `Lifecycle` 的 Component | `running/stopped` | 获取或释放监听器、线程、端口、设备句柄等运行资源。 |
| 实现 `PausableLifecycle` 的 Component | `running/paused/stopped` | 在不清除组件内部状态的情况下暂停和恢复。 |
| 无运行生命周期的 Component | `active/inactive` | 保留实例与 Flow 绑定，打开或关闭 Runtime 执行闸门。资源删除才表示回收实例。 |
| Flow | 无 | 纯路由声明，不参与 desired-state 调谐。 |

组件定义的生命周期能力由插件扫描自动推导并写入结构化文档。插件作者只需实现对应标准接口；用户通过组件文档中的 `supportedDesiredStates` 查询清单可用值。

删除资源表示从期望状态中删除该资源并由调谐器回收实例，不叫“从上下文删除”。Event、Session、Flow、Global context 是数据作用域；删除其中的键属于独立的数据操作和权限问题，不能与资源 DELETE API 混为一谈。

资源状态至少包含：

```yaml
status:
  observedGeneration: 3
  phase: ready
  conditions:
    - type: Ready
      status: "true"
      reason: Reconciled
  bindings: 2
```

`metadata.generation` 在 spec 变化时递增；只有 `status.observedGeneration` 追上它，调用方才能确认新期望状态已经生效。

## 调谐与控制 API

App 保存期望资源图，调谐器按以下顺序工作：

1. 解析清单并按资源身份去重；
2. 校验 schema、插件组件存在性、实例策略、import 和 edges；
3. 构建组件、Flow 与绑定依赖图；
4. 计算创建、更新、删除差异；
5. 先创建依赖，再切换绑定，最后回收旧实例；
6. 失败时保留上一份可工作状态，并把原因写入 status condition 与 SystemEvent；
7. 持续重试可恢复错误，对不可恢复配置错误等待新的 generation。

当前实现有三种触发边界：

1. `start/restart`：重新读取完整 manifests，先完成 schema 和插件能力校验，再事务性覆盖 StateStore desired set；随后应用 `resource-selection`，只对选中命名空间校验实例共享策略并装配 Flow/组件。选中资源标记为 `READY`，未选中资源追平 observedGeneration 并标记为 `EXCLUDED`；磁盘声明始终覆盖上次运行期间的 API 修改。
2. desired-state API：先事务性写入新 generation，再立即尝试同步调谐；调用失败时保留 desired generation 并标记 `FAILED`。
3. 后台循环：仅在 App 为 `RUNNING` 时执行。默认第一轮在启动后 1000ms 运行，之后使用固定延迟；每轮查询 StateStore，选择 `generation != observedGeneration` 或 phase 为 `FAILED` 的 Component，按当前 desiredState 重试并分别更新 `READY/FAILED`。可通过 `reconciliation.enabled` 和 `reconciliation.interval-ms` 配置。

后台循环不重新读取 YAML，也不重复调谐已经收敛的资源；Flow 当前没有 desiredState，运行期间也没有 Flow apply API，因此不会被周期性重建。这样磁盘部署源、数据库控制面和 Runtime 执行面之间只有明确的单向边界。

### 启动命名空间选择

资源隔离通过一次 App 运行实际激活的命名空间集合实现。`resource-selection.namespace-mode: ALL` 部署全部命名空间；`INCLUDE` 接受一个或多个命名空间。跨命名空间 import 不扩大该集合：选中的 Flow 及它引用的每个组件 namespace 都必须已经入选，否则选择闭包校验失败。选择器只影响执行面，不过滤声明源或 StateStore，因此切换启动集合不会被误判成资源删除。资源与 Flow 查询返回 `selected`；未选中资源显示为 `EXCLUDED`，对它调用 desired-state 控制接口会失败。根配置和其他 App 设置一样在创建 App 时读取，修改后需要重新启动进程；同一 App 实例的 restart 当前只重新载入 manifests。

控制 API 应围绕资源，而不是为每种组件复制一套控制器：

```text
GET    /api/v1/resources
GET    /api/v1/resources/{kind}/{namespace}/{name}
PUT    /api/v1/resources/{kind}/{namespace}/{name}
PATCH  /api/v1/resources/{kind}/{namespace}/{name}
DELETE /api/v1/resources/{kind}/{namespace}/{name}
GET    /api/v1/resources/{kind}/{namespace}/{name}/status
```

当前通用入口是 `POST /api/v1/runtime/components/{kind}/{namespace}/{name}/desired-state/{state}`。所有 Component（包括 EventSource）使用同一个 App 调谐入口；`runtime` 只是 HTTP 资源分域，Controller 仍只依赖 App 外观，不直接暴露 Runtime 对象。

未来 `kuudractl apply -f xxx.yaml` 会把同一清单提交给 ResourceService，以资源身份和 generation 幂等更新，然后观察调谐状态。当前 App 启动和 desired-state API 已使用相同的 SQLite 持久模型与后台重试循环，但运行期通用 apply 和文件监听尚未开放。

### `state/` 与 SQLite StateStore

当前实现使用 `.kuudra/state/kuudra.db` 保存规范资源身份、完整期望 spec、generation、observedGeneration、phase 和 message。启动导入清单及运行期 desired-state 变更都由 App 使用事务写入：新增资源 generation 为 1，spec 改变时递增，未改变则保持，清单删除的资源也从期望集合移除。App 负责执行调谐，成功后将 observedGeneration 追平并标记 `READY`，失败则标记 `FAILED` 且不伪造已观测 generation。Runtime 不读写状态库。Session、事件负载、暂停检查点和插件自行持久化的数据都不进入该状态库。

启动时 `<home-directory>/manifests` 是权威声明源。即使上一次运行通过 API 把数据库中的 `desiredState` 改成了不同值，下一次启动仍会用当前磁盘清单的完整资源集合调用 `replaceDesired`：同身份但 spec 不同的资源递增 generation 并覆盖数据库，磁盘中已删除的资源从数据库删除，然后 App 按覆盖后的 desired set 调谐。因此数据库提供跨进程的 generation、观测进度、失败原因和控制面审计基础，但不会让旧运行期状态凌驾于启动清单。

## 家目录目标结构

建议目标结构为：

```text
.kuudra/
  config.yaml               # 仅内核、日志等 App 设置
  manifests/                # 用户声明的具体组件、Flow 等资源
    input/
      keyboard-hook.yaml
    actions/
      keyboard-robot.yaml
    flows/
      combat.yaml
  plugins/                  # 插件 JAR 与插件运行时家目录
  logs/
  state/kuudra.db           # SQLite 期望/观测资源状态
```

不用 `conf/`，因为它难以区分 App 配置与资源对象；不用顶层 `flows/`，因为 Flow 已经只是多种资源之一。`manifests/` 递归扫描 `.yaml/.yml`，子目录只服务人类整理，资源身份完全由 metadata 决定。未知 kind、重复身份、损坏文件或引用缺失都应使本次期望状态导入失败。

## 迁移顺序

1. 在 API/配置模块加入资源信封、Component/Flow spec、引用和校验模型，不接入运行时。
2. 为插件组件定义加入实例策略和 lifecycle capability，并完成扫描测试。
3. 实现 App ResourceRegistry、依赖图和一次性事务式 reconcile；先覆盖 Component 创建和 Flow import。
4. 将 EventSource emitter 改为一实例多绑定，并验证跨 Flow 启停与失败回滚。
5. 增加统一资源 API 和 `kuudractl apply/get/delete`，再接入持久 ResourceRepository。
6. 启用 `.kuudra/manifests/` 启动加载，并删除顶层 `flows/` 初始化与旧 Flow schema。

整个迁移期间，Event/Session 路由不变量、插件依赖类加载和 App/Web 边界保持不变。
