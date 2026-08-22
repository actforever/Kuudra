# Kuudra 外部配置启动

当前最小可用启动链路是：`kuudra-web` 启动后创建 `KuudraApp`；App 读取 `kuudra.yaml`，加载插件目录中的 JAR，解析插件元数据与依赖，启动插件，再将 `flows/*.yaml` 编译为 `KuudraFlow`，最后注册并启动每个事件源。

`kuudra-web` 可通过环境变量 `KUUDRA_CONFIG_PATH` 指定全局配置的绝对路径；未指定时 App 以空运行时启动，仍可通过 HTTP 生命周期接口管理它。

```text
KUUDRA_CONFIG_PATH=/absolute/path/kuudra.yaml
```

全局配置示例见 [examples/kuudra.yaml](../examples/kuudra.yaml)，Flow 示例见 [examples/flows/hello-world.yaml](../examples/flows/hello-world.yaml)。其中的 `plugins` 路径相对 `kuudra.yaml` 所在目录解析，Flow 目录同理。

启动顺序如下：

1. `KuudraYamlLoader` 读取全局配置和所有 Flow YAML。
2. App 扫描每个插件目录的 `*.jar`，读取 `META-INF/kuudra-plugin/metadata.toml`，通过隔离 ClassLoader 加载插件。
3. `DefaultPluginManager` 按 `metadata.toml` 的依赖关系拓扑排序，依次初始化、启动插件，并注册带命名空间的组件，例如 `event-source/hello-world/loop-emitter`。
4. App 将节点和边编译为 `KuudraFlow`，注册 Flow，再根据 `sources` 绑定启动事件源。

任一步失败都会使 App 进入 `FAILED`，并释放已创建的 Runtime、插件与 ClassLoader；不会回退启动旧配置。`POST /api/v1/app/restart` 会使用同一份已读取的配置重新创建内核。

`globalContext` 已由 App 保存为只读上下文；占位符解析和向插件动作注入仍是后续工作，当前最小内核不把它隐式注入组件。
