# Kuudra 配置与启动

## 配置来源与目录

App 按优先级深度合并：初始化时显式配置、`<home-directory>/config.yaml`、包内 `config.yaml`。默认家目录是 `.kuudra`。启动会确保 `plugins/`、`manifests/`、`logs/`、`state/`、`locale/` 存在；家目录缺少 `config.yaml` 时复制包内默认文件，已有文件绝不覆盖。每个实际补建的目录以及恢复的配置文件都会在日志会话建立后提交一条 INFO 系统事件；已经存在的项目不会重复报告，restart 也不会重复回放首次初始化记录。

Web 中的 `POST /api/v1/kuudra/restart` 会重建 App 内核并重新读取 manifests，但不会重新创建承载 App 的 Spring Bean，因此不会重新合并根 `config.yaml`。修改日志、调谐、命名空间选择等根配置后需要重启 Web 进程；仅修改资源清单时使用内核 restart 即可。

```yaml
home-directory: .kuudra
banner-enabled: true
runtime:
  queue-capacity: 1024
  worker-threads: 2
  max-event-hops: 256
  session-coordinator:
    default-policy: parallel
    default-group-scope: flow-binding
    max-parallel-sessions: 64
    queue-capacity: 256
resource-selection:
  namespace-mode: ALL
  namespaces: []
logging: {level: info, console-enabled: true, file-enabled: true}
global-context: {}
```

`max-event-hops` 是单个事件谱系允许的最大路由跳数，用于阻断跨 Egress 或图内循环。`session-coordinator` 为所有 Ingress 提供默认调度参数；Component `options` 中的同名参数只覆盖该 Ingress，未声明的字段继续继承根配置。

App 严格加载 `plugins/` 中所有 JAR。损坏归档、非 Kuudra 插件、重复 `namespace/pluginId` 身份、缺失依赖或依赖环都会令启动失败。`manifests/` 下的 YAML 递归加载，资源字段使用 K8s 风格 camelCase；一个文件可使用 `---` 声明多个资源。

`state/kuudra.db` 是 SQLite StateStore，数据库访问由独立 `kuudra-state` 模块中的 MyBatis Mapper 管理。App 启动时在一个 MyBatis 事务中导入清单，按 `kind/namespace/name` 保存期望资源及 generation，再从数据库读取并装配资源；成功后写入 observedGeneration 和 `READY`。运行期间 App 以固定延迟重试未收敛或失败的组件资源；尚未监听磁盘文件变更。每轮循环提交 TRACE 级 `reconciliation.cycle.started/completed`，完成事件包含尝试调谐数、失败数和耗时；组件实际观测状态改变时提交 DEBUG 级 `component.state.changed`，包含资源身份、前后状态和 desiredState。

## 根配置参数

包内默认 `config.yaml` 是完整、带中文注释的配置样板。首次初始化家目录时会原样复制；以后新增或修改根配置字段，必须同步更新该样板、配置模型/加载测试和本节说明。

| 配置路径 | 默认值 | 说明 |
| --- | ---: | --- |
| `home-directory` | `.kuudra` | Kuudra 家目录。 |
| `banner-enabled` | `true` | 是否在内核启动时向控制台打印 Kuudra Banner。 |
| `runtime.queue-capacity` | `1024` | Runtime 事件任务队列容量。 |
| `runtime.worker-threads` | `2` | 异步节点工作线程数。 |
| `runtime.max-event-hops` | `256` | 单个事件的最大路由跳数。 |
| `runtime.dispatcher-poll-interval-ms` | `200` | 调度线程空队列轮询间隔。 |
| `runtime.shutdown-session-drain-timeout-ms` | `5000` | 停止时等待活动 Session 排空的时间；`0` 表示不等待。 |
| `runtime.session-coordinator.default-policy` | `parallel` | Ingress 默认会话调度策略。 |
| `runtime.session-coordinator.default-group-scope` | `flow-binding` | 默认会话组隔离范围。 |
| `runtime.session-coordinator.max-parallel-sessions` | `64` | 每组最大并行 Session 数。 |
| `runtime.session-coordinator.queue-capacity` | `256` | 每组等待队列容量。 |
| `resource-selection.namespace-mode` | `ALL` | `ALL` 启动全部资源命名空间；`INCLUDE` 仅启动指定命名空间。 |
| `resource-selection.namespaces` | `[]` | `INCLUDE` 模式下要启动的一个或多个 `metadata.namespace`；此时不能为空。 |
| `reconciliation.enabled` | `true` | 是否启用后台代际收敛和失败重试。 |
| `reconciliation.interval-ms` | `1000` | 上一轮调谐结束到下一轮开始之间的固定延迟。 |
| `state-store.busy-timeout-ms` | `5000` | SQLite 遇锁后的最长等待时间。 |
| `logging.level` | `info` | Kuudra 日志级别。 |
| `logging.console-enabled` | `true` | 是否输出控制台日志。 |
| `logging.file-enabled` | `true` | 是否写入和归档文件日志。 |
| `global-context` | `{}` | 全局上下文初始值。 |

