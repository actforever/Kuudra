# Kuudra 插件运行目录与加载

可执行 Web JAR 启动后，默认以 JAR 所在目录为运行基准，并使用固定布局：

```text
<jar-directory>/.kuudra/plugins/
  kuudra-hello-world-plugin.jar
  kuudra-official/
    hello-world/
```

`.jar` 文件是待加载的插件归档；`kuudra-official/hello-world/` 是由插件 namespace 与插件 ID 共同确定的运行时家目录。完整规则为 `<home-directory>/plugins/<plugin-namespace>/<plugin-id>/`，避免不同命名空间使用相同 ID 时发生数据目录冲突。目录只在插件真正进入初始化时创建，插件可通过 `PluginContext.home()` 或 `PluginComponentContext.plugin().home()` 使用它持久化数据。

## 组件文档与插件日志

插件组件可通过 `@ComponentDoc` 声明用途、配置示例和生命周期阶段，并通过一个或多个 `@EventEmission` 描述可能输出的事件类型、输出阶段与数据示例。归档扫描时，这些信息会和组件定义一起进入注册表，而不是从 README 文本中临时解析。

插件及组件清单由 App 提供只读快照，并通过 Web 的 `/api/v1/app/plugins`、`/api/v1/app/plugins/{pluginId}/components`、`/api/v1/app/components` 和组件详情接口公开。组件是否真正具有生命周期还会根据其接口实现自动识别。

插件代码不直接依赖 Logback。`PluginContext.logger()` 和 `PluginComponentContext.logger()` 返回绑定 namespace 与插件 ID 的 `PluginLogger`；日志先作为 `plugin.log` 系统事件进入 App 总线，再由 `kuudra-logging` 按内核日志配置输出。

插件目录不再可配置，也没有显式加载清单。Kuudra 会按文件名顺序读取 `<home-directory>/plugins/` 下所有 `.jar` 文件，并把它们作为一个依赖图加载。目录中的每个 JAR 都必须是合法 Kuudra 插件：

- 必须是可读取的 JAR；
- 必须包含合法的 `META-INF/kuudra-plugin/metadata.toml`；
- entrypoint 必须存在、可实例化并实现 `KuudraPlugin`；
- 实例 ID 必须与元数据 ID 一致；
- 插件 ID 不能重复；
- 声明的依赖必须也存在于该目录，且依赖图不能成环。

任一归档不满足要求都会中止整个 App 启动；Kuudra 不会忽略未知或损坏的 JAR，也不会只加载部分插件。

## 插件依赖与类可见性

`metadata.toml` 的 `[[dependencies]]` 使用 namespace、插件 ID、`mandatory` 和 `versionRange` 声明直接依赖。插件版本必须是点分隔的非负数字段，可带 `-prerelease` 或 `+build` 后缀且不能带前导 `v`；范围采用 Forge/Maven 风格的 `[a,b)`、`(,b]`、`[a,)` 或 `[a]`。归档加载器会先校验依赖身份、必需性和版本兼容性，再以拓扑递归方式为依赖提供方创建 ClassLoader，最后创建依赖方 ClassLoader。每个插件的查找顺序固定为：

1. Kuudra 宿主 ClassLoader，保证 API 类型身份一致；
2. `dependencies` 声明顺序中的依赖插件 ClassLoader；
3. 当前插件自己的 JAR。

因此依赖方（也可理解为子插件）可以在编译期和运行期直接引用依赖提供方（父插件）公开的 Java 类。依赖 ClassLoader 本身还会继续查询其依赖，所以传递依赖也可见。类由提供方 ClassLoader 唯一定义，依赖方取得的是同一个 `Class<?>`，可以正常进行参数传递、类型转换和静态方法调用。

父插件也可以提供跨组件 DTO/POJO。子插件把实例写入 Event、Session、Flow 或 Global 时，默认 JSON codec 会先转换为不携带插件对象引用的不可变数据树；消费组件调用 `get(..., ParentDto.class)` 时，以调用方传入且由父插件 ClassLoader 唯一定义的类型完成反序列化。这条闭环由动态编译的真实父子插件归档测试覆盖。兄弟插件若要共享 DTO，必须共同依赖提供该类型的契约插件；不要在多个 JAR 中复制同名 DTO。

资源遵循同样顺序；`getResource`、`getResources` 和 Java 9+ 的 `resources` 都包含声明依赖中的资源，因此依赖资源查找和 ServiceLoader 式枚举可以闭环。重复 URL 会在枚举结果中去重。

可见性是单向的：依赖方能看到提供方，提供方不能看到依赖方，未声明依赖的兄弟插件也互不可见。插件不应与宿主或依赖 JAR 重复打包同名类，因为更高优先级定义会遮蔽当前 JAR 中的副本。缺失依赖和依赖环在创建 ClassLoader 前后立即报错，已经创建的 ClassLoader 会在失败回滚时关闭。

生命周期使用同一依赖拓扑：提供方先 `initialize/start`，依赖方后启动；停止时顺序反转。每个成功启动的插件会立即记录到启动顺序中，而不是等整个图成功后才记录。若中途某个依赖方启动失败，该插件会执行 `destroy` 并关闭已注册资源，随后 App 回滚时会逆序停止此前已成功启动的依赖插件。因此归档、ClassLoader、生命周期和失败清理形成闭环。

Flow 组件用节点 `type` 指定类别，组件引用只写 `namespace/component-id`：

```yaml
components:
  input:
    type: event-source
    component: hello-world/loop-emitter
```

内核据此组成内部完整引用 `event-source/hello-world/loop-emitter`，避免在 YAML 中重复类别信息。
