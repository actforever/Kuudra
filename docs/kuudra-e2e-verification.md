# Kuudra v0.5 真实插件端到端验证

本矩阵验证打包后的 Web JAR、外部插件 JAR、v1alpha2 清单、Ability claim、Resource 生命周期与 HTTP 适配器。单元测试不能替代这些检查。

## 构建前提

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:+UseSerialGC'
mvn test -DskipTests=false
mvn package -DskipTests

cd ..\kuudra-official-plugins
$env:MAVEN_OPTS='-Xmx256m -XX:+UseSerialGC'
mvn clean test -DskipTests=false -Dexec.skip=true
mvn package -DskipTests=false -Dexec.skip=true
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

`config.yaml` 必须显式选择示例 Profile，例如：

```yaml
ability-profiles: [default]
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
- Profile claim 与 direct override 的优先级符合用户指南。

生产 JAR 不依赖 Java 反射参数名。所有 Spring `@PathVariable` 必须显式写变量名；Ability、Resource、Plugin 和 ResourceTemplate 的单项查询应返回 200 或业务 404，不能因缺少 `-parameters` 返回 500。

## 场景三：Windows 原生宿主与 process-control

在管理员 PowerShell 中装入：

- `kuudra-official/default`
- `kuudra-official/session-probe`
- `actforever/windows-native-host`
- `actforever/process-control`

使用官方插件仓库 `examples/process-control-safe`。启动一个测试目标：

```powershell
$ping = Start-Process C:\Windows\System32\PING.EXE -ArgumentList '-t','127.0.0.1' -WindowStyle Hidden -PassThru
```

期望：

1. 仅加载 host JAR 不产生 UAC；被 Profile claim 的 process-control Resource 初始化且 `allowElevation: true` 时才启动 broker；
2. host、process-control、session-probe 的插件依赖顺序正确，Ability 为 ENABLED，三个 Resource 为 RUNNING；
3. `process-controller` 发布 `suspend` 与 `resume` 两个 handler，清单显式选择 `suspend`；
4. 事件触发后 `PING.EXE` 线程可观测到 `WaitReason=Suspended`，有界时长后自动恢复且进程仍存活；
5. 在挂起窗口内停止 App，目标立即恢复，broker 退出，Web 状态为 STOPPED；
6. Event/arguments 只能选择静态 allowlist 中的 alias、可选 PID 与有界时长，broker 不接收 Event/Context，也不提供 PowerShell/Shell 执行。

## 组合与失败矩阵

| 组合/操作 | 期望 |
| --- | --- |
| default + hello-world + logging | 完整事件链路，无错误 |
| windows-native-host 单独加载 | 插件 ACTIVE，不启动 broker、不触发 UAC |
| process-control 缺 host | 插件依赖校验失败 |
| host + process-control，Ability 未 claim | 不物化 Controller，不启动 broker |
| `allowElevation: false` 后 claim | 仅该 Ability FAILED，其他独立 Ability 可继续收敛 |
| 未知 Controller handler | Ability 编译失败并明确指出 handler |
| 同一 Controller 的 `suspend`/`resume` 节点 | 分别路由到对应方法，不使用动态 action 分派 |
| App restart | 重新读取磁盘 manifests/abilities/profiles，正常 stop 后再 start |
| App stop during suspension | 恢复目标、清空 owner 操作、关闭 broker |

## 2026-08-30 实测记录

本轮在管理员 Windows 会话中验证：

- 核心 10 模块 `mvn test -DskipTests=false` 全部通过；13 个官方插件模块 Java/Kotlin 测试、Windows native host 的 3 个 C# 测试及 self-contained `win-x64` publish 全部通过；
- HelloWorld 示例按 `manifests/`、`abilities/`、`abilities/profiles/` 部署，字符串与对象 Resource 引用均成功解析；Ability 为 ENABLED，3 个 Resource 为 RUNNING，`log` handler 持续收到带 Ability/Session ID 的事件，日志 0 ERROR；
- Ability 的 pause/resume/disable/enable/inherit 均返回 HTTP 202，并依次收敛到 PAUSED/ENABLED/DISABLED/ENABLED/ENABLED；restart 返回 200，重新读取三类目录后恢复 ENABLED + INHERIT；
- App stop 后 HTTP 仍可查询到 STOPPED，验证 Web 与 App 生命周期边界；
- conditional-boundary + session-probe 组合物化 6 个 RUNNING Resource，建立 Session dependency，并在 required Session 完成后按 `CANCEL_DEPENDENT` 取消 dependent Session，日志 0 ERROR。
