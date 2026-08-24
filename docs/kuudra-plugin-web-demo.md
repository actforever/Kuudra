# 插件与 Web 演示

同一批扫描到的插件 JAR 会先读取全部 `metadata.toml`，再按 `dependencies` 建立依赖 ClassLoader 图。依赖插件的类、单个资源与资源枚举结果对下层插件可见，因此前置插件可提供共享 Java 类型或 ServiceLoader 资源，依赖方不必重复打包它们。该行为由真实父/子插件归档集成测试覆盖。依赖必须形成无环图；卸载或启动失败回滚时依赖方先清理，前置插件最后停止。详细委派顺序、隔离边界和失败清理见 [插件运行目录与加载](kuudra-plugin-layout.md)。

插件 JAR 在 `META-INF/kuudra-plugin/metadata.toml` 中声明：

```toml
id = "hello-world"
namespace = "hello-world"
version = "0.1.0"
entrypoint = "io.github.actforever.kuudra.demo.hello.HelloWorldPlugin"
dependencies = []
```

`namespace` 是稳定的插件命名空间。注解扫描后的组件引用固定为：

```text
event-source/hello-world/loop-emitter
event-adapter/<namespace>/<name>
event-interpreter/<namespace>/<name>
event-handler/<namespace>/<name>
```

插件可声明 `@EventSource`、`@EventInterpreter`、`@EventAdapter`、`@Ingress`、`@EventHandler`、`@Egress` 与 `@Action`。SessionManager 和 SessionCoordinator 由 Runtime 提供，插件不能注册或替换。`EventData` 是不可变的命名空间容器，插件应以自身 namespace 读写属性。

插件在 `initialize(PluginContext)` 前由内核创建专属家目录，路径通过 `PluginContext.home()` 获得。该目录固定为 `<home-directory>/plugins/<plugin-namespace>/<plugin-id>/`，只有对应插件真正被加载并初始化时才创建。插件可通过 `PluginContext.resources()` 注册需要在卸载时关闭的资源。

由配置创建的 Source、Interpreter、Adapter、Ingress、Handler 或 Egress 若需要实例级资源管理，可实现 `PluginComponentLifecycle`：`initialize(PluginComponentContext)` 在所属插件进入 `ACTIVE` 后、接入 Flow 前调用；`destroy()` 在 Runtime 已停止 Source 投递后、插件 `stop()` 前按组件创建的逆序调用。

组件可以用 `@ComponentDoc` 提供用途、使用示例和生命周期阶段，用 `@EventEmission` 列出可能产生的业务事件。内核扫描后提供结构化查询：`GET /api/v1/app/plugins` 查询已加载插件，`GET /api/v1/app/plugins/{pluginId}/components` 查询插件组件，`GET /api/v1/app/components/{type}/{namespace}/{name}` 查询组件详情。Knife4j 默认打开包含全部端点的 `all` 分组，也可切换到独立的 `plugins` 分组。

插件初始化及组件初始化上下文均提供身份绑定的 `PluginLogger`。日志会携带插件 namespace/ID 并通过系统事件总线进入 Kuudra 日志会话；插件无需依赖具体日志框架。

`KuudraApp` 是应用外观。`kuudra-web` 是唯一的 REST/SSE 适配层，直接将 App 管理 API 暴露给 HTTP 客户端；它不直接暴露 Runtime。运行 Web 时输出 `:: Kuudra Web Adapter ::`，而 Kuudra Banner 只在 App 创建时输出。
