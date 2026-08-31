# Kuudra v0.5 真实插件端到端验证

本矩阵验证打包后的 Web JAR、外部插件 JAR、v1alpha2 清单、Ability claim、Resource 生命周期与 HTTP 适配器。单元测试不能替代这些检查。

## 构建前提

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:+UseSerialGC'
mvn test -DskipTests=false
mvn package -DskipTests

$env:MAVEN_OPTS='-Xmx256m -XX:+UseSerialGC'
foreach ($reactor in 'kuudra-official-plugins','kuudra-audio-plugins','kuudra-automation-plugins','kuudra-windows-plugins') {
  Push-Location "..\$reactor"
  mvn clean package -DskipTests=false
  Pop-Location
}
```

原生宿主的正式包必须由 `.NET 8 SDK` 执行 `dotnet publish --runtime win-x64 --self-contained true` 生成。内嵌的 `Kuudra.Windows.PrivilegedHost.exe` 应是约 67 MB 的单文件发布物；约 9 MB 的普通 apphost 会依赖旁边的 DLL，不得装入 JAR。Maven Ant copy 必须使用 `overwrite=true`，不能让旧 `target/classes` 按时间戳覆盖本轮 publish 结果。

若本机没有 .NET SDK，可以复用由同一 C# 源码生成且哈希已验证的 self-contained 产物完成 Java/黑箱验证，但必须明确记录 `dotnet test/publish` 未在本轮执行。

## 固定部署结构

将 Web JAR 复制到一个空目录，并在其同级创建：

```text
.kuudra/
  config.yaml
  plugins/
  manifests/
  abilities/
    profiles/
