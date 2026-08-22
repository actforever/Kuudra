# Kuudra 配置与启动

当前最小可用启动链路是：`kuudra-web` 创建 `KuudraApp`；App 合并配置，加载插件目录中的 JAR，解析插件元数据与依赖，启动插件，再将 `flows/*.yaml` 编译为 `KuudraFlow`，最后注册并启动每个事件源。

## 配置优先级

App 按以下顺序深度合并配置，同名值由高优先级覆盖：

1. 初始化 `KuudraApp` 时直接传入的配置文件或 `KuudraConfigResource`；
2. `<home-directory>/config.yaml`；
3. `kuudra-app/src/main/resources/config.yaml` 中的内置默认配置。

`home-directory` 由内置默认和显式配置共同确定，默认值为 `.kuudra`。家目录配置可以覆盖内置配置的任意部分；显式配置还可覆盖家目录配置。映射会递归合并，未覆盖的嵌套值继续保留。

确定家目录后，App 会在解析用户配置和启动内核前统一初始化以下结构：

```text
<home-directory>/
  config.yaml
  plugins/
  flows/
  logs/
```

若 `config.yaml` 不存在，App 使用只创建、不覆盖的方式将包内 `classpath:/config.yaml` 原样复制到该位置；已有普通文件永远不会被改写。若 `config.yaml` 被误配，可删除它并重启，App 会重新生成一份当前版本的默认配置。若该路径存在但不是普通文件，或者目录无法创建/写入，启动会立即失败并给出路径错误。`plugins/`、`flows/` 与 `logs/` 即使为空也会在每次初始化时检查并补建。

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

1. App 读取包内默认配置以确定家目录，补建家目录、默认 `config.yaml`、`plugins/`、`flows/` 和 `logs/`；
2. App 合并三层配置，`KuudraYamlLoader` 编译全局配置与 Flow YAML；
3. App 扫描插件目录中的 `*.jar`，读取 `META-INF/kuudra-plugin/metadata.toml`；
4. `DefaultPluginManager` 按依赖关系启动全部插件并注册组件；
5. App 编译并注册 Flow，再启动其中启用的 EventSource 资源。

任一步失败都会使 App 进入 `FAILED` 并释放已创建的 Runtime、插件与 ClassLoader。`POST /api/v1/app/restart` 使用 App 创建时已经合并并编译的配置重新建立内核。

## 组件 options 与占位符

占位符链路如下：

1. `KuudraYamlLoader` 读取 Flow YAML，将节点 `options` 保存为未解析的 Map 模板；此时 Event 和 Session 尚不存在，因此不会做字符串替换。
2. `KuudraApp` 编译 Flow 时，把 Adapter、Processor 和 Actor 的 options 模板保存在对应 `FlowNode` 中。
3. Runtime 注册 Flow 时调用 `PlaceholderResolver.compileMap`。这一阶段只执行一次正则扫描、字符串静态片段切分、表达式路径切分以及 Map/List 模板递归编译，并把每个节点的 `CompiledMap` 保存在 `RegisteredFlow` 中。语法结构不会在 Event 热路径中重复解释。
4. 每个 Event 到达节点时，`KuudraRuntime.execute` 构造当前 `EventContext`，其中包含 Event、可选 Session、当前 Flow 和 Global 四级作用域的本次执行快照及可写上下文。
5. Runtime 调用已编译模板的 `resolve`，此时只按预切分路径查询作用域值，并组装新的不可变 Map/List；原模板和已编译结构都不会被修改，可被不同 Event 和工作线程安全复用。
6. Adapter 和 Processor 从 `EventContext.configuration()` 获取解析结果；Actor 从 `ActionContext.configuration()` 获取同一份解析结果。

例如：

```yaml
global-context:
  profile: production

# <home-directory>/flows/example.yaml 中某个 actor 节点
options:
  key: ${input.key}                # 自动按 Event -> Session -> Flow -> Global 查找
  mode: ${session#mode}            # 只查 Session
  profile: ${global#profile}       # 只查 Global
  label: ${flow.id}:${event.type}
```

支持的表达式为：

