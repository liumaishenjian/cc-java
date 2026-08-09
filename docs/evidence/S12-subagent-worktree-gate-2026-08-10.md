# S12 Sub-Agent + Worktree G0-G2 Gate 证据

## 元数据

```text
Stage: S12 Sub-Agent + Worktree
Status: IN PROGRESS（G0-G2 PASSED；G3-G6 OPEN）
Release / Commit: N/A - documentation scope freeze; working tree only
Reference Behavior Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Public Source Snapshot: OpenAI Codex rust-v0.147.0 / be6e8eac029b183056b7e4402879f15d2c85f61b
Feature IDs: SUB-01..10, CTX-15, HOOK-08, HOOK-11, TOOL-15
Owner: 项目维护者
Date: 2026-08-10
```

## Gate 状态

| Gate | 状态 | 证据/退出条件 |
| --- | --- | --- |
| G0 | PASSED | ADR-061 完成授权快照 + 固定 Codex tag/commit 双源受控研究、来源边界、Unknown 与非复制声明 |
| G1 | PASSED | ADR-062 冻结全部 S12 Feature 的 L1/L2 退出目标、延期、数值上限与可证伪验收 |
| G2 | PASSED | ADR-061/062 冻结 Scope/定义/任务/预算/并发/后台/取消/Hook/并行 Tool/Worktree 独立契约 |
| G3 | CANDIDATE PASSED | Phase 3 整体修正补齐 Project Trust、恢复 registry、Stop Context 父下一回合投影、AgentRuntime batch 并行及真实 child composition |
| G4 | CANDIDATE PASSED | Java 安全矩阵、真实 Git Worktree、TOOL-15 Runtime 路径与六 seed × 五次/策略真实 Supervisor/AgentRuntime Eval 通过 |
| G5 | CANDIDATE PASSED | Demo 已改为真实生产链路与实测结果，不再使用公式生成指标 |
| G6 | OPEN | dirty worktree 无 implementation Commit；必须由协调者提交后执行 commit-scoped 全复验 |

## G0：来源与授权

### AUTH-SRC-2026-07-29-A

只读路径：`G:\AI Cloud\claude-code-main`。当前目录含根 `README.md` 与 `.idea/**` 5 个文件这 6 个本机 wrapper（合计 3,421 bytes）；排除后授权 payload 恰为登记的 1,902 files、30,382,832 bytes。登记 Tree SHA-256 仍记为 `5f820b7a05b704a5e49cfd7747189af265def28a73227889c3ff028aeab79301`，但本轮没有重新计算或宣称 re-verify Tree hash，也不改变 snapshot identity；准确 Revision、版本、License、权利人与再发布权为 `Unknown`。研究覆盖 Agent definition、runtime reuse、fork/isolated context、Tool/Permission、model/turn override、background task、cancel/cleanup、Sub-Agent Hook 与 Git Worktree 抽象机制。

### OpenAI Codex rust-v0.147.0

官方仓库 tag 重新核验：`refs/tags/rust-v0.147.0` 为 annotated tag object `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d`，其 target Commit 为 `be6e8eac029b183056b7e4402879f15d2c85f61b`。本地只读 clone `target/research/codex-0.147.0` 的 detached `HEAD` 和 exact tag 均一致。研究覆盖 role/config layering、同 Runtime thread spawn、parent/child identity、fork context、model/effort override、shared active capacity、wait/interrupt/status 与恢复测试；未发现与本 Stage Git Worktree 主链等价的公开实现，因此 Worktree 只由授权快照与本项目安全需求解释。

### 结论分类

- `Observed`：definition/run 分离、独立 context/tool/permission state、父子 identity、model/limit override、显式 status/wait/cancel、后台清理与 Sub-Agent lifecycle。
- `Inferred`：cc-java 的纯收窄 Scope、父预算 reservation、status-first terminal、保守 Worktree cleanup 与结果摘要边界。
- `Unknown`：参考调度公平性、摘要质量、预算计费口径、跨重启 background 与完整跨平台 Worktree 原子性。
- `Documented`：ADR-061/062 中的 Java/TypeScript 契约、数值 ceiling、实施批次和 Eval 门槛均为 cc-java 独立设计。

## G1：范围与目标

