# Kuudra v1alpha2 Resource 与 Ability 清单

本文说明当前 v0.5 开发线的部署清单。总体运行模型见 `kuudra-ability-architecture.md`，用户操作入口见 `kuudra-user-guide.md`。

## 声明目录

```text
<home-directory>/
  manifests/               # 只允许 Resource
  abilities/               # 只允许 Ability
    profiles/              # 只允许全局 AbilityProfile
```

三个目录均递归读取 `.yaml`/`.yml` 并支持 `---` 多文档，但不能混放 kind。旧顶层 `ability-profiles/` 不再兼容；其中存在 YAML 时加载失败并给出迁移提示。

Resource 与 Ability 省略 `metadata.namespace` 时使用 `default`。AbilityProfile 是全局对象，禁止声明 namespace。

## Resource

Resource 是 App 拥有并按 Ability claim 按需物化的插件实例。支持 `EventSource`、`EventInterpreter`、`EventAdapter`、`Ingress`、`Controller`、`Egress`。

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata:
  namespace: shared-services
  name: logger
spec:
  template: kuudra-official/logging/event-logger
  options:
    level: INFO
```

`spec.template` 固定为 `plugin-namespace/plugin-id/template-name`。`options` 是静态初始化配置，禁止占位符。相同 `kind/namespace/name` 表示同一 App-owned 实例；不同名称表示不同实例。

## Ability

Ability 是调度、控制和声明 claim 的单位。它描述节点、边、Controller handler、Ingress Session 语义和动态 arguments。

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Ability
metadata:
  namespace: demo
  name: hello
spec:
  resources:
    source: EventSource/input/hello
    ingress:
      kind: Ingress
      namespace: shared-routing
      name: create
  nodes:
    source:
      resource: source
    ingress:
      resource: ingress
      session:
        mode: CREATE
        scheduling:
          policy: PARALLEL
          groupScope: INGRESS
    logger:
      resource: Controller/shared-services/logger
      handler: log
      arguments:
        message: "${event#hello-world.message}"
  edges:
    - { from: source, to: ingress }
    - { from: ingress, to: logger }
```

`spec.resources` 是可选 alias 表。alias 值与节点直接 Resource 引用都支持两种等价形式：

```yaml
# 字符串
resource: Controller/shared-services/logger

# 对象
resource:
  kind: Controller
  namespace: shared-services
  name: logger
```

字符串必须是完整 `kind/namespace/name`。对象必须同时提供 `kind`、`namespace`、`name`。Resource 引用的 namespace 永远不会从 Ability 的 `metadata.namespace` 继承；Ability 命名空间是选择和控制边界，Resource 命名空间独立表达资源池身份。

节点 `resource` 的无斜杠字符串被解释为 alias。alias 可以未使用，但只有实际出现在节点中的 Resource 才产生 claim；声明 alias 本身不会初始化资源或触发权限请求。所有 alias 和节点直接引用仍必须指向已声明 Resource，以便错误尽早暴露。

Controller 节点必须选择 `handler`。Ingress 节点必须声明 CREATE 或 JOIN；CREATE 拥有调度与依赖，JOIN 只能指向同一 Ability 中的 CREATE Ingress。一个 EventSource Resource 在同一 Ability 只能出现于一个节点，需要扇出时从该节点声明多条边。

节点 `arguments` 支持原生 YAML 数值、布尔、映射与列表，并可包含 `${event#...}`、`${session#...}`、`${ability#...}`、`${global#...}`。占位符在注册 Ability 时预编译，执行时只进行作用域查找。

## AbilityProfile

```yaml
apiVersion: kuudra.io/v1alpha2
kind: AbilityProfile
metadata:
  name: default
spec:
  abilities:
    - demo/hello
```

Profile 固定放在 `abilities/profiles/`。根配置的 `ability-profiles` 选择全局 Profile，根配置的
`abilities` 以 `namespace/name` 直接选择 Ability；二者的启动 claims 取并集。运行时直接控制优先，
`inherit` 恢复配置合并状态。Resource 生命周期是所有活动 Ability 节点 claims 的合并结果。

## 加载与失败边界

App 每次 start/restart 都重新读取完整的 Resource、Ability 与 Profile 集合，并在物化任何 Resource 前完成：

1. apiVersion、kind、目录归属与字段校验；
2. 重复身份、节点和 edge 校验；
3. alias 与直接 Resource 引用存在性校验；
4. 插件 ResourceTemplate 与实例策略校验；
5. Ability 编译、claim 合并与生命周期调谐。

未知 kind、错误目录、重复身份、不完整引用、缺失目标或不兼容静态 options 都会使本次导入失败。StateStore 持久化声明与 observed generation，但不保存 Session、Event payload 或运行时上下文。