| 查询模式 | 表达式 | 行为 |
| --- | --- | --- |
| 自动查询 | `${<path>}`，如 `${input.key}` 或唯一的 `${key}` | 依次查询 Event、Session、Flow、Global，第一个命中立即返回 |
| Event | `${event#<path>}`，如 `${event#input.key}` | 只查询当前 `EventData`；`id`、`type`、`occurred-at` 是 Event 元数据 |
| Session | `${session#<path>}` | 只查询同一 Session 共享值 |
| Flow | `${flow#<path>}` | 只查询同一 Flow、跨 Session 共享值；`${flow#id}` 返回 Flow ID |
| Global | `${global#<path>}` | 只查询同一 Runtime、跨 Flow 共享值 |

旧语法 `${event.data.input.key}`、`${session.values.mode}`、`${global.profile}`、`${flow.id}` 继续兼容。新配置应优先采用 `#` 明确作用域。配置只能读取作用域，不能声明写操作；插件组件在明确的业务时机通过 `sessionContext()`、`flowContext()`、`globalContext()` 的 `put/remove/update` 写入。Event 始终不可变，组件通过 `EventData.with` 或 `Event.withData` 生成派生值。

EventData 保留 namespace 隔离。`${input.key}` 明确读取 `input` namespace；`${key}`/`${event#key}` 可以省略 namespace，但仅在该 key 只存在于一个 namespace 时成立，多个 namespace 同名会报歧义错误并要求显式写出 namespace。

若整个字符串只有一个占位符，解析器保留原值类型，例如数字、布尔值、Map 或 List；若占位符嵌在较长字符串中，则通过 `String.valueOf` 转为文本。普通非字符串标量保持不变。表达式不存在会使当前节点执行失败，不会静默生成空值；无 Session 的节点显式引用 Session 会抛出明确错误。

节点 options 同时支持以下字面量：

```yaml
options:
  count: 2                              # YAML 数值，保持 Integer
  enabled: true                         # YAML 布尔，保持 Boolean
  native-list: [1, 2, 3]                # YAML 原生 List
  native-map: {key: A, pressed: true}   # YAML 原生 Map
  json-list: '[1, true, {"key":"A"}]' # JSON 文本，解析成不可变 List
  json-map: '{"key":"A","count":2}' # JSON 文本，解析成不可变 Map
  dynamic-json: '{"key":"${event#input.key}"}'
  numeric-text: '42'                    # 仍是 String，不隐式转为数值
```

只有去除首尾空白后以 `{...}` 或 `[...]` 包围的字符串才作为结构化 JSON 字面量处理；JSON 语法错误会在模板编译或节点解析时明确失败。带占位符的 JSON 会先完成插值再解析。JSON 数字/布尔标量字符串不会自动转换，避免普通文本发生意外改型。

### 上下文值与类型转换

Event、Session、Flow、Global 的业务值使用统一 `ContextCodec`。默认 `JsonContextCodec` 在写入边界把 POJO 编码一次，存储为不持有插件对象引用的不可变 JSON 兼容树；普通读取和占位符遍历不做反复 JSON 字符串序列化。组件需要强类型时调用 `context.sessionContext().get("key", Type.class)`、`flowContext().get(...)`、`globalContext().get(...)` 或 `event.data().get(namespace, key, Type.class)`；占位符已经注入节点 options 后，也可调用 `context.configuration("key", Type.class)`，Action 参数可调用 `call.argument("key", Type.class)`。只有这些强类型读取才执行反序列化。`ContextCodecs` 保留替换默认 codec 的扩展点。

这条链路对 `event-adapter`、`event-processor` 和 `actor` 已闭环，并有 API 解析测试与 Runtime 组件注入测试覆盖。`session-allocator` 的策略在 App 启动编译 Flow 时就必须确定，不能使用事件期占位符；`event-source` 没有输入 Event 上下文，当前契约也不接收节点 options，因此同样不支持动态占位符。

### 执行成本

优化前，每次节点执行都要对完整 options 树重新遍历并对每个字符串执行正则匹配和 `split(".")`；成本随 `事件数 × 节点执行数 × 模板大小` 增长。优化后，正则与表达式语法解析成本只在 Flow 注册时支付一次。不含占位符的 JSON 文本也只在该阶段解析一次；含动态占位符的 JSON 必须在每次插值后解析。Event 热路径仍必须完成动态作用域查找、结果 Map/List 分配和插值字符串拼接，因为这些值会随 Event 与 Session 改变；其成本与本次真正需要解析的模板节点和表达式路径深度线性相关。

`PlaceholderResolver.resolve/resolveMap` 公共便捷方法为保持独立调用语义，单次调用仍会即时编译再解析；Runtime 的高频路径固定使用可复用的 `CompiledMap`，不会走该便捷路径。
