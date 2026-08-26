# Kuudra Web 配置

`kuudra-web` 只使用 Spring 的 Web 配置（例如 `server.port`、SpringDoc 与 Knife4j），不再从 Spring `Environment` 或 `application.yaml` 读取任何 Kuudra App 配置。

HTTP 适配层按领域组织在 `io.github.actforever.kuudra.web.controller` 包中。Kuudra 内核生命周期、观测和内建资源文档统一使用 `/api/v1/kuudra`；Flow、Component 和 Session 归入 `/api/v1/runtime`；Plugin 与 ComponentTemplate 归入 `/api/v1/plugin`；SystemEvent 保持独立资源域。所有 Controller 只依赖 `KuudraApp`，`runtime` 路径仅表达执行资源归属，不得直接暴露或访问 Runtime 对象。插件注册的 `Component` 在 HTTP 语义中称为 ComponentTemplate，清单声明并由 App 调谐的资源称为 Component。

Web 创建 `KuudraApp` 时，将可执行 JAR 所在目录作为 App 的配置基目录；开发期从 classes 目录运行时使用当前工作目录。App 按以下优先级深度合并配置：

1. 初始化 `KuudraApp` 时直接传入的 `KuudraConfigResource` 或配置文件；
2. `<home-directory>/config.yaml`；
3. `kuudra-app` 包内的 `classpath:/config.yaml`。

内置配置声明 `home-directory: .kuudra`，因此打包部署时用户配置默认为 `<jar-directory>/.kuudra/config.yaml`，插件 JAR 固定放在 `<jar-directory>/.kuudra/plugins/`，具体组件 kind 与 Flow 资源固定放在 `<jar-directory>/.kuudra/manifests/`。

首次启动时，Web 会通过 App 自动创建 `.kuudra/`、`.kuudra/plugins/`、`.kuudra/manifests/`、`.kuudra/logs/` 和 `.kuudra/state/`，并在缺少 `.kuudra/config.yaml` 时复制包内默认配置。现有配置不会被覆盖；配置损坏时可删除该文件并重启以恢复默认值。

`application.yaml` 仍可放在可执行 JAR 同级并覆盖 Spring Web 设置，但其中的 `kuudra.*` 属性不会被读取。Kuudra 配置必须写入 `.kuudra/config.yaml`，例如：

```yaml
runtime:
  worker-threads: 4
global-context:
  profile: production
```

代码宿主可通过 `KuudraApp.createConfigured(KuudraConfigResource)` 传入最高优先级配置；该映射也必须使用小写 kebab-case 键。

## OpenAPI 分组

`doc.html` 提供聚合的 `all` 分组，并按 Controller 资源域提供独立 OpenAPI 分组；Knife4j 下拉框使用带顺序号的中文显示名，分组标识与 `/v3/api-docs/{group}` 地址保持稳定。Controller 的 Tag 使用统一的英文资源名，具体操作摘要使用中文：

| 分组 | 内容 |
| --- | --- |
| `all`（00 - 全部接口） | 所有 HTTP API，也是 `doc.html` 默认分组。 |
| `kuudra`（01 - Kuudra 内核） | 内核快照、详细状态、生命周期控制和资源规约文档。 |
| `runtime`（02 - Runtime 运行资源） | Flow、Component 和 Session 的查询与控制，统一显示为 `Runtime` Tag。 |
| `plugin`（03 - Plugin 扩展资源） | 已加载 Plugin、ComponentTemplate 与结构化文档查询，统一显示为 `Plugin` Tag。 |
| `system-events`（04 - 系统事件） | SystemEvent SSE 订阅。 |

各组规范位于 `/v3/api-docs/{group}`，Swagger 配置入口为 `/v3/api-docs/swagger-config`。分组只改变文档呈现，不改变任何 REST 路径或 App 边界。
