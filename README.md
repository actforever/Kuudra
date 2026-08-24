# Kuudra

Kuudra 是一个事件驱动、插件化的 Java 自动化编排内核。当前版本围绕显式的 RAW/SESSION 双域事件流、声明式 Flow、可复用组件资源和可观测会话生命周期构建。

## 核心模型

```text
EventSource -> RawEventInterpreter / EventAdapter -> Ingress
                                                     |
                                      SessionManager + SessionCoordinator
                                                     |
                                    EventHandler / EventAdapter -> Egress
                                                                      |
                                                               RAW 域继续路由
```

- `KuudraEvent` 是唯一不可变业务消息，不携带可空 Session。
- `RawEventWrapper` 与 `SessionEventWrapper` 明确区分执行域，避免 Runtime 猜测上下文。
- `Ingress` 只判断准入和会话分组；Runtime 的 `SessionManager` 创建会话，`SessionCoordinator` 执行有界调度策略。
- `EventHandler` 异步处理会话事件并协作式检查取消；`Egress` 显式擦除 Session 绑定并保留因果谱系。
- `EventAdapter` 可部署在任一域，但不会改变域；`RawEventInterpreter` 面向需要窗口、计时器或状态机的 RAW 事件解释。
- Flow 边无端口、无隐式域转换。分类、过滤和重映射由 Adapter 完成。

架构图见 [docs/flow-arch.png](docs/flow-arch.png)，完整不变量见 [docs/kuudra-architecture.md](docs/kuudra-architecture.md)。

## 模块

| 模块 | 职责 |
| --- | --- |
| `kuudra-api` | 事件、Wrapper、组件、上下文、Session 与 App 公共契约 |
| `kuudra-config` | YAML 和 K8s 风格资源清单模型 |
| `kuudra-plugin` | 插件归档、依赖 ClassLoader、组件发现和生命周期 |
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
  manifests/
  logs/
  state/
```

所有插件 JAR 都必须是合法 Kuudra 插件，否则启动失败。Component 和 Flow 资源统一放在 `manifests/`，Flow 通过 `spec.imports` 引用 Component。

```powershell
mvn test -DskipTests=false
java -jar kuudra-web/target/kuudra-web-v0.4.0-alpha-2.jar
```

启动后可访问 `GET /api/v1/app/status`、`GET /api/v1/app/sessions/{id}`、`POST /api/v1/app/sessions/{id}/cancel` 和 `/doc.html`。

## 上下文与占位符

占位符支持 Event、Session、Flow、Global 四个逻辑作用域。RAW 区域只能读取 Event、Flow、Global；SESSION 区域还可读取 Session。`${path}` 按当前可用域从内向外查找，`${event#path}`、`${session#path}`、`${flow#path}`、`${global#path}` 严格指定作用域。

Flow 注册时预编译模板并校验作用域，事件热路径只执行查值和结果组装。上下文默认保存不可变 JSON 兼容树，组件通过 `get("key", Type.class)` 按需恢复类型。

更多信息见 [启动与配置](docs/kuudra-bootstrap.md)、[插件布局](docs/kuudra-plugin-layout.md)、[App 管理](docs/kuudra-app-management.md) 和 [日志](docs/kuudra-logging.md)。
