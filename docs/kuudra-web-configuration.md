# Kuudra Web 配置

`kuudra-web` 只使用 Spring 的 Web 配置（例如 `server.port`、SpringDoc 与 Knife4j），不再从 Spring `Environment` 或 `application.yaml` 读取任何 Kuudra App 配置。

HTTP 适配层按领域组织在 `io.github.actforever.kuudra.web.controller` 包中。每个 Controller 只管理一个 `/api/v1/{resource-domain}`：App 生命周期、Flow、内核资源文档、Component 实例、Component 模板、Session、Plugin 和 SystemEvent 各自独立。所有 Controller 只依赖 `KuudraApp`，不得直接暴露或访问 Runtime。插件注册的 `Component` 是可实例化模板，清单声明并由 App 调谐的 `ComponentResource` 是 Component 实例，两者使用不同 API 资源域。

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

`doc.html` 提供聚合的 `all` 分组，并按 Controller 资源域提供独立 OpenAPI 分组；每组内部使用类级中文 Tag 和操作摘要：

| 分组 | 内容 |
| --- | --- |
| `app` | App 快照、详细状态、启动、停止、暂停、恢复和重启。 |
| `flows` | Flow 查询。 |
| `resource-documentation` | 内核资源规约文档。 |
| `components` | 清单声明的 Component 实例查询与期望状态控制。 |
| `component-templates` | 插件提供的 Component 模板与结构化文档。 |
| `sessions` | Session 查询与协作式取消。 |
| `system-events` | SystemEvent SSE 订阅。 |
| `plugins` | 已加载 Plugin 查询。 |

各组规范位于 `/v3/api-docs/{group}`，Swagger 配置入口为 `/v3/api-docs/swagger-config`。分组只改变文档呈现，不改变任何 REST 路径或 App 边界。