命名空间选择发生在 App 调谐边界，而不是 YAML 读取边界。App 始终解析完整 `manifests/` 并把全量声明写入 StateStore；仅选中命名空间的 Component 会被实例化，Flow 会被编译并注册到 Runtime。未选中资源仍可通过控制面 API 查询，返回 `selected: false`、状态/phase `EXCLUDED`，但不能在本次部署中修改 `desiredState`。插件仍然全量加载，因为插件命名空间和资源的 `metadata.namespace` 是两套独立身份。

## 具体组件资源与 Flow

支持的资源 kind 为 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`、`SessionCoordinationPolicy` 和 `Flow`。kind 使用 PascalCase 并直接表达资源类型，不再接受 `kind: Component` 或 `spec.type`。组件资源的 `spec.component` 必须写成 `plugin-namespace/plugin-id/component-name`；App 会结合资源 `kind` 查找唯一的插件组件定义。外置 `kuudra-default-plugin` 必须部署到 `plugins/` 后才会作为 `kuudra-official/default` 加载；Ingress/Egress 仍须由清单显式声明。EventAdapter 资源不声明 domain；App 根据它在每个 Flow 中与 Source/Interpreter/Ingress/Handler/Egress 的连接位置推导 RAW 或 SESSION 域。无法唯一推导或两侧域冲突时，Flow 编译失败。

Flow import 未填写 `namespace` 时默认引用 Flow 自身命名空间；显式填写时允许跨命名空间引用同一个 `kind/namespace/name` 实例。跨命名空间不会复制资源或绕过实例限制。Flow 和被引用资源各自是否实例化仍只由 `resource-selection` 决定；若选中了 Flow 却没有选中它显式引用的资源命名空间，启动会以缺失引用失败。因而共享 `macro` 下的全局 EventSource 给 `system` 控制 Flow 时，应同时激活 `macro` 与 `system`。

Adapter 的域属于 Flow import binding：同一个 Adapter 实现以及同一个 Component 资源都可以在 RAW 和 SESSION 两侧绑定。相同 `kind/namespace/name` 永远指向同一个 App 所有实例；alias 只标识节点，需要隔离时必须声明不同名称的资源。每个 binding 的 `options` 都按推导域预编译，RAW binding 不允许引用 `${session#...}`，SESSION binding 则允许。插件声明 `threadSafe=false` 时，Runtime 会按资源实例串行化所有 binding 的调用。

插件组件实现 `PluginComponentLifecycle` 后，会在 `initialize(PluginComponentContext)` 阶段收到当前 Component 清单的不可变 `options`。EventSource 等没有事件执行上下文的有状态资源，应在这里读取并校验启动参数；运行阶段不再重复解释 YAML。`PluginComponentContext`、`EventContext` 与 `ActionContext` 统一通过 `TypedValueMap` 提供 `configuration(key, Type)` 和带默认值的读取接口，查找、缺失值处理及 `ContextCodec` 类型转换不需要由插件重复实现。

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Ingress
metadata: {namespace: demo, name: ingress}
spec:
  component: kuudra-official/default/plain-ingress
  desiredState: active
  options:
    groupKey: ${event#input.key}
    sessionLabels: {role: job}
---
apiVersion: kuudra.io/v1alpha1
kind: SessionCoordinationPolicy
metadata: {namespace: demo, name: serial-jobs}
spec:
  selector: {matchLabels: {role: job}}
  scheduling: {policy: SERIAL, maxParallelSessions: 1, queueCapacity: 32}
```

Flow 通过 `spec.imports` 引用 Component，再用 `edges` 路由。Source 只能指向 RAW 节点；只有 Ingress 可 RAW→SESSION，只有 Egress 可 SESSION→RAW。启动顺序为：初始化目录与配置，扫描并按依赖启动插件，实例化 Component，编译校验 Flow/占位符，最后启动 EventSource。任一步失败都会释放已创建资源。

清单解析错误包含 YAML 文件、文档序号、附近行号、资源身份、字段路径和正确格式示例。App 每次 `start`（包括 `restart` 完成正常停止后的重新启动）都会重新递归读取 `<home-directory>/manifests`，并用该完整集合覆盖 StateStore 的期望状态；当前不在运行期间监听文件变化。

## 占位符编译与取值

支持 `${path}` 自动查询，以及 `${event#path}`、`${session#path}`、`${flow#path}`、`${global#path}` 严格查询。不存在 `rawEvent#`。

- RAW：Event → Flow → Global；显式 Session 引用在 Flow 注册时失败。
- SESSION：Event → Session → Flow → Global。

Runtime 注册 Flow 时基于节点输入域调用 `PlaceholderResolver.compileMap`，一次完成正则扫描、路径切分、Map/List 递归编译、静态 JSON 解析和作用域校验，并缓存 `CompiledMap`。事件热路径只读取动态值和组装结果。

YAML 原生数字、布尔、Map、List 保持类型。JSON 对象/数组字符串解析成不可变兼容值；含占位符的 JSON 在插值后解析。默认 ContextCodec 在写入时把 POJO 编码为 JSON 树，只有 `get("key", Type.class)` 或 `configuration("key", Type.class)` 才按需转换。

## SessionCoordinationPolicy

`Flow`、`SessionCoordinationPolicy` 属于 Kuudra 内核直接解析和维护的基础设施资源；`EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress` 则是插件提供 ComponentTemplate 后由 App 实例化的上层资源。两层资源使用相同的 `apiVersion/kind/metadata/spec` 信封、StateStore 和查询入口，但只有插件组件资源具有 `desiredState`。

Flow 可通过 `spec.session.executionClass` 选择执行平面：

```yaml
spec:
  session:
    executionClass: CONTROL # 默认 DATA
```

`DATA` Flow 在内核暂停时停止准入和继续执行；`CONTROL` Flow 使用独立执行器，在 `PAUSED` 状态仍可路由，以承载恢复、停止、Session 控制和诊断事件。`STOPPING/STOPPED` 对两类 Flow 一视同仁，停止后不存在继续运行的插件组件。Ingress 仍是唯一 RAW 到 SESSION 边界：其组件通过 `IngressDecision.Accepted` 输出组键、初始 Session 上下文和标签；YAML 中这些参数位于该 Ingress 的 `spec.options`，具体字段由组件文档定义。Ingress 不持有 `SessionCoordinationPolicy` 引用。

| 参数 | 含义 |
| --- | --- |
| `policy` | `PARALLEL`、`SERIAL`、`IGNORE`、`CANCEL_AND_REPLACE_PENDING`、`CANCEL_AND_KEEP_PENDING`、`TOGGLE` |
| `maxParallelSessions` | PARALLEL 组内上限，默认 64 |
| `queueCapacity` | 有界等待容量，默认 256 |
| `spec.selector.matchLabels` | 选择当前命名空间、当前 Flow 中由 Ingress 产出的 Session 标签 |

Ingress 只计算准入、组键、初始上下文和 Session 标签，不引用协调策略。Runtime 在当前 Flow 编译同命名空间的全部 `SessionCoordinationPolicy`，准入时按标签自动选择：零匹配使用根配置 `runtime.session-coordinator` 的有界默认策略；恰好一个匹配时采用该策略；多个匹配属于歧义并拒绝准入。SessionManager 创建会话并维护工作租约，Runtime 唯一的 SessionCoordinator 执行策略、管理队列和依赖图。

策略的 `dependencies[].requiredSessionSelector.matchLabels` 只匹配同一 Flow 内的活动 Session，不接受 `flowId` 或 Ingress 身份。组内调度先决定事件何时真正启动，随后 Coordinator 才按标签原子解析依赖并登记图，因此 SERIAL 等待项不会绑定已经结束的 Session。选择器支持 `UNIQUE`、`LATEST`、`ALL`；`terminationPropagation` 支持 `CANCEL_DEPENDENT`、`CANCEL_REQUIRED`、`CANCEL_BOTH`。依赖无法满足时不向 SESSION 域路由，并发布 `session.dependency.rejected`。`GET /api/v1/runtime/session-coordination-policies` 查询声明，`GET /api/v1/runtime/sessions/dependencies` 查询活动边。
