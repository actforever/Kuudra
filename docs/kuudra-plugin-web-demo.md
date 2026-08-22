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

`KuudraApp` 是应用外观。`kuudra-web` 是唯一的 REST/SSE 适配层，直接将 App 管理 API 暴露给 HTTP 客户端；它不直接暴露 Runtime。运行 Web 时输出 `:: Kuudra Web Adapter ::`，而 Kuudra Banner 只在 App 创建时输出。
