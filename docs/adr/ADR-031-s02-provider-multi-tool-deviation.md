# ADR-031：S02 真实 Provider 同回合多 Tool 偏差

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `MODEL-05`、`LOOP-03`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: Provider 结果为 `Observed`，本项目处置为 `Documented`

## 背景

S02 必须区分两个问题：Java Adapter 是否会丢失同一 Assistant Turn 中的多个 Tool Call，
以及维护者当前配置的真实 Provider/模型是否会生成这种响应。两者不能用同一个自然语言
Prompt 的成功或失败替代。

## 证据

2026-07-29 再次显式运行真实 Provider 双 Tool Spike。请求声明两个独立 Tool Definition，
并要求在同一 Assistant Turn 分别调用一次。Provider 返回规范的 `tool_calls` 终态，
但只包含 `record_first_probe`，没有生成 `record_second_probe`。

同一版本的本机 OpenAI-compatible SSE Contract Fixture 会把两个 Tool Call 的 ID、名称和
JSON 参数分散在多个 Chunk 中；Spring AI Adapter 最终无损返回两个有序调用。独立 Adapter
单元测试也证明两个调用的顺序、ID 和参数保持。因此当前负例发生在 Provider/模型生成侧，
不是 Java 聚合侧。

## 决策

1. 接受“当前配置的真实 Provider/模型不保证同回合生成多个 Tool Call”为 S02 明确偏差；
2. 不把两轮各一个 Tool Call 冒充同回合多个调用，也不放宽真实负例断言；
3. `MODEL-05 L2` 由真实单 Tool 流和本机真实 HTTP/SSE 双 Tool Contract 共同证明；
4. Runtime 继续严格支持一回合多个调用，并保持 Assistant Message 只追加一次、
   Call/Result ID 精确配对和整批完成后再请求下一回合；
5. Provider、模型名或兼容端点改变时必须重新运行 opt-in 双 Tool Spike；
6. S14 Capability Detection 应把“可生成并行 Tool Call”作为 Provider/Model 能力，
   不能假设所有 OpenAI-compatible 端点都支持。

## 不接受的替代方案

- 不通过重复 Prompt 直到偶然成功来掩盖能力不稳定；
- 不在 Adapter 中凭空拆分或合成第二个 Tool Call；
- 不为了当前 Provider 限制删除 Runtime 的成熟多 Tool 协议；
- 不把真实 Provider 负例加入普通离线 CI。
