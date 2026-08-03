# ADR-037：隐私安全的模型失败摘要

- Status: Accepted
- Date: 2026-08-01
- Stage: S02/S04 Accepted 后维护切片
- Capability IDs: `LOOP-09` L2 → L2、`CLI-03` L2 → L2、`CLI-10` L2 → L2、`OBS-05` L2 → L2
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 失败恢复机制为 `Observed / Inferred`；本项目摘要协议为 `Documented`

## 背景

真实 Provider 的模型列表接口可以正常返回，而 Chat 接口连续返回 503 时，Runtime 按既有
策略重试三次并产生 `MODEL_RETRY_EXHAUSTED`。该终态对程序是准确的，但用户无法区分服务
不可用、限流、鉴权、网络、超时和无效响应。直接展示 SDK Exception 或 Provider 响应又会
泄露端点、Prompt、响应正文、Request ID 或凭证。

## 决策

新增 Provider-neutral 的 `ModelFailureCategory`、粗粒度 `ModelHttpStatusClass` 和
`ModelFailureSummary`。摘要只允许固定枚举、1～100 的尝试次数和是否收到输出；禁止任意
文本。Adapter 根据状态码/异常类型创建摘要，Core 重试层只累计实际尝试数，不改变重试
策略。

分类包括：Provider 不可用、限流、超时、冲突、鉴权失败、非法请求、网络错误、不完整流、
无效响应和无法细分的 Provider 错误。HTTP 只暴露 `4xx/5xx` 状态组，不暴露精确 URL、
Header、Body 或 Request ID。

摘要作为 `AgentRunResult` 唯一终态的一部分，经 `RunFinished → stdio run.failed` 投影到
TUI。Print stderr 和 TUI 使用本地固定文案，例如：

```text
模型服务暂时不可用（5xx），已尝试 3 次；请稍后重试
```

原有 StopReason、退出码和权威终态行保持不变。意外 Runtime 异常固定为
`stopReason=internal_error`，不伪造模型失败摘要。

## 隐私白名单

允许进入 Surface：

- 固定 failure category；
- `4xx/5xx`；
- 有界 attempts；
- receivedOutput。

禁止进入 Surface：API Key、Endpoint、Prompt、模型原始输出、响应正文、异常 message、
Request ID、Header 和底层 SDK 类型。

## 验证

- 本地 503 Fixture 连续失败三次后产生 `provider_unavailable/5xx/3`；
- 429、鉴权、网络、超时与不完整流由 Adapter 分类测试覆盖；
- stdio/TUI 严格拒绝额外自由文本字段；
- Print stdout 保持 Assistant-only，安全摘要只写 stderr；
- Secret、URL、Prompt 哨兵不进入终态 payload 或 UI。

## 延后内容

S14 再加入 Provider-specific Metrics、状态码统计、Trace Export 与完整错误治理；本 ADR
不提升 Capability Level，也不改变 S05 Permission Pipeline 的下一阶段顺序。
