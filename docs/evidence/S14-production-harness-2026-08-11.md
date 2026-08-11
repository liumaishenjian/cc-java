# S14 Production Harness Stage Evidence — 2026-08-11

- Stage: S14
- Status: IN_PROGRESS / Stage Exit OPEN
- Candidate: shared working tree（未 commit）
- Baselines: R2026.03 / AUTH-SRC-2026-07-29-A / Codex rust-v0.147.0 fixed commit

## G0-G2

ADR-065 按 ADR-022 受控只读研究 Provider/retry/harness/compatibility，只提炼职责、状态、恢复与验证方法；ADR-066 冻结 Provider/Eval/OTel、stable v1/SDK/Daemon/Session、Managed/Plugin/Distribution 独立契约。授权快照 revision/license 仍 Unknown，不复制参考表达。

## G3

三个 Batch 已生产接线：Provider capability/router/typed Retry-After、Anthropic Adapter、typed Eval/OTel；stable v1 stdio+loopback daemon、SDK、Session Export/Retention/Migration/Index；Managed/Feature Gates、Plugin transaction restart recovery、release staging/checksum/SBOM/rollback。唯一 AgentRuntime/ToolExecutionPipeline 不变。

## G4 自动证据

- Anthropic protocol mock：4/4，覆盖 text/stream/tool/multi-tool/usage/cancel/429 Retry-After/5xx/context-limit；首次运行暴露 finish reason 与 Anthropic exception 未归一化，修复后 focused green。
- 真实 OpenAI-compatible：显式 gitignored 本机配置，1 PASS + multi-tool 条件 SKIP；不输出配置值或凭证。
- Unified Eval（集中审查修正）：12 个注册 seed ×5 = 60 个真实 production-harness 场景，实际覆盖 direct final、built-in Tool 多回合、Call/Result ID 精确配对、permission deny/tool failure 后模型恢复、cancel、turn limit、context preparation、canonical Session create/continue/resume、SDK Tool loop、stable initialize/run/event/唯一 terminal/idempotency。completion/墙钟/violations/modelTurns/toolCalls/stopReason 全部来自 AgentRunResult、模型收到的 ToolResult、事件或 stable envelope；60/60 完成、0 violation，artifact schema v4。真实 OpenAI 调用数 0、Anthropic protocol mock 调用数 0，均由独立 suite 提供证据；不声明双 route、120 runs、非劣、cache、usage、cost 或 L3。Artifact: `cc-java-cli/target/s14-unified-eval.json`。
- SessionIndex：10k、rebuild 33ms、list/search p95 0ms、额外 heap 0（本机测量分辨率）、SLA PASS。Artifact: `cc-java-cli/target/s14-session-index-benchmark.json`。
- Windows/WSL：Windows launcher/process/package；WSL Ubuntu shell launcher contract + 77 checksum；install/upgrade/current/LKG rollback PASS。WSL 无 JDK21，不计 Linux Java process PASS。
- Compatibility：first-v1 fixture 验证 codec/current fixture、major negotiation boundary、v0/v1 manifest coexist；无已发布 N-1，不计 L3。

## G5 Demo

`docs/demos/S14-production-harness.md` 记录 focused、真实 Provider、Eval、stable daemon、Session/Plugin recovery、Windows/WSL 与 release/compatibility 命令及负例。

## G6 对账与等级

矩阵、README、PRD、technical design、ADR、Demo、Gap、progress-state/dashboard 同步。working-tree candidate 的已验证等级为：`CLI-11/12`、`CFG-07/10/11`、`SESSION-12/13`、`PLUGIN-04`、`OBS-06`、`EVAL-03`、`DIST-03/04/05` 为 L2；`SESSION-14`、`OBS-04`、`MODEL-07/09`、`CTX-16`、`PLUGIN-05` 保持 L1。CLI/protocol compatibility 因无已发布 N-1 不升 L3，G6/Stage Exit 仍等待 implementation commit 后复验。

## Accepted Deviations

1. 无真实 Anthropic 凭证；protocol mock 不冒充真实 Provider。
2. 首个 v1 且 License 未决，无已发布 N-1 artifact；仅 L2。
3. WSL Ubuntu 无 JDK21，只验证 shell/package contract。
4. macOS、Native Image、公共更新服务、远程/TLS/账户/多租户 Daemon 延期。
5. `TOOL-17/18`、`PLUGIN-06`、`SUB-11` 保持 gap。

上述外部条件与尚未统一完成的 P1/G4-G6 继续阻止 Stage Exit；S14 保持 IN_PROGRESS / OPEN。本文件只记录当前 working-tree 证据，不是 commit-scoped 验收。
