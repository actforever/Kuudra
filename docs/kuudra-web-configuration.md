# Kuudra Web 配置

`kuudra-web` 只使用 Spring 的 Web 配置（如 `server.port`、SpringDoc 与 Knife4j），不从 Spring `Environment` 或 `application.yaml` 读取 Kuudra App 配置。

HTTP 适配层位于 `io.github.actforever.kuudra.web.controller`：

- `/api/v1/kuudra`：内核生命周期、观测与内建资源文档；
- `/api/v1/runtime`：Ability、Resource 与 Session；
- `/api/v1/plugin`：Plugin 与 ResourceTemplate；
- SystemEvent 使用独立的 SSE 资源域。

所有 Controller 只依赖 `KuudraApp`，不得直接暴露或访问 Runtime。ResourceTemplate 响应的 `kind` 使用可直接写入清单的 PascalCase 名称，如 `EventSource`、`Controller`；规范模板引用则包含小写 kind 前缀，例如 `controller/plugin-namespace/plugin-id/template-name`。

## 配置与目录

Web 将可执行 JAR 所在目录作为 App 配置基目录；从 classes 目录运行时使用当前工作目录。App 按以下优先级从高到低深度合并：

1. 创建 `KuudraApp` 时显式传入的 `KuudraConfigResource` 或配置文件；
2. `<home-directory>/config.yaml`；
3. `kuudra-app` 内的 `classpath:/config.yaml`。

内置配置声明 `home-directory: .kuudra`。打包部署时目录固定为：

```text
.kuudra/
  config.yaml
  plugins/                 # Plugin JAR
  manifests/               # Resource
  abilities/               # Ability
    profiles/              # 全局 AbilityProfile
  logs/
  state/
  locale/
```

`manifests/`、`abilities/` 与 `abilities/profiles/` 严格区分 kind；放错目录会使加载失败。旧顶层 `ability-profiles/` 中存在 YAML 时也会明确失败，不会静默忽略。首次启动会创建固定目录，并在缺少 `.kuudra/config.yaml` 时复制默认配置；已有配置不会被覆盖。

`application.yaml` 仍可覆盖 Spring Web 设置，但其中的 `kuudra.*` 不会被读取。Kuudra 设置必须写入 `.kuudra/config.yaml`，例如：

```yaml
runtime:
  worker-threads: 4
ability-profiles: [desktop]
abilities: [notification/startup-sound]
global-context:
  profile: production
```

`ability-profiles` 与完整 `namespace/name` 形式的 `abilities` 产生的启动 claim 取并集；
运行时 direct override 优先，`inherit` 恢复到该合并状态。配置深度合并时列表整体替换。

代码宿主可通过 `KuudraApp.createConfigured(KuudraConfigResource)` 传入最高优先级配置。App 配置使用 kebab-case；v1alpha2 清单字段使用 camelCase。

## OpenAPI 分组

`doc.html` 提供聚合的 `all` 分组，并按资源域提供独立分组：

| 分组 | 内容 |
| --- | --- |
| `all` | 所有 HTTP API。 |
| `kuudra` | 内核快照、详细状态、生命周期控制和资源规约文档。 |
| `runtime` | Ability、Resource 和 Session 的查询与控制。 |
| `plugin` | 已加载 Plugin、ResourceTemplate 与结构化文档。 |
| `system-events` | SystemEvent SSE 订阅。 |

各组规范位于 `/v3/api-docs/{group}`，Swagger 配置入口为 `/v3/api-docs/swagger-config`。分组只改变文档呈现，不改变 REST 路径或 App 边界。
