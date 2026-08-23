# Kuudra

Kuudra 是一个以事件、Flow、Session 和插件为核心的 Java 自动化编排内核，也是原 Orcana / GTAV Ops 项目的后继者。项目正在从面向单一应用的宏工具演进为可嵌入、可扩展且与宿主框架解耦的通用运行时。

> 当前版本为 `v0.4.0-alpha-1`。内核的最小端到端链路已经可用，但配置兼容性、插件 API 和管理接口仍可能在后续 Alpha 版本中调整。

## 当前能力

- 使用 `EventSource`、`EventAdapter`、`EventProcessor`、`SessionAllocator` 和 `Actor` 组成声明式 Flow。
- 以单一事件模型完成采集、转换、聚合、会话分配和异步动作执行。
- 同一 Session 内保持 Actor 执行顺序，不同 Session 可并行运行，并支持协作式取消和父子会话谱系。
- 从 `.kuudra/plugins/` 自动发现全部插件 JAR，校验元数据并按依赖拓扑完成类加载和生命周期管理。
- 允许依赖方插件直接引用依赖插件公开的类、资源和公共 POJO。
- 提供 Event、Session、Flow、Global 四级上下文，以及预编译占位符和类型化 JSON 数据转换。
- 通过无框架依赖的 `KuudraApp` 管理内核，并由 `kuudra-web` 提供 REST、SSE 和 OpenAPI 适配。
- 使用独立于 Spring Boot 的内核日志，输出终端彩色日志和按次运行归档的文件日志。

## 架构概览

```text
外部输入
   │
   ▼
EventSource → EventAdapter* → EventProcessor* → SessionAllocator → Actor*
                                                        │            │
                                                        └── Session ─┘

kuudra-web ──→ KuudraApp ──→ KuudraRuntime
                    ├── PluginManager
                    ├── Flow
                    └── SystemEventBus / Logging
```

Runtime 是单个 App 内唯一的执行内核，Flow 是路由、组件命名和 Session 归属的逻辑边界。只有 `SessionAllocator` 可以创建 Session；Actor 发出的事件默认继承当前 Session，回流到 Processor 或 Allocator 时则由 Runtime 剥离 Session 并保留谱系。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `kuudra-api` | 公共契约：Event、上下文、组件 SPI、Session 和 App 快照。 |
| `kuudra-config` | 格式无关的配置模型与 YAML 加载器。 |
| `kuudra-plugin` | 插件元数据、依赖感知 ClassLoader、组件扫描和生命周期管理。 |
| `kuudra-runtime` | Flow 图、任务队列、Session 生命周期和 Actor 调度。 |
| `kuudra-app` | 聚合配置、插件与 Runtime 的框架无关应用外观。 |
| `kuudra-web` | 面向 App 的 Spring Boot REST、SSE 与 OpenAPI 适配器。 |
| `kuudra-logging` | 独立内核日志上下文、系统事件观察和运行日志归档。 |

仓库根目录只构建核心模块。插件实现应放在独立项目或本地被忽略的 `plugins/` 聚合目录中，不属于核心 Maven reactor。

## 快速开始

环境要求：

- JDK 17 或更高版本
- Maven 3.8.5 或更高版本，或直接使用仓库内的 Maven Wrapper

构建可执行 Web 包：

```powershell
.\mvnw.cmd clean package
java -jar kuudra-web\target\kuudra-web-v0.4.0-alpha-1.jar
```

首次启动会在可执行 JAR 所在目录创建以下结构：

```text
.kuudra/
  config.yaml
  plugins/
  flows/
  logs/
    latest.log
```

缺少 `config.yaml` 时，Kuudra 会复制内置默认配置；已有文件不会被覆盖。插件 JAR 放入 `plugins/`，Flow YAML 放入 `flows/`。目录中的非法插件、损坏 JAR、缺失依赖或依赖环都会令启动明确失败，而不是被静默忽略。

默认配置如下：

```yaml
home-directory: .kuudra
runtime:
  queue-capacity: 1024
  worker-threads: 2
logging:
  level: info
  console-enabled: true
  file-enabled: true
global-context: {}
```

启动后可访问：

- App 状态：`GET http://localhost:8080/api/v1/app/status`
- OpenAPI 文档：`http://localhost:8080/doc.html`
- 系统事件流：`GET http://localhost:8080/api/v1/app/events`

完整的目录规则、配置优先级、Flow YAML 和占位符语法见 [配置与启动文档](docs/kuudra-bootstrap.md)。

## 插件与数据传递

插件归档必须包含 `META-INF/kuudra-plugin/metadata.toml`，组件使用注解注册，并通过 `type/namespace/name` 唯一引用。插件依赖决定启动顺序和单向类可见性：依赖方可以使用提供方公开的类，停止顺序与启动顺序相反。

运行时提供四级数据作用域：

| 作用域 | 共享范围 | 读取示例 |
| --- | --- | --- |
| Event | 当前不可变事件 | `${event#input.key}` |
| Session | 同一会话中的组件 | `${session#mode}` |
| Flow | 同一 Flow、跨 Session | `${flow#state}` |
| Global | 同一 Runtime、跨 Flow | `${global#profile}` |

`${key}` 会按 Event → Session → Flow → Global 自动查找。Flow 注册时会预编译占位符结构，事件热路径只执行作用域取值与结果组装。POJO 在写入上下文时默认转换为不可变 JSON 兼容树，消费方可通过 `get("key", TargetType.class)` 恢复强类型，避免上下文长期持有插件 ClassLoader 创建的对象。

插件布局、依赖和公共 POJO 的完整约束见 [插件运行目录与加载](docs/kuudra-plugin-layout.md)，事件与 Session 语义见 [事件与会话架构](docs/kuudra-event-architecture.md)。

## 构建与验证

项目默认跳过测试。需要执行测试时显式覆盖该属性：

```powershell
.\mvnw.cmd test -DskipTests=false
```

仅构建产物：

```powershell
.\mvnw.cmd clean package
```

## 未来方向

Kuudra 的长期目标是让自动化能力以插件和声明式 Flow 组合，同时保持内核、管理面和具体输入／执行技术彼此独立。当前路线包括：

- 内置 `kuudra.system.*` 控制事件，以及更完整的资源化管理能力。
- Flow、配置和插件的安全重载、校验、版本迁移与故障回滚。
- 可插拔的持久化 StateStore，承载需要跨重启保存的状态。
- 插件组件元数据与自动生成文档，让配置者能够发现参数、输入输出和上下文约定。
- 面向 App API 的 `kuudractl`、TUI，以及 App 与管理端分进程部署所需的远程适配。
- JSON、TOML 等配置入口和跨语言组件桥接；这些能力不会改变核心 Event、Flow 与 Session 不变量。
- 在通用内核之上逐步恢复并重构键鼠输入、宏动作等 Orcana 领域插件，而不把具体平台能力重新耦合进核心模块。

## 文档导航

- [总体架构](docs/kuudra-architecture.md)
- [配置与启动](docs/kuudra-bootstrap.md)
- [事件与会话架构](docs/kuudra-event-architecture.md)
- [插件运行目录与加载](docs/kuudra-plugin-layout.md)
- [App 管理 API](docs/kuudra-app-management.md)
- [资源控制模型](docs/kuudra-resource-management.md)
- [资源清单与调谐模型](docs/kuudra-resource-manifests.md)
- [Web 配置](docs/kuudra-web-configuration.md)
- [内核日志](docs/kuudra-logging.md)

## License

本项目基于仓库中的 [LICENSE](LICENSE) 发布。
