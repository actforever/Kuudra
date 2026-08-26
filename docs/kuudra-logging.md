# Kuudra 内核日志

> Locale 闭环：内核英文目录为 `classpath:/i18n/en_US.json`；`config.yaml` 的 `i18n.preferred-locale`（格式 `xx_XX`）选择 `<home-directory>/locale/<locale>.json`，缺失文件或键时回退 `en_US`。插件可内嵌 `META-INF/kuudra-plugin/i18n/xx_XX.json`，并通过 `PluginLogger.message(...)` 使用身份隔离消息键。

## 解耦边界

`kuudra-logging` 使用名为 `kuudra-core` 的独立 Logback `LoggerContext`，不会继承 Spring Boot 的 root logger、appender 或日志级别。终端行以粗体彩色 `[KUUDRA]` 标识，级别按 INFO/WARN/ERROR 着色；文件日志始终使用无 ANSI 控制符的纯文本格式。

Runtime、插件管理和 App 生命周期不直接依赖具体 Logger。`kuudra-app` 持有唯一可订阅的 `SystemEventBus`，并只把 API 层的只写 `SystemEventPublisher` 端口注入 Runtime 和插件管理器；底层模块不能订阅总线，也不持有自己的总线。`KuudraLogSession` 作为 App 总线观察者统一决定日志级别与呈现方式：

- 类型包含 `failed` 的事件记为 ERROR；
- 类型包含 `rejected` 或 `cancel` 的事件记为 WARN；
- 其他生命周期、扫描、注册和资源事件记为 INFO。

系统事件不携带硬编码的自然语言日志正文：稳定的 `type`（例如 `web.shutdown.requested`）同时作为 I18n 消息键，`data` 中的 `trigger`、`action`、数量、耗时等结构化字段作为消息模板占位参数。日志适配器只打印 Resolver 渲染后的文本；增加语言时不需要修改事件生产者。

独立的 `kuudra-i18n` 默认加载 `classpath:/i18n/en_US.json`，其中 JSON key 是 SystemEvent 消息键，value 是带 `{placeholder}` 的英文模板；通配键 `*` 保证任何新事件仍经过模板渲染。该模块不依赖 App、日志框架或插件系统，提供 `MessageResolver`、`JsonMessageResolver` 与 `MessageResolvers`。App 对外暴露有效的 `MessageResolver`：

```java
app.setSystemEventMessageResolver((messageKey, arguments) ->
        myI18n.lookup(locale, messageKey, arguments));
```

外部 Resolver 优先，返回 `Optional.empty()` 时回退到内置英文目录。`KuudraApp.readSystemEventMessages(InputStream)` 可以直接读取相同格式的外部 JSON 目录。Resolver 是动态委托，App 启动后替换也会作用于当前日志会话。`plugin.log` 是插件作者主动提交的自由文本，不参与内核消息键翻译。

插件 I18n 将采用身份隔离的目录，而不是把全局 Resolver 直接交给插件修改：插件键统一限定为 `plugin.<namespace>.<pluginId>.<key>`，`PluginContext` 未来暴露的门面只能注册和解析本插件目录；键式 `PluginLogger` 再把 key 与 arguments 投影为 SystemEvent。这样插件可以提供多语言文本，但不能覆盖 `app.*`、`runtime.*` 或其他插件的消息。当前版本先稳定通用 I18n 模块和 App/日志链路，插件目录发现与 locale 选择仍为后续能力。

当前覆盖 App 启停与失败、Runtime 启停、Flow 与 Session 生命周期、队列/路由错误、插件扫描与归档加载、插件注册/初始化/启动/停止/失败、组件初始化/销毁，以及 EventSource 资源启停。Web 收到 Spring Context 关闭事件（包括终端 Ctrl-C）时会先发布 `web.shutdown.requested`；随后 App 与 Runtime 仍会逐段发布 EventSource 停止、Session 取消与排空、组件停止、插件停止、ClassLoader 关闭和日志归档事件，但正常阶段明细使用 DEBUG。默认 INFO 只保留 App 停止起止边界、排空超时和失败事件。App API、当前 Web SSE 和未来 WebSocket 等其他观察者可同时订阅同一总线，日志不会反向进入业务 Event 管线。

正常退出最显著的固定等待是 `runtime.shutdown-session-drain-timeout-ms`：Runtime 先取消全部活跃 Session，再等待工作租约释放，默认最多 5000ms。开启 DEBUG 后，`runtime.shutdown.sessions.draining` 会显示超时配置和初始会话数，`runtime.shutdown.sessions.drain.completed` 会显示实际耗时、剩余会话数及是否超时；若排空超时，完成事件仍按 AUTO 级别输出，确保默认日志可见。EventSource、组件和插件各自返回的异步 `stop/destroy` 目前没有内核统一超时，排查阻塞时应临时开启 DEBUG，通过最后一条 `*.started` 事件定位边界。

