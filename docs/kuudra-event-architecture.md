# Kuudra Event 与解释器语义

事件与 Session 的总体规范见 [kuudra-architecture.md](kuudra-architecture.md)。核心边界是：

- `KuudraEvent` 是不可变业务消息，RAW/SESSION 域由 Runtime wrapper 表达；
- Ingress 和 Egress 是唯一域转换边界；
- EventAdapter 对单个 Event 做无状态同步转换；
- EventInterpreter 在 RAW 域内解释跨 Event 状态，并通过 Runtime 托管的节点作用域主动发射；
- Controller handler 在 Session 内异步执行，由 Runtime work lease 决定 Session 完成。

## EventInterpreter 执行模型

Interpreter 的公共签名是：

```java
void interpret(KuudraEvent event, EventInterpreterContext context)
```

`EventInterpreterContext` 的有效作用域是 `ability/revision/node`，提供：

- 已解析的节点 arguments 和当前 Event/Ability/Global context；
- codec-backed 私有 state；
- 保存不可变 `KuudraEvent` 的命名 buffer；
- 替换式命名 timer 与取消操作；
- 单原因 `emit(event)` 和聚合原因 `emit(event, causes)`。

Runtime 串行执行同一节点的输入和 timer callback。不同节点是否并行仍受共享 Resource 的
`allowParallel` 约束。Timer 等待不占用 worker、Session 或 Session lease。

聚合 emit 会合并全部原因 Event 的 parent Event、parent Session 和 lineage hops。Runtime
暂停 DATA Ability、暂停或禁用 Ability/Resource、注销图或关闭时，会取消 timer、清空
state/buffer 并撤销旧 context；恢复后不会补发暂停前的窗口结果。
