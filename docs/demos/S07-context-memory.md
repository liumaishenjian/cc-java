# S07 Context + Memory 离线 Demo

## 前置条件与命令

Windows 10 Pro、Java 21、Maven Wrapper 3.9.16；不需要网络、Provider 配置或真实模型。

```powershell
.\mvnw.cmd -pl cc-java-core -am -Dtest=S07ContextMemoryEvalTest,AgentRuntimeContextIntegrationTest,SummaryReductionCoordinatorTest,MemoryRecallAndPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 实际观察（2026-08-05）

`S07ContextMemoryEvalTest` 运行 3 条独立编写的多回合长会话 Fixture（alpha/bravo/charlie）；每条先完成 3 个有序 Tool Call/Result 批次，事实与约束只写入可压缩历史，当前完成请求保留在 protected tail。每条比较 no-op Canonical 控制组与真实 `ContextPreparationService`：Tool Call/Result 孤儿为 0，Canonical in-memory message prefix value equality 为 true，事实与硬约束 marker 为 3/3，完成 oracle 为控制组 3/3、压缩组 3/3，且没有按结果筛选样本。稳定输出为 `alpha baseline=10490 candidate=5270 strategies=[LARGE_PAYLOAD_REDUCTION, LARGE_PAYLOAD_REDUCTION, LARGE_PAYLOAD_REDUCTION] reduction=49%`、`bravo baseline=10489 candidate=5269 strategies=[LARGE_PAYLOAD_REDUCTION, LARGE_PAYLOAD_REDUCTION, LARGE_PAYLOAD_REDUCTION] reduction=49%`、`charlie baseline=5227 candidate=3608 strategies=[ROLLING_MEMORY] reduction=30%`；三个样本均进入 reduction，实际中位数为 49%。alpha/bravo 覆盖真实 C1，charlie 的项目自有确定性 `SummaryCandidate` 通过配置的生产 revision、来源覆盖、UTF-8、Token、protected-tail 与 Tool protocol Guard 后采用 C3；确定性 oracle 另行检查事实/约束 marker，它们不是配置的 Summary anchor。

该 oracle 是确定性结构检查 Fake：只验证项目编写的 marker 是否出现在发往模型的请求中，不能代表真实模型的任务质量、幻觉率或 Provider tokenizer 计数。

## 零等待与负例

`AgentRuntimeContextIntegrationTest#slowMemoryNeverWaitsAndLateResultNeedsFreshNextTurn` 用 latch 固定顺序：未完成 Memory Future 时 Gateway 已收到第一请求且该请求没有 Memory；释放 Future 后，迟到结果仍不进入第一请求，下一回合仅重评估 fresh memory。该证据没有 wall-clock sleep 或延迟阈值。

`SummaryReductionCoordinatorTest#rejectsProtectedAnchorLossAndToolProtocolContamination` 覆盖丢失 anchor 的摘要候选拒绝；`MemoryRecallAndPrefetchTest#projectorRejectsStaleRevision`、`FileMemoryPrefetchAdapterTest#missingRootDoesNotCreateDirectoryAndReturnsUsableEmptyProjection`、`#corruptAndSecretRejectedTopicsDoNotEnterProjection` 覆盖 stale、不可用/损坏记忆降级为空。

## 边界

这不是 `/context`、`/compact`、stdio 或 TUI UX，均延期 S08；稳定质量数据集、真实 Provider 对照和长期指标延期 S14。
