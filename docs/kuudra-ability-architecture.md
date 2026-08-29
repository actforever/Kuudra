# Kuudra Ability 与 Resource 架构

Kuudra `v0.5` 将静态资源、路由编排和事件入口拆成三个独立概念：

- `ResourceTemplate` 是插件公布的资源构造契约；
- `Resource` 是 App 按 Ability 声明按需物化并拥有生命周期的实例；
- `Ability` 是资源声明、节点入口和边组成的可控制执行单元。

## Controller 与 EventHandler

插件使用类型级 `@Controller` 声明 Controller ResourceTemplate，使用方法级
`@EventHandler` 声明具名输入入口。一个 Controller 可以暴露多个入口，Ability 节点必须显式选择
`handler`，因此事件路由不再只能定位到一个含义不明确的 EventHandler 对象。

Handler 必须为 `public`、非静态方法，名称在 Controller 内唯一，并严格使用：

```java
CompletionStage<Void> handler(KuudraEvent event, EventHandlerContext context)
```

归档加载阶段会验证签名。`EventHandlerContext.arguments()` 是 Ability 节点提供的动态参数；
Resource 清单中的 `spec.options` 只负责静态初始化配置。

## Resource 生命周期与策略

所有插件资源实现 `ResourceLifecycle`。该接口统一提供 `initialize/start/pause/resume/stop/destroy`，
默认实现均已完成，App 是唯一生命周期所有者。ResourceTemplate 可声明：

- `maxInstances` 与 `APP/ABILITY` 限额范围；
- `exclusivityDomain`；
- `allowParallel`，默认允许并行调用。

插件归档可使用 `META-INF/kuudra-plugin/resources.idx` 限定扫描类；空索引明确表示插件不公布资源。
扫描器跳过 shaded multi-release JAR 的 `META-INF/versions/**`。

## Ability 与 Session 边界

`kuudra.io/v1alpha2` 的 Ability 将 `resources`、`nodes`、`edges` 显式分开。Ingress 节点必须选择：

- `CREATE`：创建 Session，并在节点内声明调度和依赖；
- `JOIN`：通过同一 Ability 内的 `targetIngress` 加入唯一匹配的 CREATE Session。

CREATE 的本地缺省值为 `PARALLEL/INGRESS/64/256`，group scope 仅允许 `INGRESS` 或 `ABILITY`。
JOIN 找不到或找到多个目标时拒绝执行；加入的工作共享目标 Session 的失败、取消与租约。

Ability 是启用、暂停和依赖/互斥控制边界。资源实例由所有有效 Ability claim 推导：存在运行 claim
时运行，仅有暂停 claim 时暂停，无 claim 时停止并销毁。Profile 只产生 Ability claim，运行时直接控制优先于
Profile；`inherit` 清除直接覆盖。

