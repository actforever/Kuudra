# Kuudra 内核闭环验证

本文记录可重复执行的真实插件端到端验证，而不仅是模块单元测试。验证基线使用根目录 `.kuudra/plugins` 中的三个插件 JAR：

- `kuudra-official/default`：提供 `plain-ingress`；
- `kuudra-official/hello-world`：提供周期 EventSource；
- `kuudra-official/logging`：提供 EventHandler，并经 App SystemEventBus 输出日志。

## 验证清单

在 `.kuudra/manifests/hello-world.yaml` 使用一个多文档 YAML 声明三个 Component 和一个 Flow：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata: {namespace: dev, name: hello-world-source}
spec:
  component: kuudra-official/hello-world
  desiredState: running
  options: {intervalMillis: 1000}
---
apiVersion: kuudra.io/v1alpha1
kind: Ingress
metadata: {namespace: dev, name: plain-ingress}
spec:
  component: kuudra-official/plain-ingress
  desiredState: active
  options:
    groupKey: "${event#hello-world.message}"
    sessionLabels: {role: hello-world}
---
apiVersion: kuudra.io/v1alpha1
kind: SessionCoordinationPolicy
metadata: {namespace: dev, name: hello-world-serial}
spec:
  selector: {matchLabels: {role: hello-world}}
  scheduling: {policy: SERIAL, maxParallelSessions: 1, queueCapacity: 32}
---
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata: {namespace: dev, name: event-logger}
spec:
  component: kuudra-official/event-logger
  desiredState: running
  options:
    level: INFO
    message: "E2E received ${event#hello-world.message}"
    includeData: true
---
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata: {namespace: dev, name: hello-world-flow}
spec:
  imports:
    source: {kind: EventSource, name: hello-world-source}
    ingress: {kind: Ingress, name: plain-ingress}
    logger: {kind: EventHandler, name: event-logger}
  edges:
    - {from: source, to: ingress}
    - {from: ingress, to: logger}
