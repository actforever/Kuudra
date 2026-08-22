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
plugins:
  directories:
    - .kuudra/plugins
  home-directory: .kuudra/plugins
  load: []
flows-directory: flows
global-context: {}
```

`plugins.directories` 和 `plugins.home-directory` 均相对 App 配置基目录解析；前者存放待扫描的 JAR，后者仅在对应插件真正初始化时创建其 `<plugin-id>/` 家目录。`flows-directory` 同样相对 App 配置基目录解析。

Flow YAML 使用 `components` 和 `routes`。节点 `type` 支持 `event-source`、`event-adapter`、`event-processor`、`session-allocator` 和 `actor`；`component` 使用 `namespace/component-id`。Session Allocator 选项使用 `admission-key` 与 `parent-termination-policy` 等 kebab-case 键。

启动顺序如下：

1. App 合并三层配置，`KuudraYamlLoader` 编译全局配置与 Flow YAML；
2. App 扫描插件目录中的 `*.jar`，读取 `META-INF/kuudra-plugin/metadata.toml`；
3. `DefaultPluginManager` 按依赖关系启动选中的插件并注册组件；
4. App 编译并注册 Flow，再启动其中启用的 EventSource 资源。

任一步失败都会使 App 进入 `FAILED` 并释放已创建的 Runtime、插件与 ClassLoader。`POST /api/v1/app/restart` 使用 App 创建时已经合并并编译的配置重新建立内核。

`global-context` 由 App 保存为只读上下文。节点 `options` 中的占位符在 Runtime 处理 Event 时解析，并通过 `EventContext.configuration()` 或 `ActionContext.configuration()` 注入组件；YAML 加载器只保存模板。

支持 `${event.id}`、`${event.type}`、`${event.occurredAt}`、`${event.data.<namespace>.<key>}`、`${session.id}`、`${session.flowId}`、`${session.values.<key>}`、`${global.<key>}` 和 `${flow.id}`。完整占位符保留原始类型，嵌入较长字符串时转为文本。
