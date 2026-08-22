# Kuudra 插件、日志与 Web Demo

## 定时 HelloWorld 插件

`kuudra-hello-plugin` 是一个独立 JAR。它通过 `META-INF/services` 声明 `KuudraPlugin`，由 `PluginArchiveLoader` 的独立 `URLClassLoader` 和 `ServiceLoader` 发现。启动时，插件经受限 `PluginRuntimeServices` 注册一个 `RawSignalSource`；该信号源每 100ms 发送一次：

```yaml
type: demo.hello-world
payload:
  message: HelloWorld
```

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

Web 模块启动时会从 `banner.txt` 打印 Kuudra ASCII Banner。
