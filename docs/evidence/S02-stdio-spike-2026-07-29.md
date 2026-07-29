# S02 Java Fake stdio Spike 证据

- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Feature IDs: `CLI-11`、`CLI-06`、`LOOP-08`
- Current → Target: `L0 → L1`（本证据只覆盖 Spike，不提升 Capability Level）
- Public Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: `Observed`（本项目测试）+ `Documented`（Jackson 官方资料）

## 要证伪的问题

Java Headless 进程能否在不依赖真实 Provider、Ink 或网络的条件下，通过有界 UTF-8
NDJSON 边界完成命令接收、事件串行化、异步取消和确定性退出；同时不把跨进程协议、
取消权威或 Agent 状态机放进未来 TUI。

## 独立实现

本 Spike 在 `cc-java-cli` 的架构边缘增加：

- 严格 Envelope Codec：`initialize`、`run.start`、`run.cancel`、`shutdown`；
- 64 KiB 有界 UTF-8 单行读取器，拒绝畸形 UTF-8、重复字段和超限行；
- 容量 256、入队等待 1 秒的单 Writer 事件出口；
- 连接内单调命令/事件序列，以及每个 Run 的 `started → exactly one terminal` 不变量；
- 测试专用异步 Fake Handler，用来验证取消而不伪装成真实 `AgentRuntime`；
- Windows 子 Java 进程测试，验证 stdout 只含协议事件、正常退出且没有存活的已捕获后代进程。

64 KiB、256 和 1 秒只是 Spike 参数，不是 S14 稳定协议承诺。

JSON 实现使用 Jackson 3.1.0 LTS 候选版本；该依赖只存在于 CLI 适配层，不进入
Domain/Core。

## 正例与负例

| 场景 | 可观察结果 |
| --- | --- |
| 合法命令序列 | 事件 sequence 单调递增 |
| 异步 `run.start` 后发送 `run.cancel` | 一个 `run.cancelled` 终态 |
| 第二个终态或终态后的 Delta | Event Emitter 确定性拒绝 |
| 重复 JSON 字段、畸形 JSON、未知主版本/类型 | `protocol.error` |
| 非法 UTF-8、超限行 | 拒绝该行；超限内容消费至换行后可继续读取 |
| Client 停止读取 | 有界队列在有限等待后失败，不无限占用内存 |
| Windows 子进程 `shutdown` | 退出码 0、stderr 为空、无存活的已捕获后代进程 |

测试编写过程中，跨进程用例曾把格式化多行 JSON 发送到 stdin。Server 按“一行一个
完整 JSON”的契约正确返回 `protocol.error`；修复的是测试发送端，不是放宽协议。
这条负结果证明行边界确实被执行。

## 验证

```text
.\mvnw.cmd -pl cc-java-cli -am clean test
```

结果：

- `cc-java-core`：23/23；
- `cc-java-cli`：12/12；
- Reactor：6/6 `SUCCESS`；
- 总计：35/35，0 Failure，0 Error，0 Skipped。

## 尚未证明

- 当前 Handler 是测试 Fake，未接入真实 `AgentRuntime`、Provider 或流式 Tool Call；
- 尚未建立 `cc-java-tui`，未验证 React/Ink、中文宽字符、Resize 和真实 Ctrl+C；
- 尚未验证 stderr 洪水、TUI 崩溃、Java 崩溃和活动 Run 上的 EOF 清理；
- `CLI-11`、`CLI-06`、`LOOP-08` 继续保持 L0，G2-G6 继续保持 Open；
- 精确协议 Schema、错误码和上限要等 Spike B 与 Provider Spike 后再由 G2 固定。
