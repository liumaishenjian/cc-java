# S15 Batch 4 自适应预算与 Tool 失败治理证据

- Date: 2026-08-21
- Stage: S15 Independent Innovation（仍 `IN_PROGRESS / OPEN`）
- Feature IDs: `LOOP-07`、`TOOL-10`、`TOOL-13`、`TOOL-18`、`PERM-05`、`OBS-04`
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Implementation State: uncommitted working tree；本批不 commit/push
- Capability Levels: 无变化

## 实现与可证伪结果

| 行为 | 证据 | 结果 |
| --- | --- | --- |
| 普通交互超过历史 16/32 且持续进展 | `AgentRuntimeTest.adaptiveInteractiveBudgetCompletesAfterMoreThanThirtyTwoProgressingToolCalls` | 34 Tool / 35 Model Turns 后完成，发布 `PROGRESS_EXTENDED` |
| 显式 cap 不放宽 | `AgentRuntimeTest.explicitToolCapStillTerminatesExactBatch` | Tool cap=1 精确终止 |
| adaptive 仍有绝对界限 | `AgentRuntimeTest.adaptiveAbsoluteCeilingStillTerminatesWithExplicitReason` | 达到 ceiling 后 `TURN_LIMIT_REACHED / ABSOLUTE_LIMIT` |
| 相同 403 不盲重复；changed query 可执行 | `WebSearchPipelineTest.repeatedIdenticalForbiddenIsRedirectedBeforeExecutionButChangedQueryIsAllowed` | Adapter 执行 2 次而非 3 次；重复调用 `REPEATED_FAILURE` |
| 403 非重试并安全细分 | `WebSearchToolTest.forbiddenIsTypedNonRetryableAndDoesNotRetry` | HTTP hit=1，`HTTP_FORBIDDEN/retryable=false` |
| 429/5xx bounded retry/backoff | `WebSearchToolTest.rateLimitAndServerFailuresUseBoundedAdapterRetryWithDeterministicSleeper` | 每类 3 attempts / 2 deterministic delays 后成功 |
| nonzero process failure | `RunCommandToolTest`、`S04CodingLoopFixtureTest` | `FAILURE/PROCESS_EXIT`，有界 stdout/stderr 保留，修改后同命令成功允许 |
| taxonomy 不由 prose 猜测 | `ToolErrorTaxonomyTest` | `403` 字样不能改变 PROCESS_EXIT category |
| stdio/TUI typed projection | `RuntimeStdioCommandHandlerTest`、TUI protocol/state/tool tests | category/retryable 与 budget event 通过 |
| Session round-trip | `FileSessionStoreTest` | 新 category/retryable 持久化，旧记录保守兼容 |
| AutoReview 边界 | `AutoReviewCoordinatorTest`、Pipeline/Headless 既有安全矩阵 | Hard Denial/deny/source/trust 仍早于 fast path；repeated gate 位于 AutoReview 前 |

## 验证命令与准确结果

1. `./mvnw.cmd clean verify`
   - 首次：因 S04 历史 fixture 仍把预期测试失败断言为 Tool SUCCESS 而失败；生产结果已经正确为 `PROCESS_EXIT`。
   - 修正 fixture 后第二次：**1,117 tests / 35 skips / 0 failures / 0 errors，BUILD SUCCESS**。
2. 最终 focused Java regression（13 个测试类）：**157 tests / 0 skips / 0 failures / 0 errors**。
3. `npm --prefix cc-java-tui run build`：通过。
4. affected TUI `protocol/state/tool-activity`：最终相关运行 **44/44** 与 **17/17** 通过。
5. `npm --prefix cc-java-tui run check`：build 通过；测试 **218/219**，唯一失败为此前已有的
   `/connect 实时显示脱敏 Key，再通过一次性 stdin 保存` Ink 等待超时；隔离复跑仍失败，和 Batch 4
   typed failure/budget 路径无关。本批没有掩盖或修改该既有问题。
6. Dashboard generate / `--check` / `--self-test`：通过。
7. `git diff --check`：通过（仅 Git 的 LF→CRLF 提示）。
8. `generate_henan_weather_xlsx.py`：保持未跟踪且未触碰；未 commit、未 push。

## 来源分类与剩余差距

授权研究的 `Observed / Inferred / Unknown` 见 ADR-079 与授权基线第 17 节。真实站点 403 原因常不可观察，
本项目只在受信响应头存在时细分；WebFetch 尚未作为独立生产 Tool 接入，当前生产 Web 能力仍是
`web_search`。尚无真实多站点 403/429/5xx、真实 Provider 策略质量、跨 Session failure cache 或 L4 A/B
证据，因此不提升 `LOOP-07`、`TOOL-10/13/18`、`PERM-05` 或 `OBS-04` 等级，S15 继续 OPEN。
