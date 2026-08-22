# Kuudra 插件运行目录与加载

可执行 Web JAR 启动后，默认以 JAR 所在目录为运行基准，并使用固定布局：

```text
<jar-directory>/.kuudra/plugins/
  kuudra-hello-world-plugin.jar
  hello-world/
```

`.jar` 文件是待加载的插件归档；`hello-world/` 是插件 ID 对应的运行时家目录。该目录只在插件真正进入初始化时创建，插件可通过 `PluginContext.home()` 或 `PluginComponentContext.plugin().home()` 使用它持久化数据。

插件目录不再可配置，也没有显式加载清单。Kuudra 会按文件名顺序读取 `<home-directory>/plugins/` 下所有 `.jar` 文件，并把它们作为一个依赖图加载。目录中的每个 JAR 都必须是合法 Kuudra 插件：

- 必须是可读取的 JAR；
- 必须包含合法的 `META-INF/kuudra-plugin/metadata.toml`；
- entrypoint 必须存在、可实例化并实现 `KuudraPlugin`；
- 实例 ID 必须与元数据 ID 一致；
- 插件 ID 不能重复；
- 声明的依赖必须也存在于该目录，且依赖图不能成环。

任一归档不满足要求都会中止整个 App 启动；Kuudra 不会忽略未知或损坏的 JAR，也不会只加载部分插件。

Flow 组件用节点 `type` 指定类别，组件引用只写 `namespace/component-id`：

```yaml
components:
  input:
    type: event-source
    component: hello-world/loop-emitter
```

内核据此组成内部完整引用 `event-source/hello-world/loop-emitter`，避免在 YAML 中重复类别信息。
