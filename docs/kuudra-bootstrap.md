# Kuudra v0.5 启动与部署

## 启动顺序

```text
解析并合并 config.yaml
  -> 创建固定 home 目录
  -> 读取 manifests/**/*.yaml、abilities/**/*.yaml 与 profiles/**/*.yaml
  -> 严格扫描 plugins/*.jar
  -> 校验插件依赖并启动插件
  -> 发布 ResourceTemplate
  -> 将 Deployment 写入 StateStore
  -> 读取 active-profile 指向的 KuudraProfile（Ability 集合 + Global Context）
  -> 按需物化 Resource
  -> 编译并注册 Ability
  -> 绑定 EventSource
```

任一步失败都会沿相反顺序清理已经启动的对象。仅装载插件归档不会创建 Resource，
也不会触发 Windows native host 的 UAC；初始化一个允许提权的被 claim Resource 才可能
请求权限。

## 固定目录

打包 Web 使用 `<jar-directory>/.kuudra`。App 初始化确保存在 `plugins/`、
`manifests/`、`abilities/`、`profiles/`、`logs/`、`state/`、`locale/`。插件目录中的每个 JAR
都必须是有效 Kuudra 插件，且所有强制依赖的身份和版本范围必须满足。

## 根配置默认值

```yaml
home-directory: .kuudra
banner-enabled: true
runtime:
  queue-capacity: 1024
  worker-threads: 2
  max-event-hops: 256
  dispatcher-poll-interval-ms: 200
  shutdown-session-drain-timeout-ms: 5000
  ability-drain-timeout-ms: 5000
  cancel-grace-timeout-ms: 5000
  resource-lifecycle-timeout-ms: 120000
active-profile: ""
reconciliation: {enabled: true, interval-ms: 1000}
state-store: {busy-timeout-ms: 5000}
logging: {level: info, console-enabled: true, file-enabled: true}
i18n: {preferred-locale: en_US}
```

不存在全局 namespace selection 或 SessionCoordinator 默认值。`active-profile` 选择一份完整运行配置；
空字符串表示不激活任何 Ability 且 Global Context 为空。Ability 列表和 Global Context 都定义在
KuudraProfile 内。每个 CREATE Ingress 节点拥有自己的有界调度配置。

## v1alpha2 清单

`manifests/` 只允许 Resource kind，`abilities/` 只允许 `Ability`，`profiles/` 只允许
`KuudraProfile`。一个文件可以用 `---` 包含多个
同类文档，身份在对应递归目录中必须唯一。

Resource 示例：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata: {namespace: demo, name: network}
spec:
  template: actforever/network/network-controller
  options: {allowElevation: true}
```

Ability 示例：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Ability
metadata: {namespace: demo, name: disconnect}
spec:
  resources:
    network: {kind: Controller, namespace: demo, name: network}
  nodes:
    disconnect:
      resource: network
      handler: disconnect
      arguments: {target: '${event.data.process.alias}'}
  edges: []
```

Resource options 是静态配置，加载时拒绝占位符；节点 arguments 可在执行时解析作用域。
`spec.resources` 可省略，alias 值与节点直接引用都支持 `kind/namespace/name` 字符串和
`{kind, namespace, name}` 对象。完整 Resource 引用必须包含 namespace，且不继承 Ability
namespace；未使用 alias 不产生 claim。
Controller 节点必须选择插件已发布的具名 handler。v1alpha1 会被明确拒绝，不执行隐式
升级，因为 Flow/Component 的生命周期语义无法无歧义映射到 Ability claim。

Profile 示例（放入 `profiles/default.yaml`）：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: KuudraProfile
metadata: {name: default}
spec:
  abilities: [demo/disconnect]
  globalContext:
    processAlias: game
    # Global 模板在 Ability 注册时预编译，并保留被引用值的原生类型。
    target: '${session.values.target}'
```

KuudraProfile 是全局对象，禁止 `metadata.namespace`。占位符必须使用显式点号路径：
`${event.id}`、`${event.type}`、`${event.occurredAt}`、`${event.data.<namespace>.<path>}`、
`${session.id}`、`${session.abilityId}`、`${session.values.<path>}`、`${ability.id}`、
`${ability.values.<path>}` 与 `${global.<path>}`。无作用域和旧 `#` 写法不再接受。
Global 模板可嵌套引用其他 Global；缺失键、循环引用，以及 RAW 节点通过 Global 间接读取
Session 都会在激活阶段失败。运行期 `GlobalContext.put/update` 写入的是普通值，不会被当成模板。

## Session 与关闭

Ingress 使用 CREATE 或 JOIN。CREATE 本地声明 scheduling/dependencies；JOIN 只通过
`targetIngress` 指向同 Ability 的 CREATE 节点。Runtime 工作租约决定 Session 完成。

禁用 Ability 的顺序是关闭注入、等待 `ability-drain-timeout-ms`、协作取消、等待
`cancel-grace-timeout-ms`、注销图、停止并销毁无 claim Resource。App 停止仍先排空
Session，再逆序关闭 Ability、Resource、Runtime、插件与归档。

## StateStore 迁移

磁盘清单是每次启动的权威集合。StateStore schema `control-plane=3` 保存 v1alpha2 的
Resource、Ability 和 KuudraProfile。检测到旧 schema 时只删除并重建 Kuudra 自身的
`resources` 表；不删除数据库中名称不同的插件表。

## 验证端点

```text
GET  /api/v1/kuudra/status
GET  /api/v1/runtime/abilities
GET  /api/v1/runtime/profiles
GET  /api/v1/runtime/profiles/{name}
GET  /api/v1/runtime/resources
GET  /api/v1/runtime/sessions
GET  /api/v1/plugin/plugins
GET  /api/v1/plugin/resource-templates
POST /api/v1/runtime/abilities/{namespace}/{name}/{action}
POST /api/v1/runtime/profiles/{name}/activate
```

Ability 控制返回 202。`action` 是 `enable/pause/resume/disable/inherit`。Web Controller
只依赖 `KuudraApp`，不会向 HTTP 暴露 Runtime 对象。Profile 热切换也返回 202：Runtime
停止新的 DATA 准入、排空或取消全部 Session、原子替换 Global Context、清除 Ability direct
override 并重新收敛。失败会恢复旧 Profile；重启始终回到 `config.yaml` 的 `active-profile`。

旧根字段 `ability-profiles`、`abilities`、`global-context`，旧 kind `AbilityProfile`，以及旧目录
`ability-profiles/`、`abilities/profiles/` 均直接报迁移错误，不做兼容读取。
