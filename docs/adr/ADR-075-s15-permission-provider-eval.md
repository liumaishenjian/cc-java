# ADR-075：S15 Permission Provider Eval 与隐私边界

- Status: Accepted
- Date: 2026-08-18
- Stage: S15 Independent Innovation
- Features: `PERM-05`、`PLAN-01`
- Depends on: ADR-072、ADR-073、ADR-074

## Decision

PERM-05 的评测分为默认离线 Eval 与显式 opt-in real-provider suite。默认 suite 使用注册 Seed 和确定性
`ApprovalReviewGateway`，覆盖 safe read/web allow、untrusted network、prompt injection、malformed、
timeout、exception、deny/allow、latency/cost counters 以及 circuit/stop。报告只允许 typed decision、
固定 failure kind、计数、有界延迟和 usage-derived cost；不得记录 Prompt、模型输出、原始 Tool args、
文件正文、Secret 或自由文本理由。

真实 Provider suite 必须由明确环境变量/凭证 opt-in。缺少配置时返回结构化 `SKIPPED`，不把跳过伪装成
通过，也不影响普通 CI；断言只检查 typed decision、fail-closed 与预先登记的安全/延迟/成本阈值，不断言
固定自然语言。真实 Provider 结果不得写入普通仓库证据，凭证不得进入日志或报告。

## Evidence

- `AutoReviewProviderEvalTest`：注册 Seed 离线运行，验证隐私安全报告与安全断言。
- `AutoReviewEvalReport`：稳定、无正文 JSON 聚合结构。
- `S15AutoReviewRealProviderEvalTest`：仅 `CC_JAVA_REAL_PROVIDER_EVAL=true` 且 profile/endpoint/key/model 齐全时创建真实 Client；否则输出 `SKIPPED/NOT_RUN` 并成功结束。
- 真实 Provider 只断言 typed decision、安全误放行阈值、失败关闭、延迟和 cost counters，不断言自然语言；误放行率、语义上下文质量与跨 Provider A/B 仍是后续证据，不能据此将 `PERM-05` 提升至 L2。
