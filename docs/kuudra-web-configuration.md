# Kuudra Web 配置

`kuudra-web` 将 App 的配置嵌入自己的 `application.yaml`：所有内核配置位于 `kuudra:` 下。Spring Boot 先完成配置源合并，再由 Web 将该段配置作为 `KuudraConfigResource` 传给无框架依赖的 `KuudraApp`。

因此，发布后的 `kuudra-web` 会主动探测可执行 JAR 同级的 `application.yaml`，并在创建应用上下文前将它注入为最高优先级 Spring 属性源，从而覆盖包内配置；不依赖启动工作目录。例如：

```yaml
kuudra:
  base-directory: .
  plugins:
    directories:
      - plugins
    homeDirectory: .kuudra/plugin-homes
  flowsDirectory: flows
  globalContext:
    profile: production
```

相对的插件目录和 `flowsDirectory` 均以 `kuudra.base-directory` 为基准；默认值 `.` 即 Web 的启动工作目录。若 JAR 并非从其所在目录启动，应把 `base-directory` 显式设为部署目录的绝对路径。

`kuudra-app` 仍可独立使用 `KuudraApp.createFromDefaultLocations()`，其配置优先级为 `KUUDRA_CONFIG_PATH`、`kuudra.config.path` JVM 属性、再到开发期类路径 `kuudra.yaml`。这条独立 App 路径与 Web 的 Spring 配置合并路径刻意分开。