Web SSE 客户端关闭页面、网络切换或主动断开时，发送端只原子取消该订阅并静默结束，不调用 `completeWithError` 将连接关闭重新包装成 MVC 异常。此类断连不是内核失败，也不应产生 `AsyncRequestNotUsableException` WARN；真正的 App/SystemEvent 生产错误仍按原级别记录。

不把统一 Logger 接口放进 `kuudra-logging` 再让内核模块依赖它，是为了维持依赖方向：结构化事件契约属于 `kuudra-api`，App 负责汇聚，`kuudra-logging` 只是可替换的输出适配器。插件侧的 `PluginLogger` 是便捷门面，它最终仍转换成带插件身份的 `plugin.log` 结构化事件。

插件业务日志同样沿用这条总线。`kuudra-plugin` 向插件暴露绑定 namespace/ID 的 `PluginLogger`，并发布 `plugin.log` 系统事件；`kuudra-logging` 按 TRACE/DEBUG/INFO/WARN/ERROR 级别呈现，并在日志行附加 `[plugin=<namespace>/<plugin-id>]`。插件不需要绑定 Logback、SLF4J 或 Spring Logger。

## 配置接口

`kuudra-logging` 对外提供不依赖 Spring 或 YAML 的 `KuudraLogConfiguration` 和 `KuudraLogLevel`。宿主通过 `KuudraLog.openSession(logsDirectory, events, configuration)` 创建日志会话；原有双参数方法继续使用默认配置。

`kuudra-app` 将根配置中的 `logging` 映射到该接口：

```yaml
logging:
  level: info
  console-enabled: true
  file-enabled: true
```

- `level` 是所有 Kuudra 内核输出的最低级别，支持 `trace`、`debug`、`info`、`warn`、`error`、`off`，大小写不敏感；
- `console-enabled` 单独控制粗体彩色终端输出；
- `file-enabled` 单独控制 `latest.log` 和停止时的 gzip 归档。

`SystemEvent` 带有独立的 `SystemEventLevel` 呈现提示。历史事件使用 `AUTO`，继续按 `failed/rejected/cancel` 名称兼容映射；新增诊断事件显式使用 `DEBUG`，不会在默认 INFO 配置中形成噪声。DEBUG 当前覆盖：

- App 配置应用起止、清单校验、StateStore desired set 替换；
- 单资源调谐起止、组件物化和 Flow 编译；
- 插件归档扫描、依赖解析、组件创建和初始化边界，以及 `initialized`、`starting` 启动阶段；插件进入 `active` 后按 INFO 输出其 namespace/ID，明确本次实际载入的插件；
- App/Runtime 正常停止子阶段、Session 正常排空、插件与插件组件停止/销毁；
- Runtime 事件入队、队列拒绝、分派、节点执行起止及缺失 Session 丢弃。

这些事件只携带标识、状态、数量、执行域和结果等诊断元数据，不记录完整 Event 数据或上下文，避免日志泄漏业务载荷。任务级 DEBUG 事件频率可能较高，只应在排障期间启用。

关闭文件输出时不会创建、删除或归档 `latest.log`；已有日志文件保持不变。日志目录仍固定为 `<home-directory>/logs`，不会通过配置改变。

## 文件策略

启用文件输出时，日志目录固定为 `<home-directory>/logs/`，App 初始化家目录时无论文件输出是否启用都必须检查并创建它。每次启动内核：

1. 删除上一份 `logs/latest.log`，再创建一份属于本次运行的新 `latest.log`；
2. 内核运行期间同步追加纯文本事件日志；
3. 正常停止时先取消总线订阅并停止文件 appender，保证缓冲已刷新；
4. 将 `latest.log` 的内容 gzip 为 `yyyy-MM-dd-N.log.gz`，其中 `N` 从 1 开始选择当天第一个未占用序号；
5. 保留已停止内核的 `latest.log`，便于无需解压直接排查最后一次运行；下一次启动日志会话时才删除旧文件并创建新文件。

若压缩失败，停止过程会明确失败并保留 `latest.log`。进程被强制终止时无法执行关闭归档，遗留的 `latest.log` 仍会在下一次启动按约定删除；需要保留崩溃日志时，应在重启前先复制该文件。

## 示例

```text
.kuudra/logs/
  latest.log                 # 当前运行或最近一次已停止运行的日志
  2026-08-22-1.log.gz
  2026-08-22-2.log.gz
```
