# 插件与 Web 演示

插件 JAR 在 `META-INF/kuudra-plugin/metadata.toml` 中声明：

```toml
id = "hello-world-source"
namespace = "hello-world"
version = "0.1.0"
entrypoint = "io.github.actforever.kuudra.demo.hello.HelloWorldPlugin"
dependencies = []
```

`namespace` 是稳定的插件命名空间。注解扫描后的组件引用固定为：

```text
event-source/hello-world/loop-emitter
event-adapter/<namespace>/<name>
event-processor/<namespace>/<name>
actor/<namespace>/<name>
```

插件可声明 `@EventSource`、`@EventAdapter`、`@EventProcessor`、`@Actor` 与 `@Action`。其中前四类与 Event 图直接对应；`SessionAllocator` 由内核提供，不能由插件伪造。`EventData` 是不可变的命名空间容器，插件应以自身 namespace 读写属性。

插件在 `initialize(PluginContext)` 前由内核创建专属家目录，路径通过 `PluginContext.home()` 获得。该目录由 `plugins.homeDirectory/<plugin-id>/` 决定，只有对应插件真正被加载并初始化时才创建。插件可通过 `PluginContext.resources()` 注册需要在卸载时关闭的资源。

由配置创建的 Source、Adapter、Processor 或 Actor 若还需要实例级资源管理，可实现 `PluginComponentLifecycle`：`initialize(PluginComponentContext)` 在其所属插件进入 `ACTIVE` 后、接入 Flow 前调用；`destroy()` 在 Runtime 已停止 Source 投递后、插件 `stop()` 前按组件创建的逆序调用。因此像 JNativeHook 的全局监听器可在组件初始化时注册，并在组件销毁时可靠释放。

`KuudraApp` 是应用外观。`kuudra-web` 是唯一的 REST/SSE 适配层，直接将 App 管理 API 暴露给 HTTP 客户端；它不直接暴露 Runtime。运行 Web 时输出 `:: Kuudra Web Adapter ::`，而 Kuudra Banner 只在 App 创建时输出。
