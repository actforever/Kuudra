# Kuudra 插件运行目录与选择

可执行 Web JAR 启动后，默认以 JAR 所在目录为运行基准，并创建：

```text
<jar-directory>/.kuudra/plugins/
  kuudra-hello-world-plugin.jar
  hello-world/
```

`.jar` 文件是待加载的插件归档；`hello-world/` 是插件运行时家目录。插件家目录由其 `metadata.toml` 的 `namespace` 决定，并只会在该插件真正激活时创建。插件可通过 `PluginContext.home()` 使用该目录持久化自己的数据。

主配置通过 `plugins.load` 显式选择要激活的插件，格式为 `namespace/plugin-id`：

```yaml
plugins:
  directories:
    - .kuudra/plugins
  homeDirectory: .kuudra/plugins
  load:
    - hello-world/hello-world
```

没有列入 `load` 的 JAR 不会注册或激活；被选择插件声明的依赖会自动随之加载。插件 id 可与 namespace 相同，HelloWorld 插件即为 `hello-world/hello-world`。

Flow 组件仍用自身节点的 `type` 指定类别，组件引用只写 `namespace/component-id`：

```yaml
components:
  input:
    type: event-source
    component: hello-world/loop-emitter
```

内核据此组成内部完整引用 `event-source/hello-world/loop-emitter`，从而避免在 YAML 中重复类别信息。
