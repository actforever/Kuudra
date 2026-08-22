# Kuudra Web 配置

`kuudra-web` 只使用 Spring 的 Web 配置（例如 `server.port`、SpringDoc 与 Knife4j），不再从 Spring `Environment` 或 `application.yaml` 读取任何 Kuudra App 配置。

Web 创建 `KuudraApp` 时，将可执行 JAR 所在目录作为 App 的配置基目录；开发期从 classes 目录运行时使用当前工作目录。App 按以下优先级深度合并配置：

1. 初始化 `KuudraApp` 时直接传入的 `KuudraConfigResource` 或配置文件；
2. `<home-directory>/config.yaml`；
3. `kuudra-app` 包内的 `classpath:/config.yaml`。

内置配置声明 `home-directory: .kuudra`，因此打包部署时用户配置默认为 `<jar-directory>/.kuudra/config.yaml`。所有相对插件目录和 `flows-directory` 也以 JAR 所在目录为基准。

`application.yaml` 仍可放在可执行 JAR 同级并覆盖 Spring Web 设置，但其中的 `kuudra.*` 属性不会被读取。Kuudra 配置必须写入 `.kuudra/config.yaml`，例如：

```yaml
runtime:
  worker-threads: 4
plugins:
  load:
    - hello-world/hello-world
global-context:
  profile: production
```

代码宿主可通过 `KuudraApp.createConfigured(KuudraConfigResource)` 传入最高优先级配置；该映射也必须使用小写 kebab-case 键。
