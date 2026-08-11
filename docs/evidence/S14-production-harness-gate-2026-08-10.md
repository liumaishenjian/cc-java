# S14 Production Harness G0-G2 Gate（2026-08-10）

- Stage: S14 Production Harness
- Status: In Progress
- Release / Commit: working-tree candidate（禁止自行 commit）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public snapshot: OpenAI Codex `rust-v0.147.0` / `be6e8eac029b183056b7e4402879f15d2c85f61b`

## G0

S13 Accepted baseline 已核验，任务启动时工作树干净。完整阅读 AGENTS.md 强制资料与所列 ADR；在仓库外只读研究授权快照，并复核 Codex fixed tag/commit。研究只保留职责、状态、不变量、失败恢复和验证方法，授权 Revision/License/rights 继续 Unknown，参考字节不进入仓库。

## G1

范围、目标等级、延期项与真实证据门槛由 ADR-065 冻结。目标 L2 与 L1 例外严格按任务批准范围；L3 只在真实双 Provider/双平台/N-1 artifact 条件满足时考虑。三 Batch 为 Provider/Observability、Protocol/Session、Governance/Distribution。

## G2

ADR-065 固定双源采纳边界；ADR-066 固定独立 Java 模块、Provider Router、NetworkAccessPort、OTel、stable v1、SDK/Daemon、Session 生命周期、Managed Policy、Plugin Recovery/Signature Port 与 Distribution 契约。Domain/Core 保持 framework-free，唯一 AgentRuntime/ToolExecutionPipeline 不变。

## G3-G6

待实际实现、测试、Demo、量化、文档和看板完成后更新。任何真实凭证或平台缺失记为 SKIP/UNSUPPORTED，不计通过；Capability 只按最终证据提升。
