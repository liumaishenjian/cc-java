# ADR-019（历史）：S07 采纳渐进式 Context Reduction 机制

- Status: Superseded
- Superseded By: [ADR-020](./ADR-020-quarantine-unverified-reference-source.md)
- Date: 2026-07-28
- Stage: S07 Context Engineering（实现尚未开始）
- Capability IDs: `CTX-05`～`CTX-12`、`CTX-16`～`CTX-18`
- Current → Exit Target: 当前均为 L0；按矩阵中的跨阶段检查点推进，S07 目标为 L2
- Reference Behavior Baseline: `R2026.03`
- Previous Snapshot Classification: `AUTH-SRC-2026-03-31-A`
- Decision Scope: 采纳研究结论和验证路径，不提升任何 Capability Level

> 本 ADR 的结论曾依赖当前无法核验来源与授权范围的材料，现仅作为决策历史保留，
> 不再是 S07 的活动设计输入，也不得用于实现或测试。S07 启动时必须依据公开来源和
> 独立场景重新研究、重新决策；具体隔离范围见 ADR-020。

## 背景

“上下文压缩”不能只实现成一个达到 Token 阈值就把全部历史摘要一次的函数。授权源码研究
显示，成熟 Harness 同时处理 Tool Payload、旧 Tool Result、滚动记忆、完整摘要和
Overflow Recovery，但当前材料没有证明存在一个官方命名的固定“四层串行算法”。

本 ADR 是 [ADR-018](./ADR-018-authorized-reference-study.md)要求的独立“参考结论采纳
ADR”。它把参考机制转换为 `cc-java` 可独立解释的行为、不变量、Java 边界和证伪实验。
后续 S07 设计 ADR 可以补充实现选择，但不能用参考源码的具体表达替代本决策。

## 要独立重现的行为

给定一个持续增长的 Canonical Transcript：

1. Runtime 根据模型容量构造 Model Context Projection，而不破坏规范历史；
2. 先处理单批 Tool Payload 和低价值旧 Tool Result，未到阈值时不做高成本摘要；
3. 到达阈值时优先使用有效 Rolling Memory 与协议安全近期尾部；
4. Memory 不可用或结果仍超阈值时进入 Full Summary；
5. 任何淘汰都不能产生孤立 Tool Call/Result；
6. 压缩失败不能提交边界，同一次 Overflow 只能有一次 compact-and-retry；
7. 手动 Compact 可以使用不同触发策略和保留指令，但共享协议与提交不变量；
8. 是否成功必须根据最终 Projection 的真实 Usage 和任务保持率判断。

这些行为由本项目 PRD 表达，并由本项目 Fixture、故障注入和长会话 Eval 验证。参考源码
不是测试 Oracle。

## 采纳的参考结论

| 结论 | 分类 | 采纳方式 |
| --- | --- | --- |
| Tool 输出存在单批限流、外置或占位路径 | `Observed` | S03-S04 先建单结果信封，S06 持久化决策，S07 才做聚合预算 |
| 旧 Tool Result 可按价值和近期工作集选择性减压 | `Observed` | 独立 `StaleToolResultReducer`，要求幂等和 Call ID 保留 |
| Rolling Session Memory 带覆盖边界和近期完整尾部 | `Observed` | 独立 `RollingMemoryReducer`，只在安全 Protocol Round 推进边界 |
| Full Summary 与自动 Overflow 恢复路径存在 | `Observed` | 独立 `ConversationSummaryReducer` 和有界 `RecoveryDecision` |
| 手动 Compact 与自动触发是不同入口 | `Observed` | 共用 `ReductionRequest/Outcome`，允许不同触发顺序 |
| 多种机制应组成按条件选择的渐进式决策图 | `Inferred` | 由 `ContextPreparationService` 编排，不建立固定四次调用 |
| Canonical Transcript 与可缩减 Projection 必须分离 | `Inferred` | S06 先持久化协议和投影元数据，S07 只改变发送给模型的投影 |
| Memory 无效时必须确定性回退 Full Summary | `Inferred` | 用缺失、空、过期、边界丢失和仍超阈值五类反例验证 |

详细来源结论和 Unknown 见
[当前隔离登记](../reference-baselines/R2026.03-unverified-source.md)。

## `cc-java` 的独立设计

Core 计划使用以下项目自有契约：

```text
ContextPreparationService
├─ ToolPayloadReducer
├─ StaleToolResultReducer
├─ RollingMemoryReducer
└─ ConversationSummaryReducer
```

配套值对象为 `ContextCapacity`、`ContextUsageSnapshot`、`ProtocolRound`、
`ReductionRequest`、`ReductionOutcome`、`CompactionBoundary` 和
`RecoveryDecision`。这些名称和边界来自本项目需求；它们不是参考源码类型的翻译。

Provider Context Editing、Prompt Cache Hint 等能力只允许作为 S14 Model Adapter 优化，
不得成为 Domain/Core 正确性的前提。

## 不采纳的内容

- 不采纳参考源码的类型名、文件布局、函数体、Prompt、错误文案或常量；
- 不把“四层”当作上游正式术语或每次请求固定执行的四步算法；
- 不采用参考项目的持久化 Schema、摘要文本格式或内部事件格式；
- 不在 S07 提前实现外部 Hook；Core 只发内部事件，S09 再定义可配置 Hook；
- 不用某个 Provider 的原生 Context Editing 取代通用 Java 路径；
- 不从缺失的增量折叠或反应式压缩组件推测具体算法。

## 可证伪验证

S07 至少需要以下独立测试和度量：

1. 每种 Reducer 的正例、空操作、幂等和取消/失败路径；
2. 任意淘汰后孤立 Tool Call/Result 数为 0；
3. Memory 缺失、空、过期、边界无效、仍超阈值时全部回退 Full Summary；
4. Summary 为空、失败、取消或返回 Tool Call 时不提交 `CompactionBoundary`；
5. 同一 Overflow 第二次失败明确停止，不递归压缩；
6. resume 前后相同 Transcript 产生兼容 Projection；
7. 手动 Compact 的保留指令在最终 Projection 中仍成立；
8. 使用长会话 Fixture 度量事实/约束保持率、任务完成率、Token 降幅和压缩次数；
9. S08 加入分层 Instructions 后重跑状态重注入和 Usage 对账。

阈值、摘要模型和 Reducer 顺序都必须通过测试或 Eval 决定，不能复制参考常量。

## 历史后续说明（2026-08-05）

本 ADR 保持 `Superseded`，不得重新成为实现依据。活动设计与当前离线实现由
[ADR-042](./ADR-042-s07-authorized-context-memory-study.md)、
[ADR-043](./ADR-043-s07-context-projection-compaction.md)和
[ADR-044](./ADR-044-s07-file-memory-prefetch.md)定义；本次 C3/C4 只用独立 Domain/Core 契约、
Scripted Fake 和项目自有边界实现，不恢复本 ADR 中的历史类型草案或参考表达。

- S06 已提供 append-only Canonical Transcript，S07 摘要只产生短生命周期 Projection；
- S07 已由 ADR-042/043/044、离线 Fake、Demo、Gap 与 Commit-scoped G0-G6 对账验收；本 ADR 仍不代表任何能力依据其历史草案实现；
- 快照 Revision、缺失组件算法和上游正式术语继续保持 `Unknown`；
- 若新的公开行为观察或授权快照与本 ADR 冲突，应新建 ADR Supersede 本决策，并重跑
  相关长会话 Eval。
