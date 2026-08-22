# Kuudra 最小内核 Demo

当前 Demo 验证的是不依赖 JNativeHook、AWT Robot 或 YAML 解析器的最小内核闭环：

```text
内存 RawSignal（两次 A）
  → Runtime 输入管线中的双击 RawSignalProcessor
  → gesture.a.doublePressed
  → double-a-to-c Flow 的 SessionProcessor
  → PARALLEL 会话
  → 异步 Actor
  → 输出“simulate key C”
```

运行：

```powershell
.\mvnw.cmd test
java -cp "kuudra-app\target\classes;kuudra-runtime\target\classes;kuudra-api\target\classes;kuudra-config\target\classes" io.github.actforever.kuudra.app.KuudraDemo
```

预期最后两行包含：

```text
Action executed: simulate key C [session=...]
Kuudra demo completed successfully.
```

## 已实现边界

- `kuudra-api`：RawSignal、会话、Processor、Actor 与内存状态视图 SPI。
- `kuudra-runtime`：有界 RawSignal 队列、输入管线、会话准入、`PARALLEL`/`QUEUED`/`TOGGLE`/`IGNORE`、内存状态表、协作取消和异步串行 Actor。
- `kuudra-app`：双击 A 到模拟 C 的无插件 Demo。
- `kuudra-config`：实现了供 Demo 使用的受限 YAML 子集编译器；它不支持 YAML 列表、锚点或标签，正式 YAML/JSON/TOML 适配器仍是后续迭代项。
- `kuudra-plugin`、`kuudra-web`：已建立模块边界；Fat JAR ClassLoader 和 HTTP/WebSocket 仍是后续迭代项。

当前 Flow 以 `SessionProcessor` 为入口，Runtime 输入管线不属于任何 Flow。这与架构文档的当前会话边界一致；后续配置编译器需要把 YAML 的 `ingressPipelines.outputs` 校验为 Flow 中存在的 SessionProcessor。
