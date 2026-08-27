# Kuudra

Kuudra 是一个事件驱动、插件化的 Java 自动化编排内核。当前版本围绕显式的 RAW/SESSION 双域事件流、声明式 Flow、可复用组件资源和可观测会话生命周期构建。

## 核心模型

```text
EventSource -> EventInterpreter / EventAdapter -> Ingress
                                                  |
                                   SessionManager + SessionCoordinator
                                                  |
                                 EventHandler / EventAdapter -> Egress
                                                                   |
                                                            进入无会话域
```

- `KuudraEvent` 是唯一不可变业务消息，不携带可空 Session。
- `RawEventWrapper` 与 `SessionEventWrapper` 明确区分执行域，避免 Runtime 猜测上下文。
- `Ingress` 只判断准入和会话分组；Runtime 的 `SessionManager` 创建会话，`SessionCoordinator` 执行有界调度策略。
- `EventHandler` 异步处理会话事件并协作式检查取消；`Egress` 显式擦除 Session 绑定并保留因果谱系。
- `EventAdapter` 可部署在任一域，但不会改变域；`EventInterpreter` 面向需要窗口、计时器或状态机的进入会话前事件解释。
- 组件名称不携带 Raw/Session 前缀；RAW/SESSION 只是 Runtime Wrapper 表达的执行域。
- Flow 边无端口、无隐式域转换。分类、过滤和重映射由 Adapter 完成。

架构图见 [docs/flow-arch.png](docs/flow-arch.png)，完整不变量见 [docs/kuudra-architecture.md](docs/kuudra-architecture.md)。

## 模块

| 模块 | 职责 |
| --- | --- |
| `kuudra-api` | 事件、Wrapper、组件、上下文、Session 与 App 公共契约 |
| `kuudra-i18n` | I18n Resolver、JSON 消息目录、占位符插值和默认英文文本 |
| `kuudra-config` | YAML 和 K8s 风格资源清单模型 |
| `kuudra-plugin` | 插件元数据、依赖 ClassLoader、注解组件发现、清单配置和生命周期 |
| `kuudra-runtime` | 双域 Flow、任务队列、SessionManager 与 SessionCoordinator |
| `kuudra-app` | 配置、插件、清单和 Runtime 的框架无关外观 |
| `kuudra-web` | 面向 App 的 REST、SSE 和 OpenAPI 适配器 |
| `kuudra-logging` | 彩色终端日志、SystemEvent 投影和文件归档 |

## 运行目录与构建

首次启动会初始化：

```text
.kuudra/
  config.yaml
  plugins/
  locale/                  # xx_XX.json 用户语言目录
  manifests/
  logs/
  state/kuudra.db            # 资源期望/观测状态与 generation
```

核心不再内置或隐式注册组件；`kuudra-default-plugin` 已迁移为同级独立项目，构建后也必须作为普通 JAR 放入 `plugins/`。所有插件 JAR 都必须是合法 Kuudra 插件，否则启动失败。插件通过 `META-INF/kuudra-plugin/metadata.toml` 声明 ID、namespace、版本、入口和结构化依赖；依赖项包含 namespace、插件 ID、是否强制以及 Forge/Maven 风格版本范围。插件版本使用点分隔数字段，可带 `-prerelease`/`+build` 后缀但不带前导 `v`。依赖插件的类与资源对下游插件可见。插件运行目录固定为 `plugins/<namespace>/<plugin-id>/`。具体组件 kind 与 Flow 资源统一放在 `manifests/`，Flow 只能导入同 namespace 资源。

清单 kind 为 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`；内核对应的组件 type 分别采用 kebab-case。资源规范身份为 `kind/namespace/name`，Flow 通过同命名空间 import 将该身份绑定为局部路由别名。插件组件实现 `PluginComponentLifecycle` 后，可在初始化时通过统一的 `TypedValueMap` 读取并转换资源 `options`；EventSource 产生 `KuudraEvent`，Runtime 在入队时负责构造 `RawEventWrapper`。

插件组件可使用 `@ComponentDoc` 与 `@EventEmission` 声明结构化用途、示例、生命周期和输出事件说明，并通过 App/Web API 查询。插件通过上下文提供的 `PluginLogger` 写日志，日志自动携带插件身份并进入内核 SystemEvent 日志链路。Knife4j 默认展示聚合 `all` 分组，也保留按能力拆分的分组。

```powershell
mvn test -DskipTests=false
java -jar kuudra-web/target/kuudra-web-v0.4.3-SNAPSHOT.jar
```

启动后可访问 `GET /api/v1/kuudra/status`、`GET /api/v1/runtime/sessions/{id}`、`POST /api/v1/runtime/sessions/{id}/cancel` 和 `/doc.html`。

## 上下文与占位符

占位符支持 Event、Session、Flow、Global 四个逻辑作用域。RAW 区域只能读取 Event、Flow、Global；SESSION 区域还可读取 Session。`${path}` 按当前可用域从内向外查找，`${event#path}`、`${session#path}`、`${flow#path}`、`${global#path}` 严格指定作用域。

Flow 注册时预编译模板并校验作用域，事件热路径只执行查值和结果组装。上下文默认保存不可变 JSON 兼容树，组件通过 `get("key", Type.class)` 按需恢复类型。

## 当前边界与方向

当前版本聚焦可运行的最小内核：严格插件加载、双域路由、声明式资源、SQLite 状态调谐、可暂停的 App/Session、会话调度和 Web 管理接口。Flow 只是路由声明，不拥有状态机。后续演进方向包括资源版本迁移与热重载、持续调谐、`kuudractl` 以及多语言组件桥接。

更多信息见 [架构设计](docs/kuudra-architecture.md)、[启动与配置](docs/kuudra-bootstrap.md)、[插件布局](docs/kuudra-plugin-layout.md)、[App 管理](docs/kuudra-app-management.md) 和 [日志](docs/kuudra-logging.md)。
