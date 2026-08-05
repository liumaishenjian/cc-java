# S07 Context Engineering D4 预备证据

## 元数据

```text
Stage: S07 Context Engineering
Status: G3-G5 evidence slice; G6/Open; Stage Exit/Open
Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Implementation baseline: 46c288b plus uncommitted D4 evidence slice
Date: 2026-08-05
Feature IDs: LOOP-11, CTX-06/07/08/09/10/11/12/13/17/18, OBS-04
Capability Levels: unchanged
```

## G0-G2（Passed，继承）

ADR-042/043/044 已固定公开行为、受控研究边界、独立 Java 契约、Canonical/Projection 分离、C1-C4、M1-M5 和 ready-only 消费。该 D4 切片没有读取或复制授权材料，也不新增参考表达。

## G3（Passed）

`ContextPreparationService` 位于 AgentRuntime 模型请求前置 seam，Projection 不回写 Canonical Session/Journal；`LatestContextUsageCollector` 仅保存隐私安全 latest View。`S07ContextMemoryEvalTest` 是仅测试依赖的确定性 Fixture，不引入生产依赖，并以中文 Javadoc 声明其 Fake/oracle 边界。

## G4（Passed）

```text
Environment: Windows 10 Pro; Java 21; Maven Wrapper 3.9.16
Date: 2026-08-05
Command: .\mvnw.cmd -pl cc-java-core -am -Dtest=S07ContextMemoryEvalTest,AgentRuntimeContextIntegrationTest,SummaryReductionCoordinatorTest,MemoryRecallAndPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false test
Metric control/candidate: three independently authored multi-turn samples, each with three completed ordered Tool Call/Result batches and no selection exclusion; facts 3/3 → 3/3; constraints 3/3 → 3/3; completion 3/3 → 3/3; Tool orphans 0; Canonical in-memory message prefix value equality true. Stable metrics: alpha 10490 → 5270, C1 LARGE_PAYLOAD_REDUCTION ×3, 49%; bravo 10489 → 5269, C1 LARGE_PAYLOAD_REDUCTION ×3, 49%; charlie 5227 → 3608, C3 ROLLING_MEMORY, 30%; actual median estimated input-token reduction 49%. All 3/3 samples entered reduction; charlie uses a deterministic project-authored SummaryCandidate adopted through configured production revision/source coverage/UTF-8/token/protected-tail/protocol guards. The deterministic oracle separately checks fact/constraint markers; they are not configured Summary anchors.
Result: Passed
```

负例：受保护 anchor 丢失/Tool protocol contamination 的摘要候选被拒绝；stale、缺失、非法或损坏/Secret memory 均降级为空；latch 驱动的 slow future 在 Gateway 到达前不等待，迟到结果不注入已发送请求。

## G5（Passed）

`docs/demos/S07-context-memory.md` 给出可复制命令、实际执行观察、确定性 Fake 局限和负例；`docs/gap-reports/S07.md` 记录尚未实现的 UX、真实模型质量与后续 Stage 边界。

## G6（Open）

本切片尚未在实现 Commit 上完成独立审查、全量最终对账和退出接受。不得把 G3-G5 证据解释为 Capability Level 提升、G6 Passed 或 S07 Stage Exit Accepted。