```

EventSource 和 EventHandler 实现生命周期，因此期望状态是 `running/stopped`；无生命周期的 Ingress 使用 `active/inactive`。混用两组状态会在启动校验阶段失败。

## 复验步骤与判定

使用项目根目录作为可执行 JAR 的目录启动 Web，确保固定家目录解析为根目录 `.kuudra`：

```powershell
mvn -pl kuudra-web -am package -DskipTests
Copy-Item kuudra-web/target/kuudra-web-v0.4.4.-alpha-1.jar ./kuudra-e2e.jar
java -jar ./kuudra-e2e.jar
```

通过以下接口观察，不直接访问 Runtime：

- `GET /api/v1/kuudra/status`；
- `GET /api/v1/plugin`；
- `GET /api/v1/runtime/components`；
- `GET /api/v1/runtime/flows`；
- `GET /api/v1/runtime/session-coordination-policies`；
- `GET /api/v1/runtime/sessions/dependencies`；
- `GET /api/v1/runtime/components/reconciliation-states`。

当前闭环判定矩阵如下：

| 能力 | 操作 | 必须观察到的结果 |
| --- | --- | --- |
| 插件加载 | 启动包含三个 JAR 的内核 | API 返回三个 `ACTIVE` 插件，插件 home 使用 `<plugins>/<namespace>/<plugin-id>` |
| 事件路由 | 保持四个资源清单生效 | 周期出现 `E2E received hello-world`；Session 创建后归零，无租约泄漏 |
| 周期调谐 | `reconciliation.enabled=true` 且日志为 TRACE | 每周期严格成对出现 `reconciliation.cycle.started/completed` |
| 调谐禁用 | 关闭配置并重启 Web 进程 | 不产生周期调谐事件，资源仍在启动阶段完成首次收敛 |
| 状态可见性 | 改写任一 Component desiredState | DEBUG 出现 `component.state.changed`，INFO 出现 `resource.state.changed`；无变化的周期不重复输出 |
| 生命周期门控 | EventSource 或 EventHandler 改为 `stopped` | observed/effective 均为 `STOPPED`，组件不再产生或处理事件；恢复 `running` 后继续 |
| 被动组件门控 | Ingress 改为 `inactive` | 资源实例和 Flow 绑定保留，但事件不再准入；恢复 `active` 后继续 |
| StateStore | API 将磁盘中为 running 的资源改为 stopped | 当前 generation 收敛并持久化；下一次 start 以 manifests 为权威恢复 running |
| 失败重试 | 生命周期第一次调谐失败 | StateStore 先记录 FAILED，周期循环重试同一 generation，最终进入 READY |
| DATA 内核暂停 | DATA Flow 下调用 `/pause` | observed state 不变，effectiveStatus 为 `SUSPENDED` 且原因是 `KERNEL`，DATA 事件停止流转 |
| CONTROL 暂停旁路 | `system` CONTROL Flow 显式导入 `macro` EventSource 后调用 `/pause` | 两个 namespace 均被选中；Flow 继续输出控制事件，共享 Source 保持 available，组件/Session 自身暂停仍有效 |
| 内核恢复 | 调用 `/resume` | effectiveStatus 恢复，事件从保留的组件状态继续流转 |
| 暂停态停止 | `PAUSED` 时调用 `/stop` | 正常走 `STOPPING -> STOPPED`，释放 Runtime、Session、组件和插件 |
| 暂停态重启 | `PAUSED` 时调用 `/restart` | 正常停止后重新加载 manifests，最终回到 RUNNING，不走强制清空分支 |
| 清单重载 | 修改 EventSource interval 后 `/restart` | 新实例采用新间隔；运行期间不会隐式扫描磁盘 |
| 清单诊断 | 将 `spec.edges` 误写后 `/restart` | 返回失败并进入 FAILED，错误包含文件、文档号、附近行、资源身份、字段和正确格式；修复后 `/start` 可恢复 |
| 文件日志 | 正常停止并再次启动 | 停止产生日期序号 gzip 且保留 latest.log；下一次启动才新建 latest.log |

### 跨命名空间控制 Flow 黑箱验证

使用真实 `kuudra-official/hello-world`、`kuudra-official/default` 和 `kuudra-official/logging` JAR：在 `macro` namespace 只声明一个周期 EventSource，在 `system` namespace 声明 plain Ingress、日志 Handler 与 `spec.session.executionClass: CONTROL` Flow。Flow 的 source import 显式写 `namespace: macro`，根配置使用 `resource-selection.namespace-mode: INCLUDE` 并同时选择 `[macro, system]`。

验证时先确认 Flow API 返回 `executionClass: CONTROL` 且日志持续出现事件，再调用 `/api/v1/kuudra/pause`。等待至少两个 EventSource 周期后，日志计数必须继续增长，`EventSource/macro/...` 的 `status/effectiveStatus` 均保持 `RUNNING` 且 `available: true`。随后从 PAUSED 调用 `/stop`，两类执行器、组件和插件必须正常释放。另一次启动只选择 `system` 时必须因所引用的 `macro` 资源不在激活闭包内而失败，不能隐式扩大 namespace 集合。

### 会话依赖真实插件验证

会话依赖不能只依赖单元测试。发布前还应使用官方 `kuudra-official/session-probe` 与 `kuudra-official/conditional-boundary` 真实插件 JAR，在同一个 Flow 中声明窗口和作业两个分支（可直接采用官方插件仓库的 `examples/session-dependency/manifests.yaml`）：两个 Ingress 只生成 `role=window/job` 标签，`SessionCoordinationPolicy` 自动选择作业 Session、使用 `SERIAL` 调度并声明 `UNIQUE + CANCEL_DEPENDENT` 标签依赖。验证顺序如下：

1. 先启动 B，再准入 A，依赖查询接口必须在二者存活期间返回一条活动边；
2. B 正常结束后，SystemEvent 必须依次包含 `session.dependency.established` 和 `session.dependency.termination-propagated`，A 的协作式执行控制必须观察到取消；
3. A 的第二个事件只能在首个 Session 终止后从 SERIAL 队列出队；此时 B 已不存在，依赖应在实际启动时重新解析并产生 `session.dependency.rejected`；
4. 被拒绝的 Session 不得路由到 EventHandler，最终活动 Session、依赖边和延迟任务均归零；
5. 通过 App 停止接口关闭内核后，状态应为 `STOPPED`，Flow、Session、依赖边及任务队列均已释放。

该用例同时证明：调度策略先于依赖解析、排队任务不会沿用过期选择结果、终止传播可被插件通过协作式检查观察，以及依赖图能够通过 App/Web 查询而不泄露 Runtime。

`POST /api/v1/kuudra/restart` 重建的是 App 内核，并按约定重新读取 manifests；`config.yaml` 在 Web 宿主创建 App Bean 时合并，修改根配置（包括调谐开关、日志级别）后需要重启 Web 进程。二者不要混为同一种重载语义。

## 自动化回归

真实插件验证之外，以下测试为高并发或故障分支提供确定性覆盖：

```powershell
mvn test -DskipTests=false
mvn -f D:/Users/pinec/Documents/Code/Java/kuudra-official-plugins/pom.xml test -DskipTests=false
```

其中 Runtime 测试覆盖 RAW/SESSION 域边界、非法边、占位符作用域、Session 串行调度、替换策略、租约排空、最大跳数和协作式暂停；Plugin 测试会真实编译 A/B 两个 JAR，证明 B 能引用 A 的类和资源、共享同一个 `Class<?>` 并完成父插件 POJO 的 JSON 往返，同时验证依赖身份、版本范围、缺失依赖和环检测。