```

`config.yaml` 必须显式选择示例 Profile 或 Ability，例如：

```yaml
ability-profiles: [default]
# 也可以使用：abilities: [hello-world-demo/hello-to-log]
```

只复制当前场景需要的插件 JAR。每个 JAR 都会被严格加载，缺失强依赖、重复身份、版本范围不兼容和普通非插件 JAR 都必须使启动失败。

## 场景一：HelloWorld、边界与具名 Controller

使用官方插件仓库 `examples/hello-world-logging` 的 `v1alpha2` Resource、Ability 与 AbilityProfile，并装入：

- `kuudra-official/default`
- `kuudra-official/hello-world`
- `kuudra-official/logging`

期望：

1. `/api/v1/runtime/abilities/hello-world-demo/hello-to-log` 返回 `ENABLED`，`profileClaims` 包含 `default`；
2. EventSource、Ingress、Controller 三个 Resource 均为 `RUNNING`；
3. `/api/v1/plugin/resource-templates/controller/kuudra-official/logging/event-logger` 发布 `log` handler；
4. 日志持续出现 `received hello-world`，且带 Ability ID 与 Session ID；
5. 不出现 `ability.reconciliation.failed`、`reconciliation.loop.failed` 或 HTTP 500；
6. `POST /api/v1/kuudra/stop` 后 HTTP 仍可查询到 `STOPPED`。

EventSource 必须在 `start()` 前获得 Runtime emitter；App 应先物化全部 Resource、注册 Ability、绑定 Source，再按“下游优先、EventSource 最后”启动生命周期。该顺序必须有 App 回归测试。

## 场景二：Ability 控制与路径参数 API

对上述 Ability 依次调用 `pause`、`resume`、`disable`、`enable`、`inherit`：

- 控制端点立即返回 `202 Accepted`；
- 最终状态异步收敛；
- DISABLED 时 Resource 无其他 claim 才 stop/destroy；
- PAUSED 不销毁 Resource；
- configuration/Profile claim 与 direct override 的优先级符合用户指南，`inherit` 恢复二者的并集。

生产 JAR 不依赖 Java 反射参数名。所有 Spring `@PathVariable` 必须显式写变量名；Ability、Resource、Plugin 和 ResourceTemplate 的单项查询应返回 200 或业务 404，不能因缺少 `-parameters` 返回 500。

## 场景三：Windows 原生宿主与 process-control

在管理员 PowerShell 中装入：

- `kuudra-official/default`
- `kuudra-official/session-probe`
- `actforever/windows-native-host`
- `actforever/process-control`

使用 `kuudra-windows-plugins/examples/process-control-safe`。示例自身按序启动并清理测试目标。

期望：

1. 仅加载 host JAR 不产生 UAC；被 Profile claim 的 process-control Resource 初始化且 `allowElevation: true` 时才启动 broker；
2. host、process-control、session-probe、default、logging 的插件依赖顺序正确，Ability 与全部 Resource 为 RUNNING；
3. `process-controller` 发布 `start`、`terminate`、`suspend`、`resume` 四个 handler；
4. 序号 1 普通启动 `PING.EXE`，序号 2 暂停，序号 3 显式恢复，序号 4 终止，event-logger 同时记录四个事件；
5. 专用窗口 fixture 分别验证标题 `EXACT/CONTAINS`、进程名、PID、`UNIQUE/ALL` 与映像路径复核；普通/管理员启动报告的令牌权限符合静态 `runElevated`；
6. 在挂起窗口内停止 App，目标立即恢复，broker 退出；已完成 start 的独立进程不会因 Resource 停止被隐式终止；
7. Event/arguments 只能选择静态 alias、可选 PID 与有界时长，broker 不接收 Event/Context，也不提供 PowerShell/Shell 执行。

## 场景四：音频宿主与提示音 Controller

装入 `default`、`session-probe`、`actforever/audio-host` 和 `actforever/audio-player`，使用
`kuudra-audio-plugins/examples/audio-prompt`，并将短 WAV 放入
`.kuudra/plugins/actforever/audio-player/audio/notify.wav`。

期望：

1. 根 `abilities: [audio-demo/prompt]` 在无 Profile 时使 Ability 为 ENABLED，响应中 `configurationClaim=true`；
2. audio-host 不发布 ResourceTemplate，audio-player 发布包含六个具名 handler 的 Controller；
3. Resource 初始化只建库和获取租约，不播放声音；事件到达 `play` 后才打开默认输出设备；
4. `awaitCompletion: true` 时 Session 保持活动直到 WAV 播放结束；pause/resume/stop 不互相覆盖生命周期暂停；
5. 缺少 host JAR 时依赖校验失败，未知 track 或越界目录仅使对应调用/Ability 明确失败；
6. audio-host fat JAR 保留 MP3/Vorbis `AudioFileReader` SPI 和第三方许可文件。

## 场景五：Windows network-control 与跨插件恢复

装入 `default`、`logging`、`session-probe`、`windows-native-host`、`network-control`，
使用 `kuudra-windows-plugins/examples/network-control-safe`。默认仅激活 `network-soft-safe`：

1. host 单独加载或 network Ability 未 claim 时不启动 broker、不触发 UAC；
2. network Controller 模板发布五个具名 Handler，Ability 与所有已 claim Resource 为 RUNNING；
3. `PING.EXE` 的出站流量在 `block-outbound` 后被阻止，`restore-outbound` 后恢复，临时防火墙规则消失；
4. 同时装入并 claim process-control 与 network-control 时只共享一个 broker，但 owner 状态互不覆盖；
5. `event-logger` 同时收到 probe Event，证明默认边界、日志和原生 Controller 组合路由正常；
6. Resource disable、App stop 和 JVM 管道断开均清理网络 owner，下一次 broker 启动可补偿遗留日志。

硬断网测试必须先通过 `Get-NetAdapter` 固定活动接口 GUID/精确名称，并在独立管理员进程中
建立至少 15 秒的恢复看门狗。验证同一次调用可禁用任意多张白名单网卡、5 秒示例窗口后恢复，
且原本禁用的网卡不会被启用。不要在仅能远程访问的机器上无人值守执行。

## 组合与失败矩阵

| 组合/操作 | 期望 |
| --- | --- |
| default + hello-world + logging | 完整事件链路，无错误 |
| windows-native-host 单独加载 | 插件 ACTIVE，不启动 broker、不触发 UAC |
| process-control 缺 host | 插件依赖校验失败 |
| host + process-control，Ability 未 claim | 不物化 Controller，不启动 broker |
| network-control 缺 host | 插件依赖校验失败 |
| host + network-control，Ability 未 claim | 不物化 Controller，不启动 broker |
| host + process-control + network-control | 共享一个 broker，进程与网络 owner 独立恢复 |
| default + session-probe + logging + network-control | probe 分支同时完成具名网络操作与 Event 日志 |
| audio-host 单独加载 | 插件 ACTIVE，不打开音频设备、不发布 ResourceTemplate |
| audio-player 缺 audio-host | 插件依赖校验失败 |
| audio-host + audio-player + 直接 Ability claim | WAV 提示音执行，configurationClaim 可观测 |
| `allowElevation: false` 后 claim | 仅该 Ability FAILED，其他独立 Ability 可继续收敛 |
| 未知 Controller handler | Ability 编译失败并明确指出 handler |
| 同一 Controller 的 `suspend`/`resume` 节点 | 分别路由到对应方法，不使用动态 action 分派 |
| App restart | 重新读取磁盘 manifests/abilities/profiles，正常 stop 后再 start |
| App stop during suspension | 恢复目标、清空 owner 操作、关闭 broker |
| App stop during network block/adapter disable | 等待在途操作后恢复防火墙与网卡，再关闭 broker |

## 2026-08-30 实测记录

本轮在管理员 Windows 会话中验证：

- 核心 10 模块 `mvn test -DskipTests=false` 全部通过；13 个官方插件模块 Java/Kotlin 测试、Windows native host 的 3 个 C# 测试及 self-contained `win-x64` publish 全部通过；
- HelloWorld 示例按 `manifests/`、`abilities/`、`abilities/profiles/` 部署，字符串与对象 Resource 引用均成功解析；Ability 为 ENABLED，3 个 Resource 为 RUNNING，`log` handler 持续收到带 Ability/Session ID 的事件，日志 0 ERROR；
- Ability 的 pause/resume/disable/enable/inherit 均返回 HTTP 202，并依次收敛到 PAUSED/ENABLED/DISABLED/ENABLED/ENABLED；restart 返回 200，重新读取三类目录后恢复 ENABLED + INHERIT；
- App stop 后 HTTP 仍可查询到 STOPPED，验证 Web 与 App 生命周期边界；
- conditional-boundary + session-probe 组合物化 6 个 RUNNING Resource，建立 Session dependency，并在 required Session 完成后按 `CANCEL_DEPENDENT` 取消 dependent Session，日志 0 ERROR。

## 2026-08-31 network-control 实测记录

本轮在本地管理员 Windows 会话中使用打包后的 Web JAR 与 `default`、`logging`、
`session-probe`、`windows-native-host`、`process-control`、`network-control` 六个插件交叉验证：

- 官方插件 16 模块 `mvn clean package -DskipTests=false` 全部成功，包含 self-contained `win-x64` publish；最终 native host 11 个 C# 测试、network-control 6 个 Java 测试通过；
- HTTP 同时显示 soft、hard、process 三个 Ability 为 RUNNING，原始 `/runtime/resources` 响应包含 10 个 Resource 且全部 `state=RUNNING`；Controller 模板返回五个预期 Handler；
- process-control 与两个 network-control Resource 共享唯一 broker，broker 的 ParentProcessId 与 Web JVM PID 完全相同，owner 操作互不覆盖；
- 软断网窗口内实际观测到一条 `Kuudra Network Control ...` 出站阻止规则，恢复后规则数为 0；
- 一次 `disable-adapters` 同时使两张原本为 Up 的非主链路 VMware 虚拟网卡进入 Disabled，`restore-adapters` 后两张均回到 Up；独立 30 秒管理员看门狗作为额外兜底；
- event-logger 分别记录 soft/hard sequence 1/2，process `suspend`、网络四个操作均完成，未出现 operation/session failure；
- `POST /api/v1/kuudra/stop` 返回 STOPPED，随后 broker 数为 0、防火墙规则为 0、两张网卡为 Up，`active-network-operations.json` 不存在；
- 强杀首次 JVM 时发现旧 broker 可能停在“command Pipe 已连接、event Pipe 未连接”的启动窗口。修复后两条 Pipe 并行等待并监控 JVM/两分钟上限；专项测试模拟半连接后客户端退出，返回 `CLIENT_EXITED` 且不再遗留等待进程。

## 2026-08-31 仓库拆分与 process-control 实测记录

本轮在本地管理员 Windows 会话中使用打包后的 Web JAR，并同时装入四个独立 Reactor
产出的全部 15 个插件：

- `kuudra-official-plugins`、`kuudra-audio-plugins`、`kuudra-automation-plugins`、
  `kuudra-windows-plugins` 均可独立执行 `mvn clean package -DskipTests=false`；Windows Host
  的 14 个 C# 测试、process-control 的 8 个 Java 测试和 network-control 的 6 个 Java
  测试全部通过；
- 15 个插件全部进入 ACTIVE，跨仓库的 audio-player → audio-host、JNativeHook/AWT Robot →
  user-interaction-spec、macro-kotlin/AWT Robot → macro-spec、process/network-control →
  windows-native-host 依赖均由独立 JAR ClassLoader 正确解析；
- `process-controller` 模板通过 HTTP 暴露 `start`、`terminate`、`suspend`、`resume` 四个入口，
  测试 Ability claim 的 5 个 Resource 全部完成初始化，且只启动一个 Broker；
- WinForms fixture 先后实际验证普通 `ProcessBuilder` 启动、管理员 Broker 启动、窗口标题
  `EXACT` 与 `CONTAINS`、默认 `UNIQUE` 与静态 `ALL`。两个同名批量 fixture 同时存活后，
  `ALL` 一次将二者全部终止；所有终止均未从 Event 传 PID，因此进程名、窗口标题和映像路径
  复核链路均真实参与匹配；
- 本轮 Web JVM 自身运行在管理员令牌下，因此普通启动按设计继承管理员令牌；这不等价于
  `runElevated: true`。后者仍通过 Broker 路径创建，且 fixture 报告管理员令牌；
- `POST /api/v1/kuudra/stop` 返回 STOPPED，随后 Broker 数为 0、fixture 数为 0，未遗留
  active operation recovery journal，日志无 ERROR/WARN。
