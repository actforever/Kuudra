# Kuudra Windows Native Host 架构

本文说明外部 `actforever/windows-native-host` 如何把 Kuudra 的 Java 插件模型连接到受限的 .NET 8 Windows 特权宿主，以及 `process-control`、`network-control` 如何复用这些能力。它不是 PowerShell、shell 或任意 RPC 执行器。

## 1. 分层与连接

```text
Kuudra App / PluginManager
  │ 解析 metadata.toml、依赖 ClassLoader、ResourceLifecycle
  ├─ windows-native-host Java 父插件
  │    WindowsNativeHost API + NativeHostProvider
  ├─ process-control Controller ─ ProcessControlLease
  └─ network-control Controller ─ NetworkControlLease
                       │ owner-scoped typed RPC
                       ▼
         两条带 SID ACL 的随机 Named Pipe
                       │ 双向 PID 校验、协议 1.1
                       ▼
       Kuudra.Windows.PrivilegedHost.exe（管理员）
          ├─ Win32 线程暂停/恢复
          ├─ Windows Firewall COM 规则
          └─ SetupAPI 网卡启用/禁用
```

父插件不发布 ResourceTemplate。它初始化时只安装唯一 `NativeHostProvider` 静态门面；加载或激活父插件不会解压 EXE、启动 broker 或弹 UAC。下级插件在 `metadata.toml` 声明强依赖，Kuudra 将父 ClassLoader 链接给下级，使双方共享同一套 DTO 和 lease 类型。只有 Ability claim 的下级 Resource 初始化并传入 `allowElevation: true` 时，provider 才通过 JNA `ShellExecuteEx(runas)` 启动 broker。

Java 仍是唯一 Kuudra Runtime：它持有 Event、Context、Ability/Session 调度和 Resource 生命周期。broker 不加载插件，不理解 Event，也不能反向创建 Resource。

## 2. 固定能力与 Handler

协议 `HELLO` 通告：

- `PROCESS_CONTROL`：`ACQUIRE_PROCESS_CONTROL`、`SUSPEND`、`RESUME`、`RESTORE_OWNER`；
- `NETWORK_CONTROL`：`ACQUIRE_NETWORK_CONTROL`、`BLOCK_OUTBOUND`、`DISABLE_ADAPTERS`、`RESTORE_OUTBOUND`、`RESTORE_ADAPTERS`、`RESTORE_NETWORK_OWNER`；
- 公共 `SHUTDOWN`：恢复两类 owner 后退出。

`actforever/network-control` 发布 `network-controller`，恰好提供五个同名小写连字符 Handler。静态 `options.programs` 只能配置既存绝对可执行路径；`options.adapters` 每项只能配置接口 GUID 或精确接口名称之一。动态 Event arguments 只能选择别名列表，不能注入路径、SetupAPI 参数、规则名或命令。

## 3. 认证传输与构建

broker 为 command/event 分别创建单实例 Named Pipe，以当前 Windows 用户 SID 建立显式 ACL。两条 Pipe 并行等待连接；等待期间同时监控启动 JVM 的退出信号和两分钟连接上限。若只连上一条 Pipe 后 JVM 退出，broker 会取消另一条等待并退出，不会成为孤儿进程。连接完成后，broker 使用 `GetNamedPipeClientProcessId` 核对 JVM，Java 使用 `GetNamedPipeServerProcessId` 核对刚启动的 broker；随后 `HELLO` 再验证 PID 和协议主版本。帧为 `4 字节大端长度 + UTF-8 JSON`，上限 64 KiB。command pipe 串行 request/response；event pipe 只承载进程操作异步完成事件。

Maven 运行 C# 测试并执行 `dotnet publish --runtime win-x64 --self-contained true`。单文件 EXE 与 SHA-256 被装入 `META-INF/native/win-x64/`；首次申请能力时才校验并解压到 `<plugin-home>/native/0.2.0-alpha-1/win-x64/`。目标机器不需要另装 .NET。

## 4. 进程能力

broker 在暂停前按白名单路径、可选 PID、进程启动时间和镜像路径重新验证身份，逐线程暂停且在部分失败时回滚。暂停有硬 deadline。自然到期、显式恢复、Session/Resource/App 清理会恢复线程；恢复日志保存完成补偿所需的 PID、启动时间、镜像路径和线程 ID。

## 5. 网络能力

### 5.1 软断网

broker 通过 `HNetCfg.FwPolicy2` / `HNetCfg.FWRule` 创建启用、全 profile、任意协议、仅出站的应用程序阻止规则。规则名由 owner/alias 的 SHA-256 派生，Event 无法控制名称。写入恢复日志发生在添加规则之前；删除不存在的规则按幂等恢复处理。批量添加中途失败会逆序删除本批已添加规则。

### 5.2 硬断网

broker 用 SetupAPI 枚举网络设备，将名称或 GUID selector 解析为唯一接口 GUID，再通过 `DIF_PROPERTYCHANGE` 执行全局禁用/启用并轮询设备状态。同一批可含多张网卡；别名解析到重复 GUID 会被拒绝。

每个接口记录初始启用状态及 owner/alias claim 集合。第一个 claim 仅在网卡原本启用时执行禁用，最后一个 claim 释放时才恢复；原本禁用的网卡永不由 Kuudra 启用。多个 Resource/Ability 可以安全共享网卡而不会互相提前恢复。批量禁用失败会恢复本批已经禁用的网卡并保留无法回滚项的恢复日志。

### 5.3 原子日志与清理顺序

网络日志位于 `<plugin-home>/state/active-network-operations.json`，用临时文件加原子替换写入。它只记录恢复需要的防火墙规则名、网卡 GUID 与初始状态，不保存 Event/Context。

Controller stop 顺序固定为：在生命周期锁内拒绝新 Handler 并快照全部已经接受且已登记的 Future → 等待在途原生操作 → `RESTORE_NETWORK_OWNER`。Handler 的“检查运行态、发起 typed RPC、登记 Future”与 stop 快照互斥，因此不会出现调用已获准却漏入快照，或恢复完成后旧的断网 Future 又落地。显式 restore、Resource stop/destroy、App 关闭、JVM 管道断开均会恢复网络；下次 broker 启动会在接受连接前处理遗留日志。

## 6. 故障边界

| 情况 | 行为 |
| --- | --- |
| 只加载 host JAR | 无 UAC、无 EXE 解压、无 broker |
| `allowElevation: false` | 在启动 broker 前拒绝，仅相关 Ability FAILED |
| 多个下级 Resource | 共享一个 broker，各自随机 owner lease |
| 多 owner 禁用同一网卡 | 引用计数，最后一个 owner 才恢复 |
| 网络批量操作部分失败 | 回滚已完成部分，日志覆盖未恢复状态 |
| Resource/App 正常停止 | 先 drain，再恢复 owner，最后关闭 broker |
| JVM 管道断开 | broker 立即恢复网络；有界进程暂停按既有 deadline 收尾 |
| JVM 在双 Pipe 握手中途退出 | 取消半连接等待并退出 broker |
| broker 被强杀或机器断电 | 无法保证立即恢复；下一次获批启动时读取日志补偿 |

无管理员权限的 Overlay 应由普通权限 Java/UI 插件或独立 sidecar 实现，不应进入这个高权限 broker。
