# Kuudra 配置与启动

## 配置来源与目录

App 按优先级深度合并：初始化时显式配置、`<home-directory>/config.yaml`、包内 `config.yaml`。默认家目录是 `.kuudra`。启动会确保 `plugins/`、`manifests/`、`logs/`、`state/` 存在；家目录缺少 `config.yaml` 时复制包内默认文件，已有文件绝不覆盖。

```yaml
home-directory: .kuudra
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

`state/kuudra.db` 是 SQLite StateStore，数据库访问由独立 `kuudra-state` 模块中的 MyBatis Mapper 管理。App 启动时在一个 MyBatis 事务中导入清单，按 `kind/namespace/name` 保存期望资源及 generation，再从数据库读取并装配资源；成功后写入 observedGeneration 和 `READY`。运行期间 App 以固定延迟重试未收敛或失败的组件资源；尚未监听磁盘文件变更。

## 根配置参数

包内默认 `config.yaml` 是完整、带中文注释的配置样板。首次初始化家目录时会原样复制；以后新增或修改根配置字段，必须同步更新该样板、配置模型/加载测试和本节说明。

| 配置路径 | 默认值 | 说明 |
| --- | ---: | --- |
| `home-directory` | `.kuudra` | Kuudra 家目录。 |
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

支持的资源 kind 为 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress` 和 `Flow`。kind 使用 PascalCase 并直接表达资源类型，不再接受 `kind: Component` 或 `spec.type`。内置 `kuudra-default-plugin` 作为 `kuudra-official/default` 插件默认加载，但默认 Ingress/Egress 仍须由清单显式声明。Adapter 的 `options.domain` 必须是 `RAW` 或 `SESSION`，输入输出域一致。

插件组件实现 `PluginComponentLifecycle` 后，会在 `initialize(PluginComponentContext)` 阶段收到当前 Component 清单的不可变 `options`。EventSource 等没有事件执行上下文的有状态资源，应在这里读取并校验启动参数；运行阶段不再重复解释 YAML。`PluginComponentContext`、`EventContext` 与 `ActionContext` 统一通过 `TypedValueMap` 提供 `configuration(key, Type)` 和带默认值的读取接口，查找、缺失值处理及 `ContextCodec` 类型转换不需要由插件重复实现。

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Ingress
metadata: {namespace: demo, name: ingress}
spec:
  component: kuudra-official/default
  desiredState: active
  options:
    groupKey: ${event#input.key}
    policy: SERIAL
    groupScope: FLOW_BINDING
    maxParallelSessions: 1
    queueCapacity: 32
```

Flow 通过 `spec.imports` 引用 Component，再用 `edges` 路由。Source 只能指向 RAW 节点；只有 Ingress 可 RAW→SESSION，只有 Egress 可 SESSION→RAW。启动顺序为：初始化目录与配置，扫描并按依赖启动插件，实例化 Component，编译校验 Flow/占位符，最后启动 EventSource。任一步失败都会释放已创建资源。

清单解析错误包含 YAML 文件、文档序号、附近行号、资源身份、字段路径和正确格式示例。App 每次 `start`（包括 `restart` 完成正常停止后的重新启动）都会重新递归读取 `<home-directory>/manifests`，并用该完整集合覆盖 StateStore 的期望状态；当前不在运行期间监听文件变化。

## 占位符编译与取值

支持 `${path}` 自动查询，以及 `${event#path}`、`${session#path}`、`${flow#path}`、`${global#path}` 严格查询。不存在 `rawEvent#`。

- RAW：Event → Flow → Global；显式 Session 引用在 Flow 注册时失败。
- SESSION：Event → Session → Flow → Global。

Runtime 注册 Flow 时基于节点输入域调用 `PlaceholderResolver.compileMap`，一次完成正则扫描、路径切分、Map/List 递归编译、静态 JSON 解析和作用域校验，并缓存 `CompiledMap`。事件热路径只读取动态值和组装结果。

YAML 原生数字、布尔、Map、List 保持类型。JSON 对象/数组字符串解析成不可变兼容值；含占位符的 JSON 在插值后解析。默认 ContextCodec 在写入时把 POJO 编码为 JSON 树，只有 `get("key", Type.class)` 或 `configuration("key", Type.class)` 才按需转换。

## Ingress 调度参数

| 参数 | 含义 |
| --- | --- |
| `policy` | `PARALLEL`、`SERIAL`、`IGNORE`、`CANCEL_AND_REPLACE_PENDING`、`CANCEL_AND_KEEP_PENDING`、`TOGGLE` |
| `groupScope` | `FLOW_BINDING`（默认）或 `INGRESS`；后者按 Component 资源身份跨 Flow 共享调度组 |
| `maxParallelSessions` | PARALLEL 组内上限，默认 64 |
| `queueCapacity` | 有界等待容量，默认 256 |
| `groupKey` | 默认 Ingress 分组表达式，默认事件 type |

Ingress 只计算准入与组键；SessionManager 创建会话并维护工作租约，SessionCoordinator 管理调度状态和队列。失败或取消只会阻止新工作，已有租约全部归还后才发布唯一终态并启动组内后继任务。
