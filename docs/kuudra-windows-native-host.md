# Kuudra Windows Native Host 架构

本文说明外部插件 `actforever/windows-native-host` 如何把 Kuudra 的 Java 插件模型连接到受限的 C# Windows 特权宿主，以及下级插件如何复用这些能力。它不是通用的 PowerShell、shell 或任意 RPC 执行器。

## 1. 分层与职责

```text
Kuudra App / PluginManager
  │ 解析 metadata.toml、建立依赖 ClassLoader、管理生命周期
  ▼
windows-native-host Java 父插件
  │ WindowsNativeHost 强类型 API + NativeHostProvider
  │ 双随机 Named Pipe、双向 PID 校验、请求关联、CompletionStage
  ▼
提升权限的 C# broker（独立进程）
  │ 白名单、owner lease、deadline、恢复日志
  ▼
Windows Win32 API
  └─ 当前仅 OpenThread / SuspendThread / ResumeThread
```

Java 侧仍是唯一的 Kuudra 组件运行时。它持有 `KuudraEvent`、`ActionContext`、Session lease、占位符解析和 `ExecutionControl`；C# broker 不加载插件、不理解 Flow/Event/Context，也不能反向创建 Kuudra 组件。

## 2. 下级插件如何连接 Kuudra 与宿主

`windows-native-host` 自身的 `components.idx` 为空，因此不会向 Flow 注册 Component。Kuudra 扫描其 `metadata.toml` 后只创建 `WindowsNativeHostPlugin`：

1. `initialize(PluginContext)` 取得父插件 home 和身份绑定的 `PluginLogger`；
2. 创建唯一 `NativeHostProvider`，安装到 `WindowsNativeHost` 静态门面；
3. 同时把 provider 注册到插件私有资源表，供生命周期清理；
4. `destroy()` 先撤销静态门面，再恢复 owner 操作、关闭管道和 broker 句柄。

下级 `actforever/process-control` 在自己的 `metadata.toml` 中声明对 `actforever/windows-native-host [0.1.0,0.2.0)` 的强制依赖。Kuudra 在创建 ClassLoader 前验证依赖和版本范围，并把父插件 ClassLoader 链接给下级。因此两边看到的是同一个 `WindowsNativeHost`、`ProcessTarget` 和 `ProcessControlLease` 类，而不是各自 shade 出来的重复类型。

Kuudra 随后按注解发现 `process-control` EventHandler。只有清单实际选中该资源时，`ProcessControlEventHandler.initialize(PluginComponentContext)` 才会：

1. 从不可变 `spec.options` 解码静态目标白名单和时长上限；
2. 用组件 canonical reference 作为 owner 前缀；
3. 调用 `WindowsNativeHost.acquireProcessControl(...)`；
4. 获得只属于该组件实例的 `ProcessControlLease`。

Event 到达 Handler 后，Java 侧从已解析的调用配置中读取 `SUSPEND/RESUME`、目标别名、可选 PID 和时长。`SUSPEND` 返回的 CompletionStage 会一直占有当前 Session work lease，直到 broker 报告恢复完成。Kuudra 暂停时 Handler 进入 `checkpoint()`，但原生 deadline 不延长；Session 取消、Component stop/destroy 会调用 `RESUME` 或 `RESTORE_OWNER`。

## 3. JAR 如何携带并启动 C# 程序

Maven 在构建阶段执行 C# 测试，再运行 `dotnet publish -r win-x64 --self-contained true` 生成无需目标机安装 .NET 的单文件 EXE，计算 SHA-256，并把二者放入插件 JAR 的 `META-INF/native/win-x64/`。shade 包含 Jackson 与 JNA；空的 `components.idx` 阻止依赖类被当作 Component 扫描。

首次申请特权能力时，`NativeHostProvider` 才把 EXE 解压到 `<plugin-home>/native/0.1.0/win-x64/`。已有文件也必须重新计算 SHA-256；不匹配时以临时文件重新提取、校验后原子替换。单纯加载父插件或下级插件不会解压或启动 EXE。

Java 使用 JNA `ShellExecuteEx`，verb 为 `runas`，参数只有随机 command/event pipe 名、期望 JVM PID 和固定恢复日志路径。`allowElevation: false` 会在调用 `ShellExecuteEx` 之前失败；用户拒绝 UAC 则映射为 `UAC_CANCELLED`。

## 4. Named Pipe 协议和双向身份验证

C# broker 创建两条单实例 Named Pipe，并分别用当前 Windows SID 构造显式 ACL：command pipe 严格串行执行 Java→broker request/response，event pipe 只承载 broker→Java 异步完成事件。拆分传输是为了避免 Windows 同步 pipe handle 上并发阻塞读写产生互锁。

两条连接都执行身份校验：broker 调用 `GetNamedPipeClientProcessId` 验证 JVM PID；Java 调用 `GetNamedPipeServerProcessId` 验证刚启动的 broker PID。随后 command pipe 上的 `HELLO` 再核对协议主版本和 broker 自报 PID。

每一帧是 `4 字节大端长度 + UTF-8 JSON`，最大 64 KiB。Envelope 只允许 `request`、`response`、`event`。单线程 command executor 保证请求顺序并核对响应 UUID，专用 event reader 完成异步 `PROCESS_OPERATION_COMPLETED`。Future 的业务回调在 I/O 线程之外执行，避免生命周期回调重入同一个 command executor。

当前操作集合固定为 `HELLO`、`ACQUIRE_PROCESS_CONTROL`、`SUSPEND`、`RESUME`、`RESTORE_OWNER`、`SHUTDOWN`。协议不接受类名、方法名、脚本或命令行，因此下级插件不能借此执行任意 C# 代码。

## 5. C# broker 如何执行受限操作

`ACQUIRE_PROCESS_CONTROL` 为 owner 注册“别名 → 绝对可执行文件路径”和最大时长。执行 `SUSPEND` 时 broker：

1. 按路径查找进程；多实例时强制要求 PID；
2. 再次核对 PID、进程启动时间和 `MainModule.FileName`，避免 PID 复用或同名程序；
3. 拒绝同一进程的重叠 suspension，避免累计线程暂停计数；
4. 枚举线程，用 `OpenThread` 和 `SuspendThread` 逐一暂停；任一步失败会恢复已暂停线程；
5. 在返回成功前写入 `active-process-operations.json`；
6. deadline 到期、显式恢复或 owner 清理时调用 `ResumeThread`，再清除日志并发送完成 event。

日志只保存恢复所需的 PID、启动时间、镜像路径和线程 ID。broker 重启读取日志时会重新验证进程身份后才恢复，避免把旧 PID 的线程操作应用到新进程。

## 6. 生命周期与故障边界

| 情况 | 行为 |
| --- | --- |
| 只加载 JAR | 无 UAC、无 EXE 解压、无 broker |
| 首个允许提升的组件初始化 | 启动并握手一个共享 broker |
| 多个组件实例 | 各自 owner lease，共享传输和 broker |
| 正常 deadline | broker 恢复线程并发完成 event |
| Session 取消 / Component 停止 | Java 请求恢复该 owner |
| App 正常销毁 | 恢复所有 lease，再请求 `SHUTDOWN` |
| JVM 断开 | broker 保持到活动操作 deadline，恢复后退出 |
| broker 被强杀 / 机器断电 | 不能保证立即恢复；下次获批启动后读取日志补偿 |

未来防火墙、网卡等能力应新增独立的 DTO、allowlist、lease 和固定 RPC，不应扩大成通用命令执行。无需管理员权限的 Overlay 应由普通权限 sidecar 或 Java UI 插件实现，不能放进高权限 broker。
