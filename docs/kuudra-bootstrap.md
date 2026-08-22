# Kuudra 配置与启动

当前最小可用启动链路是：`kuudra-web` 创建 `KuudraApp`；App 合并配置，加载插件目录中的 JAR，解析插件元数据与依赖，启动插件，再将 `flows/*.yaml` 编译为 `KuudraFlow`，最后注册并启动每个事件源。

## 配置优先级

App 按以下顺序深度合并配置，同名值由高优先级覆盖：

1. 初始化 `KuudraApp` 时直接传入的配置文件或 `KuudraConfigResource`；
2. `<home-directory>/config.yaml`；
3. `kuudra-app/src/main/resources/config.yaml` 中的内置默认配置。

`home-directory` 由内置默认和显式配置共同确定，默认值为 `.kuudra`。家目录配置可以覆盖内置配置的任意部分；显式配置还可覆盖家目录配置。映射会递归合并，未覆盖的嵌套值继续保留。

Standalone App 以当前工作目录作为相对路径基准；打包后的 Web 以可执行 JAR 所在目录为基准。旧的 `KUUDRA_CONFIG_PATH`、`kuudra.config.path` 和 Spring `kuudra.*` 配置入口不再使用。

## 配置格式

全部 YAML 配置键使用小写 kebab-case：

```yaml
home-directory: .kuudra
runtime:
  queue-capacity: 1024
  worker-threads: 2
global-context: {}
```

插件与 Flow 使用固定的 Kuudra 家目录布局，不再提供 `plugins.directories`、`plugins.home-directory`、`plugins.load` 或 `flows-directory` 配置项：

```text
<home-directory>/
  config.yaml
  plugins/
    plugin-a.jar
    plugin-a/
  flows/
    flow-a.yaml
```

App 会尝试加载 `plugins/` 下所有以 `.jar` 结尾的普通文件。每个 JAR 都必须包含合法的 `META-INF/kuudra-plugin/metadata.toml`、有效入口类与一致的插件 ID；缺少元数据、JAR 损坏、入口类错误、重复 ID、依赖缺失或依赖循环都会使 App 启动失败并退出内核。插件进入初始化时，其运行时家目录固定为 `plugins/<plugin-id>/`。`flows/` 下所有 `.yaml` 和 `.yml` 文件都会被加载。

Flow YAML 使用 `components` 和 `routes`。节点 `type` 支持 `event-source`、`event-adapter`、`event-processor`、`session-allocator` 和 `actor`；`component` 使用 `namespace/component-id`。Session Allocator 选项使用 `admission-key` 与 `parent-termination-policy` 等 kebab-case 键。

启动顺序如下：

1. App 合并三层配置，`KuudraYamlLoader` 编译全局配置与 Flow YAML；
2. App 扫描插件目录中的 `*.jar`，读取 `META-INF/kuudra-plugin/metadata.toml`；
3. `DefaultPluginManager` 按依赖关系启动全部插件并注册组件；
4. App 编译并注册 Flow，再启动其中启用的 EventSource 资源。

任一步失败都会使 App 进入 `FAILED` 并释放已创建的 Runtime、插件与 ClassLoader。`POST /api/v1/app/restart` 使用 App 创建时已经合并并编译的配置重新建立内核。

## 组件 options 与占位符

占位符链路如下：

1. `KuudraYamlLoader` 读取 Flow YAML，将节点 `options` 保存为未解析的 Map 模板；此时 Event 和 Session 尚不存在，因此不会做字符串替换。
2. `KuudraApp` 编译 Flow 时，把 Adapter、Processor 和 Actor 的 options 模板保存在对应 `FlowNode` 中。
3. 每个 Event 到达节点时，`KuudraRuntime.execute` 构造当前 `EventContext`，其中包含 Event 对应的 Flow、可选 Session、Session 最新快照以及 App 的只读 `global-context`。
4. Runtime 调用 `PlaceholderResolver.resolveMap`，递归遍历 options 中的 Map、List 和字符串，针对这一次 Event 生成新的不可变配置 Map；原模板不会被修改，因此同一节点处理后续 Event 时会重新解析。
5. Adapter 和 Processor 从 `EventContext.configuration()` 获取解析结果；Actor 从 `ActionContext.configuration()` 获取同一份解析结果。

例如：

```yaml
global-context:
  profile: production

# <home-directory>/flows/example.yaml 中某个 actor 节点
options:
  key: ${event.data.input.key}
  mode: ${session.values.mode}
  profile: ${global.profile}
  label: ${flow.id}:${event.type}
```

支持的表达式为：

| 作用域 | 表达式 | 值来源 |
| --- | --- | --- |
| Event | `${event.id}`、`${event.type}`、`${event.occurredAt}` | 当前 Event 的基础字段 |
| EventData | `${event.data.<namespace>.<key>}` | 当前 Event 的命名空间数据，可继续访问嵌套 Map |
| Session | `${session.id}`、`${session.flowId}` | 当前绑定的 Session |
| Session 数据 | `${session.values.<key>}` | 执行该节点时的 Session 最新快照，可继续访问嵌套 Map |
| 全局配置 | `${global.<key>}` | 合并配置中的 `global-context`，可继续访问嵌套 Map |
| Flow | `${flow.id}` | 当前 Flow ID |

若整个字符串只有一个占位符，解析器保留原值类型，例如数字、布尔值、Map 或 List；若占位符嵌在较长字符串中，则通过 `String.valueOf` 转为文本。普通非字符串标量保持不变。表达式不存在会使当前节点执行失败，不会静默生成空值；无 Session 的节点引用 `${session...}` 会抛出明确错误。

这条链路对 `event-adapter`、`event-processor` 和 `actor` 已闭环，并有 API 解析测试与 Runtime 组件注入测试覆盖。`session-allocator` 的策略在 App 启动编译 Flow 时就必须确定，不能使用事件期占位符；`event-source` 没有输入 Event 上下文，当前契约也不接收节点 options，因此同样不支持动态占位符。
