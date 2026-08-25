# Kuudra 内核日志

## 解耦边界

`kuudra-logging` 使用名为 `kuudra-core` 的独立 Logback `LoggerContext`，不会继承 Spring Boot 的 root logger、appender 或日志级别。终端行以粗体彩色 `[KUUDRA]` 标识，级别按 INFO/WARN/ERROR 着色；文件日志始终使用无 ANSI 控制符的纯文本格式。

Runtime、插件管理和 App 生命周期不直接依赖具体 Logger。`kuudra-app` 持有唯一可订阅的 `SystemEventBus`，并只把 API 层的只写 `SystemEventPublisher` 端口注入 Runtime 和插件管理器；底层模块不能订阅总线，也不持有自己的总线。`KuudraLogSession` 作为 App 总线观察者统一决定日志级别与呈现方式：

- 类型包含 `failed` 的事件记为 ERROR；
- 类型包含 `rejected` 或 `cancel` 的事件记为 WARN；
- 其他生命周期、扫描、注册和资源事件记为 INFO。

当前覆盖 App 启停与失败、Runtime 启停、Flow 与 Session 生命周期、队列/路由错误、插件扫描与归档加载、插件注册/初始化/启动/停止/失败、组件初始化/销毁，以及 EventSource 资源启停。App API、当前 Web SSE 和未来 WebSocket 等其他观察者可同时订阅同一总线，日志不会反向进入业务 Event 管线。

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
- 插件依赖解析、组件创建和初始化边界；
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
