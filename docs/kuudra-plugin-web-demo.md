# Kuudra 插件、日志与 Web Demo

## 定时 HelloWorld 插件

`kuudra-hello-plugin` 是一个独立 JAR。它通过 `META-INF/kuudra-plugin/metadata.toml` 声明 `id`、`namespace`、`version`、`entrypoint` 与 `dependencies`，由 `PluginArchiveLoader` 的独立 `URLClassLoader` 加载。元数据依赖决定启动拓扑；缺失依赖与循环依赖都会拒绝激活。`namespace` 必须是稳定、全局唯一的短标识（例如 `hello-world`），用于隔离配置引用。

插件资源使用 `@SignalSource`、`@SignalProcessor`、`@SignalAdapter`、`@Actor`、`@Action` 等注解声明。加载器扫描归档并注册配置引用，格式为 `<资源类型>/<插件命名空间>/<资源名>`，例如 `signal-source/hello-world/loop-emitter`；`KuudraApp.installSignalSource` 是配置编译器把该引用装配到 Runtime ingress 的唯一入口。启动时，HelloWorld 插件经受限 `PluginRuntimeServices` 注册一个 `RawSignalSource`；该信号源每 100ms 发送一次：

```yaml
type: demo.hello-world
payload:
  hello-world-source:
    message: HelloWorld
```

所有 Signal payload 都由不可变 `SignalData` 承载。插件按自身 ID 写入命名空间（例如 `hello-world-source.message`），读取时必须指定命名空间和键，因此链路中的不同插件不能无意覆盖彼此的数据。

插件停止时注销 source 并关闭定时线程。运行端到端 Demo：

```powershell
mvn -pl kuudra-app,kuudra-hello-plugin -am package -DskipTests
java -cp "kuudra-app\target\classes;kuudra-runtime\target\classes;kuudra-plugin\target\classes;kuudra-api\target\classes;kuudra-config\target\classes;kuudra-logging\target\classes" io.github.actforever.kuudra.app.KuudraPluginDemo
```

Demo 会等待三个 `HelloWorld` 信号，然后停止插件，并验证停止后 source 不再投递信号。

## 日志隔离

`kuudra-logging` 使用一个命名为 `kuudra-core` 的私有 Logback `LoggerContext`；运行时不调用全局 `LoggerFactory.getLogger`。因此，当 Runtime 嵌入 Spring Boot 时，核心日志的 appender、等级和格式不会被 `kuudra-web` 的 `logback-spring.xml` 覆盖。

`kuudra-web` 使用 Spring Boot 自己的全局 Logback 上下文，并以 `[kuudra-web]` 作为日志格式标识。

## Web 模块

```powershell
mvn -pl kuudra-web -am package -DskipTests
java -jar kuudra-web\target\kuudra-web-0.1.0-SNAPSHOT.jar
```

- 健康检查：`GET /api/v1/runtime/health`
- Flow：`GET /api/v1/flows`、`GET /api/v1/flows/{flowId}`、以及 `activate`、`pause`、`resume`、`stop` 操作
- 会话：`GET /api/v1/sessions/{sessionId}`、`POST /api/v1/sessions/{sessionId}/cancel`
- Knife4j：`http://127.0.0.1:8080/doc.html`

`KuudraApp` 是由 API、Config、Plugin、Logging 和 Runtime 组合出的应用外观，并在创建时打印 Kuudra ASCII Banner；Web 模块自身的 Spring Boot Banner 只显示 `Kuudra Web Adapter`，明确其适配层身份。`kuudra-web` 只依赖 `kuudra-app`，将其外观 API 映射为 HTTP，不直接访问 Runtime。
