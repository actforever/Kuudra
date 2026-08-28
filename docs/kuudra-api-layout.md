# Kuudra API 包结构

`kuudra-api` 是内核模块与插件共同依赖的公共契约层。除跨边界统一抛出的 `KuudraException` 保留在根包外，公共类型按职责分包：

| 包 | 职责 |
| --- | --- |
| `api.action` | EventHandler 的异步执行上下文（`ActionContext`），包含事件派发和当前会话受限控制能力 |
| `api.app` | App 生命周期状态及传输安全快照 |
| `api.component` | EventSource、Interpreter、Adapter、Ingress、Handler、Egress 等组件 SPI |
| `api.context` | 四级上下文、类型转换、占位符编译和 ContextCodec |
| `api.event` | 业务事件、执行域、Wrapper、lineage 与事件发射端口 |
| `api.lifecycle` | 标准生命周期及可暂停生命周期 |
| `api.runtime` | Runtime 检查点、Flow 快照和只读状态视图 |
| `api.session` | Session 标识、状态、快照、分组调度、终态传播依赖与 `CurrentSessionControl` |
| `api.system` | 只用于观测的系统事件及发布/订阅端口 |

子包是公开 API 的组成部分。插件应导入具体类型，不依赖根包通配符；新增契约时应放入职责最接近的子包，避免重新堆积到 `api` 根包。
