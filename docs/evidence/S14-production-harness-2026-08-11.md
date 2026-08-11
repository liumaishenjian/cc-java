# S14 Production Harness Stage Evidence — 2026-08-11

- Stage: S14
- Status: Accepted with documented deviations / Stage Exit Accepted
- Release / Commit: `dff814c1bb5a659979e007061e6d10a0a9ff6e82`
- Reference Behavior Baseline: R2026.03
- Authorized Snapshot: AUTH-SRC-2026-07-29-A
- Public Cross-check: Codex rust-v0.147.0 fixed commit
- Feature IDs: 见 `docs/feature-parity-matrix.md` S14 路线
- Levels: 150 L2 / 37 L1 / 10 L0；本次退出不整体提升等级

## G0：来源与授权 — PASSED

ADR-065 按 ADR-022 受控只读研究 Provider/retry/harness/compatibility，只提炼职责、状态、恢复与验证方法；授权快照 Revision/license 继续标记 Unknown，不复制参考表达或字节。公开 Codex 固定 tag/commit 仅用于行为与机制交叉检查，项目实现、命名、协议和测试均保持独立。

## G1：范围与可证伪目标 — PASSED

ADR-065/066 与矩阵冻结 Provider/Eval/OTel、stable v1/SDK/Daemon/Session、Managed/Plugin/Distribution 的 L1/L2 目标和 L3 门槛。无真实 Anthropic 在线证据、无已发布 N-1 artifact、WSL 无 JDK21、无 macOS/Native Image/公开更新服务证据均在实现前列为 deviation；`CFG-07` 保持 L1，`SESSION-14`、`MODEL-07/09`、`CTX-16`、`PLUGIN-05`、`OBS-04` 等保持 L1。

## G2：研究与 ADR — PASSED

ADR-065 固定参考结论采纳边界，ADR-066 固定独立 Java/CLI 契约：唯一 AgentRuntime/ToolExecutionPipeline、fallback durable fence、typed NetworkAccess/Telemetry、stable initialize/sequence/correlation/idempotency/terminal、canonical Session 事实源、Managed deny-only、Plugin global writer/journal/digest/create-only publish，以及 truthful packaging/compatibility 边界。

## G3：独立实现 — PASSED

实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 已生产接线：

- Provider capability/router/typed Retry-After、Anthropic Adapter、typed Eval/OTel；
- stable v1 stdio + loopback daemon、SDK、Session Export/Retention/Migration/Index；
- Managed/Feature Gates、Plugin transaction/restart recovery/registry migration；
- release staging/checksum/SBOM/rollback。

Core/Domain 保持框架无关，外部 Adapter 位于边缘；严格 aggregate Javadoc 在 `doclint=all`、`failOnWarnings=true` 下 0 warning。

## G4：验证 — PASSED

### Commit-scoped 标准验证

- Commit：`dff814c1bb5a659979e007061e6d10a0a9ff6e82`
- 首次 `./mvnw.cmd clean verify`：历史 `AgentRuntime` cancellation 2 秒窗口出现一次偶发 timeout；未静默忽略。
- 同一失败用例立即隔离重跑：1/1 PASS。
- 第二次完整 `./mvnw.cmd clean verify`：BUILD SUCCESS，911 tests/10 skips，0 failure/error：Domain 53/0、Core 250/0、Spring 51/2、Tools 175/8、MCP 13/0、Protocol 8/0、SDK 2/0、OTel 3/0、CLI 356/0。
- `./mvnw.cmd -DskipTests javadoc:aggregate`：BUILD SUCCESS，0 warning。
- `java scripts/ProgressDashboard.java --check`：PASS。
- `java scripts/ProgressDashboard.java --self-test`：PASS。

该偶发 timeout 已由隔离重试和第二次全量通过收敛，但作为历史稳定性信号保留，不改写成“首次即全绿”。

### 专项证据

- Unified Eval：12 seed ×5 = 60 个真实 production-harness 场景；覆盖 direct final、built-in Tool 多回合、Call/Result ID、permission/tool failure 恢复、cancel、turn limit、context preparation、canonical Session create/continue/resume、SDK Tool loop、stable initialize/run/event/唯一 terminal/idempotency；60/60 完成、0 violation，usage/cache/cost 继续标记 unknown。Artifact：`cc-java-cli/target/s14-unified-eval.json`。
- Anthropic protocol mock：4/4，覆盖 text/stream/tool/multi-tool/usage/cancel/429 Retry-After/5xx/context-limit；不冒充真实在线 Provider。
- 真实 OpenAI-compatible：1 PASS + multi-tool conditional SKIP；不输出配置或凭证。
- SessionIndex：10k 往返与 SLA artifact；普通文件 projection 因此保持 L1，不偷升。
- Windows/WSL：Windows launcher/process/package 与 WSL Ubuntu shell/checksum contract；WSL 无 JDK21，不计 Linux Java process PASS。
- Compatibility：first-v1 fixture 验证 v0/v1 coexist、codec 和 major negotiation；无已发布 N-1，不计真实升级或 L3。

## G5：可复现 Demo — PASSED

`docs/demos/S14-production-harness.md` 记录 stable/SDK/Daemon、Provider/Eval/OTel、Session lifecycle、Plugin migration、发行/rollback 命令与事实边界。Demo 明确保留真实 Anthropic、N-1、WSL JDK21、macOS/Native Image/公开更新服务缺口，并区分 Plugin migration L2 与 Marketplace/publisher identity 等未实现能力。

## G6：退出对账 — PASSED

README、AGENTS、矩阵、PRD、technical design、Evidence、Demo、Gap、progress-state/dashboard 已绑定实现 Commit 并对账。S14 Stage Exit 为 **Accepted with documented deviations**；能力等级仍以矩阵为准：`CLI-11/12`、`CFG-10/11`、`SESSION-12/13`、`PLUGIN-04`、`OBS-06`、`EVAL-03`、`DIST-03/04/05` 等为 L2，`CFG-07` 为 L1，`SESSION-14`、`MODEL-07/09`、`CTX-16`、`PLUGIN-05`、`OBS-04` 等为 L1。

## Documented Deviations / 后续条件

1. 无真实 Anthropic Provider 在线证据；protocol mock 不计真实 Provider。
2. 首个 v1 且无已发布 N-1 artifact，不具备真实升级兼容证明。
3. WSL Ubuntu 无 JDK21，只验证 launcher shell/package contract。
4. 无 macOS、Native Image、公开更新服务证据。
5. Marketplace、publisher identity/revocation/root rotation/transparency log、远程/TLS/账户/多租户 Daemon 等仍未实现。
6. 第一次 clean verify 的 cancellation timeout 保留为历史稳定性信号；隔离 1/1 与第二次完整通过是本次接受依据。

以上 deviation 不阻止已冻结 L1/L2 范围的 S14 Stage Exit，但继续阻止相关 L3 声明。下一阶段为 S15 规划，必须以真实 A/B Eval 和明确创新假设进入，不得把上述缺口倒写为已完成。
