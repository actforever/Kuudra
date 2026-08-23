# Kuudra 资源清单与调谐模型

本文定义并记录 Kuudra 的资源与编排模型。当前版本从 `<home-directory>/manifests/**/*.yaml` 加载 Component 与 Flow 两种资源；Flow 通过 `spec.imports` 引用 Component 并配置路由，Component 不引用 Flow。旧 `<home-directory>/flows/*.yaml` schema 在迁移期继续兼容；通用资源 API 与持续调谐仍是后续工作。

## 设计目标

1. 组件实例与 Flow 编排分离，避免为了复用一个 EventSource 而把不相关自动化逻辑写进同一个 Flow。
2. 资源是否复用由稳定资源身份决定，不根据 Java 类型或插件组件引用进行隐式单例推断。
3. 插件声明实现能力和实例数量约束，部署清单声明具体实例；内核联合校验两者。
4. 文件启动和未来的 `kuudractl apply` 使用同一种资源对象及调谐链路。
5. 生命周期控制表达期望状态，App 负责把实际状态收敛到期望状态并报告失败条件。

## 三层模型

```text
插件组件定义                 资源声明                         Flow 编排
annotation/metadata   →   Component 实例   ← import/绑定 →   Flow + edges
能力、约束、实现类           身份、options、期望状态           路由、会话边界
```

### 插件组件定义

插件扫描得到的定义描述“能创建什么”，不代表实例。除类型、名称和实现类外，后续注解元数据至少需要表达：

- 是否允许共享同一实例；
- 是否保证并发安全；
- 实例数量上限；
- 数量限制的作用域；
- 互斥域；
- 支持的生命周期能力；
- options schema、输入输出和组件说明。

### Component 资源

Component 资源描述“创建哪个实例”。两个 Flow 只有导入相同资源身份时才共享同一 Java 实例。仅仅使用相同的 `spec.component` 不会触发复用。

### Flow 配置

Flow 资源描述“实例如何连接”。它是 Component 资源的消费者：`imports` 为 Component 分配 Flow 内别名，`edges` 只引用这些别名。Component 不允许引用或导入 Flow；Flow 也不再内嵌组件构造信息或拥有导入实例的生命周期。

## 清单格式

资源清单采用 K8s 风格的信封结构，并遵循其 camelCase 字段惯例；App 根配置仍使用 kebab-case，两类配置不应混用：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Component
metadata:
  namespace: input
  name: keyboard-hook
  labels: {}
  annotations: {}
spec: {}
```

资源身份固定为 `(apiVersion, kind, metadata.namespace, metadata.name)`。`metadata.namespace` 是资源命名空间，不等于插件 namespace、上下文 namespace 或实例互斥域；四者不可混用。缺省资源 namespace 可使用 `default`。

一个文件可以包含一个对象；是否支持 YAML 多文档应在实现加载器时一次决定并补充测试。首版建议一个文件一个资源，便于原子更新、错误定位和未来 apply 持久化。

### Component 示例

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Component
metadata:
  namespace: input
  name: keyboard-hook
spec:
  type: event-source
  component: native-input/jnativehook-keyboard
  desiredState: running
  options:
    capture-mouse: false
```

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Component
metadata:
  namespace: actions
  name: keyboard-robot
spec:
  type: actor
  component: awt-input/keyboard-robot
  desiredState: active
  options: {}
```

`spec.component` 仍指向插件组件定义。options 只属于 Component 实例；同一实例被多个 Flow 导入时不能在 Flow 中分别覆盖 options，否则共享身份将失去确定语义。

### Flow 示例

```yaml
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata:
  namespace: macros
  name: combat
spec:
  desiredState: active
  imports:
    keyboard:
      kind: Component
      namespace: input
      name: keyboard-hook
    allocate:
      kind: Component
      namespace: macros
      name: combat-session
    robot:
      kind: Component
      namespace: actions
      name: keyboard-robot
  edges:
    - from: keyboard
      to: allocate
    - from: allocate
      to: robot
