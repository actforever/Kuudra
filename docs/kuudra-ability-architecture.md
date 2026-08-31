# Kuudra Ability 与 Resource 架构

Kuudra v0.5 将插件能力、静态实例和路由编排拆成三层：

```text
Plugin archive
  -> ResourceTemplate（构造契约、策略、文档）
      -> Resource（静态 options、App 所有的生命周期）
          <- Ability claim
              -> node（入口与动态 arguments）
                  -> edge（Event 路由）
```

Resource 清单固定放在 `manifests/`，Ability 放在 `abilities/`，全局 KuudraProfile
放在 `profiles/`。Ability namespace 只属于 Ability 身份和选择域，不参与
Resource 引用解析。

Ability 节点可以通过 `spec.resources` 中的 alias 引用 Resource，也可以直接使用
`kind/namespace/name` 字符串或 `{kind, namespace, name}` 对象。两种格式在加载期统一为
完整 `ResourceReference`；Resource namespace 必须显式出现在引用中。未使用 alias 合法，
但不会产生 claim。实际 claim 始终从节点引用去重计算。

## Controller 与 EventHandler

插件以类型级 `@Controller` 发布 Controller ResourceTemplate，以方法级
`@EventHandler` 发布具名入口。一个 Controller 可以具有多个入口，Ability 节点必须显式
选择 `handler`，从而解决旧模型只能把 Event 路由到整个 EventHandler 对象、无法表达
具体功能入口的问题。

入口签名严格为：

```java
CompletionStage<Void> handler(KuudraEvent event, EventHandlerContext context)
```

归档扫描会校验方法必须 public、非 static、名称在 Controller 内唯一、参数和返回类型
完全匹配。`EventHandlerContext.arguments()` 提供节点动态参数和执行控制；Resource
`spec.options` 只提供不含占位符的静态初始化配置。

## Resource 生命周期和策略

每个资源实现 `ResourceLifecycle`，统一具有
`initialize/start/pause/resume/stop/destroy`。默认方法允许无状态资源只实现需要的阶段，
但所有阶段都由 App 调用，Runtime 不取得生命周期所有权。

ResourceTemplate 策略包括：

- `maxInstances` 与 `APP`/`ABILITY` 限额范围；
- `exclusivityDomain`，用于拒绝同一互斥域中的冲突实例；
- `allowParallel`，默认 true；false 时 Runtime 跨 Ability 串行调用同一 Resource。

归档可提供 `META-INF/kuudra-plugin/resources.idx` 限定扫描类；空索引表示不发布资源。
扫描必须跳过 shaded multi-release JAR 的 `META-INF/versions/**`。

## EventAdapter 与 EventInterpreter

EventAdapter 对单个 Event 做无状态、同步的过滤或映射，并以 `List<KuudraEvent>` 返回结果。
EventInterpreter 用于连击、序列、组合键和其他跨 Event 状态机，签名固定为：

```java
void interpret(KuudraEvent event, EventInterpreterContext context)
```

Interpreter 不返回同步列表；立即和延迟结果都调用 `context.emit`。Context 提供节点 arguments、
节点私有 `state()`、命名 `buffer()`、可替换/取消的命名定时任务，以及支持多原因 Event 的
emit。Runtime 以 `ability/revision/node` 为作用域串行执行输入和超时回调，所以同一 Resource
实例被多个 Ability claim 时不会混合窗口状态。`allowParallel=false` 仍额外串行化不同节点
对共享实例的调用。

Ability 暂停、Resource 暂停、DATA 内核暂停、禁用、注销和关闭都会丢弃未完成窗口：取消
定时任务、清空 state/buffer，并使旧 context 无法继续发射。恢复后从新的输入重新计数。

## Ability claim 与状态

Ability 是运行时的控制和排空边界。当前 KuudraProfile 的成员集合与运行时直接覆盖共同
计算有效状态：

- 没有 claim：DISABLED；
- 至少一个 ENABLED claim：ENABLED；
- 只有 PAUSED claim：PAUSED；
- 初始化或调谐失败：FAILED，并保留可观测 detail。

直接控制优先于 Profile，`inherit` 删除直接覆盖并恢复到 Profile 状态。`dependsOn` 在同 namespace 内级联
暂停/禁用，`mutexWith` 阻止互斥 Ability 同时有效。一个 Profile 成员失败不应阻止其他
独立成员收敛。

Resource 状态由全部有效 claim 合并：有运行 claim 时 RUNNING，仅有暂停 claim 时
PAUSED，无 claim 时 stop/destroy。`options` 相同身份的 Resource 永远是同一实例；
不同 Resource 身份才产生不同实例。

EventSource 的 emitter 是启动前置条件。App 先物化所需 Resource、注册 Ability 图并绑定
Source，再启动生命周期；同一批 Resource 中先启动下游消费者，最后启动 EventSource，
避免首个 Event 早于 Controller 或边界就绪。v1alpha2 claim 变化由 AbilityManager 同步
调谐，不进入只理解 v1alpha1 行的旧周期调谐器。

KuudraProfile 同时定义 Ability 集合和 Global Context。Profile 热切换在 Runtime 全局安全点
完成：阻止新的 DATA RAW Event，允许既有 Session 排空，超时后统一取消；随后原子替换
Global Context 与全部节点的预编译 arguments，再调谐 Ability/Resource。成功时清除所有
direct override，失败时恢复旧 Global/Profile；恢复失败才使 App 进入 FAILED。切换只影响
当前生命周期，restart 总是重新读取磁盘并使用根 `active-profile`。

## Session 边界

v1alpha2 的 Ingress 节点显式选择：

- CREATE：创建 Session，并在节点内声明 scheduling 和 dependencies；
- JOIN：以 `targetIngress` 指向同一 Ability 的 CREATE 节点，把新工作租约加入唯一匹配
  的活动 Session。

CREATE 默认值是 `PARALLEL/INGRESS/64/256`；group scope 只允许 `INGRESS` 和
`ABILITY`。JOIN 零匹配或多匹配都拒绝执行，且不创建隐式父子 Session。

Ability 暂停是独立的 ExecutionControl 原因，不会改写 Session 自身的暂停位。禁用先
关闭入口，等待排空，超时后请求协作取消，再注销 Ability 图和无 claim Resource。

## 持久化与 API

StateStore 记录 v1alpha2 Deployment 和 observed generation。Web 只通过 App 暴露：

- `/api/v1/runtime/abilities`：有效状态、直接覆盖、`profileDesiredState`、依赖和互斥；
- `/api/v1/runtime/profiles`：Profile 名称、活动标志、Ability 列表和 Global Context 键名；
- `/api/v1/runtime/resources`：实际生命周期状态和 claimedBy；
- `/api/v1/plugin/resource-templates`：插件发布的构造策略、handler 文档与事件说明。

控制请求返回 202；请求接受与最终收敛是两个不同时间点。SystemEvent 记录状态转换和
失败细节，不携带完整 Event 或上下文负载。