目标表以 ADR-062 为权威：`SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 目标 L2；`SUB-06`、`HOOK-11` 目标 L1。`SUB-11`、远程/跨重启 worker、稳定外部 task protocol、模型 Prompt/Agent Hook、自动 merge/push、OS Sandbox 全部延期。本文档冻结目标但不提升当前 L0。

## G2：架构冻结

### 核心链路

```text
delegate_agent Tool
→ unique Tool Pipeline
→ AgentSupervisor
→ definition snapshot + budget reservation + shared permit
→ optional Worktree lease
→ ChildRuntimeScopeFactory
→ same AgentRuntime over independent Session/Context/Permission state
→ terminal CAS
→ bounded ChildTaskReport
→ parent Tool Result / background notification
→ reverse idempotent cleanup
```

### 实施批次

| Batch | 交付 | 核心证伪 |
| --- | --- | --- |
| A | Scope、definition、单前台委托、summary、HOOK-08、journal recovery | 第二套 Loop=0；mutable state 串扰=0；每 Tool 走 Pipeline |
| B | 公平有界并发、后台 inspect/wait/cancel/notify、预算 reservation、TOOL-15、stdio/TUI | active≤4、queue≤32、depth≤2；超卖/orphan=0；并行读协议完整 |
| C | Git Worktree lease、独立 root composition、keep/remove、集成 Eval | 自动测试证明 identity/registration、active/ignored/dirty/new commit 保留与 clean remove 无泄漏；reparse/fault/cancel recovery 仍是 gap；多 Agent质量与收益达到门槛 |

### 安全边界

- Worktree 是 Git working copy，不是 Sandbox；S13 File/Process/Network/Secret 隔离保持未实现。
- 后台任务仍由父 Session 拥有；shutdown/取消必须收敛，不允许 detached orphan。
- Agent definition、Hook、仓库、模型输出和子摘要均不可信，不能扩大 Tool、Permission、Workspace、Budget 或取消所有权。
- 无自动 commit/merge/push；无远端写入；无参考私有格式兼容。

## G4/G5 预注册验证

G4 必须执行 ADR-062 的隔离、权限、协议、预算、并发、后台、取消、Worktree、安全和 6-task 多 Agent Eval。G5 Demo skeleton 见 `docs/demos/S12-subagent-worktree.md`；没有实际运行前保持 Planned，不能作为能力证据。

## 当前未决问题

1. `ChildTask` 聚合状态落入现有 S06 JSONL 还是独立内部 task journal 的最小 schema，需要在 Batch A 测试先行时以恢复不变量收敛；无论选择哪种都不得形成 S14 稳定协议声明。
2. 父 Context 的 child report 是由 deterministic runtime summary 还是零 Tool model summarizer生成，需要 Eval 比较；默认先用 deterministic bounded report，模型摘要失败不得阻塞 terminal。
3. Worktree ancestor symlink/reparse、Git fault/timeout 进程树清理，以及 Windows remove/branch lock 与取消竞态，当前均缺少可移植的自动故障注入；本证据不据此声称已验证 recovery，无法证明 clean 时一律 preserve。
4. 当前单 Provider 只允许已配置同一模型或受信 alias；完整模型 catalog/cost governance 仍属 S14。
5. `HOOK-11` 的模型决策面保持延期；S12 L1 只冻结 host-trusted narrowing seam。

## Phase 3 整体 Review 校正

整体 Review 证明先前 focused 10/10 与 TUI 129/129 只覆盖浅层路径，不能支撑候选 L2。尤其 `S12MultiAgentEvalTest` 仅按公式生成 wall/token/completed，不驱动任何 production Supervisor/Runtime/Tool/Worktree，因此 100% 完成率、40% 墙钟改善与 10% Token 增加全部从证据中撤销。

本轮在上述修正上继续接入 Extension/S08 Project Trust、no-replay 恢复 registry、Stop Hook additional context 父下一回合一次性投影，并确认 TOOL-15 位于完整 AgentRuntime batch path。旧公式 Eval 已重写为真实 Supervisor/AgentRuntime child runs；协调者在最终工作树独立执行标准 `.\mvnw.cmd clean verify`，连续通过 838 tests/21 skips、0 failure/error（Domain 53/0、Core 238/0、Spring 45/2、Tools 162/8、MCP 13/0、CLI 327/11），TUI check 133/133、launcher 59 assertions、Dashboard generate/check/self-test 与 `git diff --check` 均通过。`SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 因此为 L2 candidate，`SUB-06/HOOK-11` 为冻结的 L1 candidate；commit-scoped G6 仍 Open。

## 结论

S12 G0-G2 Passed，G3-G5 candidate passed；矩阵记录 ADR-062 冻结的 L2/L1 candidate。G6 与 Stage Exit 必须等待真实 implementation Commit 后的 commit-scoped acceptance，当前仍不得宣称 S12 Accepted；Worktree 也不得描述成 OS Sandbox。