```

另一个 Flow 可以再次 import `input/keyboard-hook`。App 只创建并启动一个 EventSource，Runtime 为它安装一对多 emitter/binding，将产生的 Event 分别投递到两个 Flow 的目标别名。任一 Flow 暂停或停止时只关闭自己的路由闸门，不停止共享 EventSource。

`SessionAllocator` 也应表示成 `Component` 资源，其 `spec.component` 指向内置的稳定引用，例如 `core/session-allocator`，从而避免 Flow schema 再次出现特殊内嵌节点。

## 实例数量与互斥域

“是否允许多例”应升级为通用实例策略，而不是单一布尔值：

```text
maxInstances: 1
limitScope: app
exclusivityDomain: native-input/jnativehook
shareable: true
threadSafe: true
```

- `maxInstances`：同一限制键允许存在的最大实例数；缺省为不限制。
- `limitScope`：首期支持 `app` 和 `flow`。App 范围覆盖全部 Flow；Flow 范围只在单个 Flow 内计数。
- `exclusivityDomain`：把不同组件定义纳入同一个资源冲突域，例如所有会安装 JNativeHook 全局钩子的实现。
- `shareable`：是否允许一个实例被多个 Flow 导入。
- `threadSafe`：共享调用是否可并行；为 false 时即使允许共享，Runtime 也必须串行化调用或拒绝并发绑定。

有效限制键为 `(limitScope-owner, exclusivityDomain)`，不是实现类名。互斥域必须使用限定名称 `<authority>/<name>`；插件自己的域默认以插件 namespace 为 authority，跨插件协议则由共同依赖的契约插件或 `kuudra.system/*` 保留域定义。这样既避免无意同名冲突，也允许两个不同插件明确声明它们竞争同一个全局钩子。

插件元数据是硬约束，资源清单不能放宽它。清单可以选择更严格的部署策略，但不能把 `maxInstances: 1` 的组件扩成多例，也不能把非 shareable 组件强制跨 Flow 共享。调谐前必须先对完整期望状态执行数量与能力校验，违反约束时不创建任何一半成功的实例。

对于 AWT Robot，更推荐插件在自身生命周期内维护一个共享底层服务，多个轻量 Actor 使用该服务；如果 Actor 本身无状态且线程安全，也可直接声明为 shareable 并由多个 Flow 导入同一个 Component 资源。

## 生命周期能力

所有资源都应具有统一的 `metadata/spec/status` 和创建、观察、更新、删除流程，但不应伪造完全相同的 start/stop 语义。

| 资源 | 期望状态 | 调谐行为 |
| --- | --- | --- |
| EventSource Component | `running/stopped` | 获取或释放外部监听器、线程、端口、设备句柄。 |
| Adapter/Processor/Actor Component | `active/inactive` | 创建实例并允许路由，或关闭新投递并按策略排空在途调用。 |
| Flow | `active/paused/stopped` | 控制路由和 Session 闸门，不拥有导入组件。 |

组件定义需要声明 `lifecycleCapabilities`。EventSource 通常支持 start/stop；被动组件至少支持 materialize/destroy，active/inactive 是 Runtime 路由门控，不要求插件实现没有意义的 `start()`。

删除资源表示从期望状态中删除该资源并由调谐器回收实例，不叫“从上下文删除”。Event、Session、Flow、Global context 是数据作用域；删除其中的键属于独立的数据操作和权限问题，不能与资源 DELETE API 混为一谈。

资源状态至少包含：

```yaml
status:
  observedGeneration: 3
  phase: ready
  conditions:
    - type: Ready
      status: "true"
      reason: Reconciled
  bindings: 2
```

`metadata.generation` 在 spec 变化时递增；只有 `status.observedGeneration` 追上它，调用方才能确认新期望状态已经生效。

## 调谐与控制 API

App 保存期望资源图，调谐器按以下顺序工作：

1. 解析清单并按资源身份去重；
2. 校验 schema、插件组件存在性、实例策略、import 和 edges；
3. 构建组件、Flow 与绑定依赖图；
4. 计算创建、更新、删除差异；
5. 先创建依赖，再切换绑定，最后回收旧实例；
6. 失败时保留上一份可工作状态，并把原因写入 status condition 与 SystemEvent；
7. 持续重试可恢复错误，对不可恢复配置错误等待新的 generation。

控制 API 应围绕资源，而不是为每种组件复制一套控制器：

```text
GET    /api/v1/resources
GET    /api/v1/resources/{kind}/{namespace}/{name}
PUT    /api/v1/resources/{kind}/{namespace}/{name}
PATCH  /api/v1/resources/{kind}/{namespace}/{name}
DELETE /api/v1/resources/{kind}/{namespace}/{name}
GET    /api/v1/resources/{kind}/{namespace}/{name}/status
```

start/stop/enable/disable 是对 `spec.desiredState` 的便捷子资源操作，只有资源声明相应 lifecycle capability 时才接受。现有 App、Flow、EventSource 专用 API 可在迁移期作为适配层，最终都调用同一个 ResourceService。HTTP 继续只暴露 App 资源，不暴露 Runtime。

`kuudractl apply -f xxx.yaml` 将同一清单提交给 ResourceService，以资源身份和 generation 幂等更新，然后观察调谐状态。持久化期望状态需要 ResourceRepository/StateStore；在该能力完成前，CLI apply 必须明确标记为仅当前进程有效，不能暗示重启后仍存在。

## 家目录目标结构

建议目标结构为：

```text
.kuudra/
  config.yaml               # 仅内核、日志等 App 设置
  manifests/                # 用户声明的 Component、Flow 等资源
    input/
      keyboard-hook.yaml
    actions/
      keyboard-robot.yaml
    flows/
      combat.yaml
  plugins/                  # 插件 JAR 与插件运行时家目录
  logs/
  state/                    # 内核管理的持久状态；用户不直接编辑
```

不用 `conf/`，因为它难以区分 App 配置与资源对象；不用顶层 `flows/`，因为 Flow 已经只是多种资源之一。`manifests/` 递归扫描 `.yaml/.yml`，子目录只服务人类整理，资源身份完全由 metadata 决定。未知 kind、重复身份、损坏文件或引用缺失都应使本次期望状态导入失败。

## 迁移顺序

1. 在 API/配置模块加入资源信封、Component/Flow spec、引用和校验模型，不接入运行时。
2. 为插件组件定义加入实例策略和 lifecycle capability，并完成扫描测试。
3. 实现 App ResourceRegistry、依赖图和一次性事务式 reconcile；先覆盖 Component 创建和 Flow import。
4. 将 EventSource emitter 改为一实例多绑定，并验证跨 Flow 启停与失败回滚。
5. 增加统一资源 API 和 `kuudractl apply/get/delete`，再接入持久 ResourceRepository。
6. 启用 `.kuudra/manifests/` 启动加载；迁移期可只读兼容 `flows/`，但同一 Flow 身份同时出现时必须报错。
7. 示例、插件文档和部署工具完成迁移后，删除顶层 `flows/` 初始化与旧 Flow schema。

整个迁移期间，Event/Session 路由不变量、插件依赖类加载和 App/Web 边界保持不变。
