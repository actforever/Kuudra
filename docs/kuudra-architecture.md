# Kuudra 核心架构

Kuudra 是事件驱动内核。v0.5 的部署单元是 Ability，不再是 Flow；插件发布的是
ResourceTemplate，App 按 Ability claim 物化 Resource，Runtime 只执行已编译的路由图。

## 模块边界

| 模块 | 职责 |
| --- | --- |
| `kuudra-api` | Event、Context、生命周期、Controller handler 等公共契约 |
| `kuudra-config` | 根配置与 `kuudra.io/v1alpha2` 清单解析 |
| `kuudra-state` | MyBatis/SQLite Deployment 期望与观测状态 |
| `kuudra-plugin` | 归档、依赖 ClassLoader、ResourceTemplate 注册与实例构造 |
| `kuudra-runtime` | Ability 图、RAW/SESSION 路由、任务队列与 Session 协调 |
| `kuudra-app` | 生命周期、Ability claim、Resource 调谐和控制面 façade |
| `kuudra-web` | 只面向 App 的 REST/SSE/OpenAPI 适配器 |
| `kuudra-logging` | SystemEvent 控制台和文件投影 |

## 事件域

```text
EventSource / EventInterpreter / RAW EventAdapter
  -> Ingress CREATE 或 JOIN
      -> Controller / SESSION EventAdapter
          -> Egress
              -> RAW
```

`KuudraEvent` 是不可变业务消息。Runtime 使用 `RawEventWrapper` 和
`SessionEventWrapper` 明确域，不在 Event 上附加 nullable Session。Ingress 是唯一
RAW->SESSION 边界，Egress 是唯一 SESSION->RAW 边界并保留 EventLineage。

上下文分为 Event、Session、Ability 和 Global 四个逻辑作用域。`${path}` 只搜索当前
可用作用域，`${event#path}`、`${session#path}`、`${ability#path}`、`${global#path}`
严格指定来源。占位符语法在 Ability 注册时预编译，事件热路径只做查找和结果装配。

## 扩展点

插件资源类型是 EventSource、EventInterpreter、EventAdapter、Ingress、Controller 和
Egress。Controller 通过多个具名 `@EventHandler` 方法提供业务入口；Handler 异步返回
`CompletionStage<Void>`，可在 stage 完成前 emit，并继承当前 Session 和 lineage。

同步 Adapter/Interpreter/Ingress/Egress 应轮询 `ExecutionControl.poll()` 并快速返回；
长运行 Handler 可在稳定边界调用 `checkpoint()`。SessionControl 只能操作当前 Session，
Runtime 仍拥有工作租约、终态和依赖传播。

## Ability、Resource 与 Session

Resource 身份为 `kind/namespace/name`，模板引用为
`type/plugin-namespace/plugin-id/template-name`。同一 Resource 被多个 Ability claim 时
共享一个 App 所有的实例；Runtime 仅尊重 `allowParallel` 调用策略，不启动或销毁它。

Ability 显式声明可选 Resource aliases、nodes 和 edges。节点可引用 alias，也可用
`kind/namespace/name` 字符串或 `{kind, namespace, name}` 对象直接引用 Resource；两种格式
在加载期归一化，且 Resource namespace 不继承 Ability namespace。Controller 节点
还选择 handler，节点 arguments 是动态调用参数。Ability 是启用、暂停、排空、依赖和
互斥边界，所有 Ability 都是对等的。

Ingress CREATE 内联声明有界 group scheduling 和 Session dependencies；JOIN 只指向同一
Ability 的 CREATE 节点。Session 没有隐式父子生命周期。SessionManager 创建 Session 并
拥有 lease，单一 SessionCoordinator 维护组队列和活动依赖图。

## 生命周期与控制

App 状态保持 `CREATED -> RUNNING -> STOPPING -> STOPPED`，并具有内核级
`PAUSING/PAUSED/RESUMING` 子流。内核 pause 是 DATA 工作的粗粒度闸门；CONTROL
Ability 保持可路由，但仍受自身和 Session 控制。

Ability 的 `enable/pause/resume/disable/inherit` 是异步收敛请求。暂停设置独立的
ABILITY suspension reason；禁用关闭新工作、排空/取消现有 Session、注销图，再让无
claim Resource 执行 stop/destroy。所有 Resource 生命周期调用有统一超时。

## 插件与 Windows native host

插件身份始终是 `namespace/pluginId`。依赖版本范围在 ClassLoader 创建前校验；依赖插件
的 class/resource 对 dependent 可见。扫描支持 resources index 并跳过 multi-release
版本目录。

Windows native host 只导出类型化、owner-scoped 原生能力。Java 仍拥有 Resource、
Ability、Event、Context 和调度；broker 不是第二个 Runtime，也不是 Event IPC。UAC
只在允许提权的被 claim Resource 初始化时发生。进程控制和未来网络控制只能接受静态
allowlist 与类型化参数，不允许任意 PowerShell/shell。

## 控制面与观测

磁盘 v1alpha2 清单是启动时权威集合。SQLite 核心 schema v2 保存 Resource、Ability、
AbilityProfile 的 generation 和 observed generation；从 v0.4 迁移只重建 Kuudra 核心表。

App 拥有唯一 SystemEventBus，Runtime 和插件只接收 write-only publisher。Web Controller
只依赖 App。Runtime API 暴露 Ability、Resource、Session；Plugin API 暴露插件和
ResourceTemplate。生命周期细节使用 DEBUG，顶层启动/停止、有效插件和失败保持 INFO。

更详细的清单与运行语义见 `kuudra-ability-architecture.md`、`kuudra-bootstrap.md` 和
`kuudra-user-guide.md`。
