# Kuudra 资源清单与调谐模型

本文定义并记录 Kuudra 的资源与编排模型。当前版本从 `<home-directory>/manifests/**/*.yaml` 加载具体组件 kind 与 Flow；Flow 通过 `spec.imports` 引用同命名空间的组件资源并配置路由，组件资源不引用 Flow。通用资源写 API 与持续调谐仍是后续工作。

当前 App/Web 已能以统一只读接口查询所有清单 Component 实例，而不只查询 EventSource：`GET /api/v1/app/resources/components` 返回类型、插件组件引用、期望/实际状态、导入它的 Flow 和真实生命周期能力，也可按类型或 `type/namespace/name` 定位。EventSource 原有专用 start/stop API 暂时保留；其他组件只公开其实际的 materialize/destroy 能力，在通用调谐写接口完成前不伪造 start/stop 操作。

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

具体组件资源描述“创建哪个实例”。两个 Flow 只有导入相同 `kind/namespace/name` 资源身份时才共享同一 Java 实例。仅仅使用相同的 `spec.component` 不会触发复用。

### Flow 配置

Flow 资源描述“实例如何连接”。它是具体组件资源的消费者：`imports` 以完整 kind/namespace/name 引用资源并分配 Flow 内别名，`edges` 只引用这些别名。资源不允许引用或导入 Flow；Flow 也不再内嵌组件构造信息或拥有导入实例的生命周期。Flow 只能导入自身 namespace 内的资源，跨 namespace 引用在配置加载阶段失败。

## 清单格式

资源清单采用 K8s 风格的信封结构，并遵循其 camelCase 字段惯例；App 根配置仍使用 kebab-case，两类配置不应混用：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macros
  name: keyboard-hook
  labels: {}
  annotations: {}
spec:
  component: native-input/jnativehook-keyboard
```

资源身份固定为 `(apiVersion, kind, metadata.namespace, metadata.name)`，规范路由地址为 `kind/namespace/name`。`metadata.namespace` 是内核强制执行的资源隔离边界，不等于插件 namespace、上下文 namespace 或实例互斥域；Flow 与被导入资源必须处于相同 namespace。缺省资源 namespace 可使用 `default`。

一个文件既可以只包含一个资源，也可以像 Kubernetes 一样使用 `---` 分隔多个 YAML 文档。加载器会按“文件路径 + 文档序号”定位错误，并在全部文件和文档范围内检查重复资源身份。独立文件仍更便于原子更新和人工管理，但不再是格式限制。

### 具体组件资源示例

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata:
  namespace: macros
  name: keyboard-hook
spec:
  component: native-input/jnativehook-keyboard
  desiredState: running
  options:
    capture-mouse: false
```

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata:
  namespace: macros
  name: keyboard-robot
spec:
  component: awt-input/keyboard-robot
  desiredState: active
  options: {}
```

`kind` 直接决定资源类型，支持 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`；不再存在 `kind: Component` 或 `spec.type`。`spec.component` 指向插件组件定义。options 只属于资源实例；同一实例被多个 Flow 导入时不能在 Flow 中分别覆盖 options，否则共享身份将失去确定语义。

当前 `desiredState` 是启动装配阶段的一次性目标，而不是后台持续运行的控制器：

- `EventSource`：`running` 会注册并启动事件源，`stopped` 只物化资源、不启动事件生产；
- `EventInterpreter`、`EventAdapter`、`Ingress`、`EventHandler`、`Egress`：`active` 会物化并允许 Flow 导入，`inactive` 不物化且不能被 Flow 导入；
- `Flow`：支持 `active`、`paused`、`stopped`，App 注册路由后把闸门切换到目标状态。

其他状态会令启动失败。当前没有监听文件/API 变更的持续调谐循环，运行期间的专用 start/stop API 也尚未写回持久期望状态。

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
      kind: EventSource
      namespace: macros
      name: keyboard-hook
    allocate:
      kind: Ingress
      namespace: macros
      name: combat-session
    robot:
      kind: EventHandler
      namespace: macros
      name: keyboard-robot
  edges:
    - from: keyboard
      to: allocate
    - from: allocate
      to: robot
```

同一 namespace 的另一个 Flow 可以再次 import `EventSource/macros/keyboard-hook`。App 只创建并启动一个 EventSource，Runtime 为它安装一对多 emitter/binding，将产生的 Event 分别投递到两个 Flow 的目标别名。任一 Flow 暂停或停止时只关闭自己的路由闸门，不停止共享 EventSource。

Ingress 必须显式声明为 `kind: Ingress` 资源。官方默认实现来自已加载的 `kuudra-official/default` 插件；加载插件本身不会隐式创建任何资源实例。

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

### `state/` 与未来 SQLite StateStore

当前实现只在启动时确保 `.kuudra/state/` 目录存在，没有任何代码读写该目录，也没有 SQLite 依赖、数据库文件、ResourceRepository 或后台调谐循环。因此它目前只是为后续状态存储预留的目录，不能视为已经实现的 etcd。

未来推荐在该目录使用嵌入式 SQLite 实现单机 `StateStore`，保存规范资源身份、完整期望 spec、generation、observedGeneration、实际状态与 condition。资源清单和未来 `kuudractl apply` 是写入期望状态的入口；SQLite 是内核唯一持久事实源；调谐器观察数据库中的 generation 并把实际状态收敛后更新 observed 状态。Session、事件负载和插件自行持久化的数据不应进入该状态库。

## 家目录目标结构

建议目标结构为：

```text
.kuudra/
  config.yaml               # 仅内核、日志等 App 设置
  manifests/                # 用户声明的具体组件、Flow 等资源
    input/
      keyboard-hook.yaml
    actions/
      keyboard-robot.yaml
    flows/
      combat.yaml
  plugins/                  # 插件 JAR 与插件运行时家目录
  logs/
  state/                    # 预留给未来 SQLite StateStore；当前为空且未使用
```

不用 `conf/`，因为它难以区分 App 配置与资源对象；不用顶层 `flows/`，因为 Flow 已经只是多种资源之一。`manifests/` 递归扫描 `.yaml/.yml`，子目录只服务人类整理，资源身份完全由 metadata 决定。未知 kind、重复身份、损坏文件或引用缺失都应使本次期望状态导入失败。

## 迁移顺序

1. 在 API/配置模块加入资源信封、Component/Flow spec、引用和校验模型，不接入运行时。
2. 为插件组件定义加入实例策略和 lifecycle capability，并完成扫描测试。
3. 实现 App ResourceRegistry、依赖图和一次性事务式 reconcile；先覆盖 Component 创建和 Flow import。
4. 将 EventSource emitter 改为一实例多绑定，并验证跨 Flow 启停与失败回滚。
5. 增加统一资源 API 和 `kuudractl apply/get/delete`，再接入持久 ResourceRepository。
6. 启用 `.kuudra/manifests/` 启动加载，并删除顶层 `flows/` 初始化与旧 Flow schema。

整个迁移期间，Event/Session 路由不变量、插件依赖类加载和 App/Web 边界保持不变。
