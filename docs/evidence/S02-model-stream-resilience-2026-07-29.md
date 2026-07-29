# S02 模型流健壮性证据（2026-07-29）

## 范围

- Stage: `S02 Model + Streaming CLI`
- Feature IDs: `LOOP-09`、`LOOP-10`、`MODEL-05`、`MODEL-10`
- Current → Target:
  `LOOP-09 L0 → L2`、`LOOP-10 L1 → L2`、`MODEL-05 L1 → L2`；
  `MODEL-10` 保持 L0（S14 全局 Provider Policy）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification:
  授权材料机制为 `Observed / Inferred`；
  本项目实现、Fixture 和测试为 `Observed`

## 机制与独立设计

受控研究只提炼了模型流、Tool Use 聚合、错误恢复、取消和执行阶段分离等机制。
本项目通过 ADR-027 独立定义：

- Spring AI Adapter 分类失败并验证完整聚合结果；
- Core `RetryingModelGateway` 决定次数、退避和取消；
- 第一个可见 Delta 后禁止自动重试；
- 不完整流不写入 Session、不执行 Tool；
- `length` 映射为 `OUTPUT_LIMIT_REACHED`，S02 不插入隐藏续写 Prompt。

没有参考函数体、Prompt、错误文案、私有类型、常量或 Fixture 进入仓库。

## 离线与本机协议证据

```powershell
.\mvnw.cmd -pl cc-java-cli -am test
npm.cmd run check
```

覆盖：

- 一个 Assistant Turn 的两个 Tool Call 保持顺序、ID、名称和 JSON 参数；
- Finish Reason 与 Tool Call 不一致时拒绝整批；
- 瞬时错误第三次成功和三次耗尽；
- 退避期间取消；
- Delta 后失败不重试并映射不完整流；
- EOF 缺少支持的 Finish Reason；
- `length` 的 Adapter 映射与 Runtime 明确停止；
- Print/TUI 的稳定输出长度诊断。

本机 OpenAI-compatible HTTP/SSE Fixture 进一步从真实 `OpenAiChatModel` 边界验证：

1. 两个 Tool Call 的参数分别跨多个 SSE Chunk 后仍形成两个有序调用；
2. 前两次 HTTP 429、第三次 SSE 成功时有界重试；
3. 无 Finish Reason 的 EOF 映射为 `INCOMPLETE_STREAM`；
4. `length` 保持到 Runtime Policy。

Fixture 只监听 `127.0.0.1` 临时端口，使用固定假 Key，不读取本地真实配置。

## 真实 Provider 对照

显式真实 Provider Spike 的文本、单 Tool Call、Usage 和 Finish Reason 继续通过。
新增的同一 Assistant Turn 双 Tool Call 场景中，真实中转模型只返回
`record_first_probe`，没有生成第二个调用。

这项结果分类为 `Observed Gap`：

- 本机 SSE Fixture 证明 Spring AI/Adapter 能保留两个已生成的调用；
- 当前证据不能证明真实 Provider/模型支持同回合多个 Tool Call；
- 不放宽测试断言，也不把两轮各一个 Tool Call 冒充同回合两个调用；
- 该 opt-in 场景由 `ccjava.real-provider-multi-tool=true` 单独启用。

真实配置、Base URL、API Key、响应文本和底层响应体均未写入证据。

## 等级结论

- `LOOP-09 → L2`：有界次数、取消、Deadline、Delta 去重边界和唯一终态均有测试；
- `LOOP-10 → L2`：`length` 能明确停止且截断内容不进入规范历史；
- `MODEL-05 → L2`：真实 Spring AI/OpenAI SSE 路径证明跨 Chunk 双 Tool 聚合；
- `MODEL-10` 保持 L0：`Retry-After`、自适应退避、熔断和全局协调仍属于 S14。

S02 Stage Exit 继续为 Open。真实 Provider 同回合多 Tool 兼容性以及 Windows
TTY/进程生命周期负例仍需关闭或形成明确 Accepted Deviation。
