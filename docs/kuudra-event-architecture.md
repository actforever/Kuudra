# 事件与会话架构

事件与会话的规范已经合并到 [kuudra-architecture.md](kuudra-architecture.md)。核心结论是：业务实体使用 `KuudraEvent`；Runtime 用 `RawEventWrapper`/`SessionEventWrapper` 表达执行域；Ingress 与 Egress 是唯一域转换边界；SessionManager 和 SessionCoordinator 分别负责会话事实与分组调度。
