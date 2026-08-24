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
logging: {level: info, console-enabled: true, file-enabled: true}
global-context: {}
```

`max-event-hops` 是单个事件谱系允许的最大路由跳数，用于阻断跨 Egress 或图内循环。`session-coordinator` 为所有 Ingress 提供默认调度参数；Component `options` 中的同名参数只覆盖该 Ingress，未声明的字段继续继承根配置。

App 严格加载 `plugins/` 中所有 JAR。损坏归档、非 Kuudra 插件、重复 ID、缺失依赖或依赖环都会令启动失败。`manifests/` 下的 YAML 递归加载，资源字段使用 K8s 风格 camelCase。

## Component 与 Flow

支持的 Component type 为 `event-source`、`event-interpreter`、`event-adapter`、`ingress`、`event-handler`、`egress`。默认 Ingress/Egress 使用 `core/default`。Adapter 的 `options.domain` 必须是 `RAW` 或 `SESSION`，输入输出域一致。

插件组件实现 `PluginComponentLifecycle` 后，会在 `initialize(PluginComponentContext)` 阶段收到当前 Component 清单的不可变 `options`。EventSource 等没有事件执行上下文的有状态资源，应在这里读取并校验启动参数；运行阶段不再重复解释 YAML。`PluginComponentContext`、`EventContext` 与 `ActionContext` 统一通过 `TypedValueMap` 提供 `configuration(key, Type)` 和带默认值的读取接口，查找、缺失值处理及 `ContextCodec` 类型转换不需要由插件重复实现。

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Component
metadata: {namespace: demo, name: ingress}
spec:
  type: ingress
  component: core/default
  desiredState: active
  options:
    groupKey: ${event#input.key}
    policy: SERIAL
    groupScope: FLOW_BINDING
    maxParallelSessions: 1
    queueCapacity: 32
```

Flow 通过 `spec.imports` 引用 Component，再用 `edges` 路由。Source 只能指向 RAW 节点；只有 Ingress 可 RAW→SESSION，只有 Egress 可 SESSION→RAW。启动顺序为：初始化目录与配置，扫描并按依赖启动插件，实例化 Component，编译校验 Flow/占位符，最后启动 EventSource。任一步失败都会释放已创建资源。

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
