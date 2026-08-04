# ADR-043：S07 Context Projection 与条件式 Reduction 契约

- Status: Accepted
- Date: 2026-08-04
- Stage: S07 Context Engineering
- Capability IDs: `LOOP-11`、`CTX-06/07/08/09/10/11/12/13`、`OBS-04`
- Current → S07 Exit Target: `L0 → L2`（G0-G2 冻结，不提升等级）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Documented / Observed / Inferred / Unknown` 见 ADR-042；本 ADR 为 cc-java 独立 `Documented` 设计

## 决策

S07 把 **Canonical Transcript** 与 **Model Context Projection** 分离。S06 journal 继续保存聚合语义
事实和 Tool 执行事实；S07 每次请求从这些事实构造短生命周期 Projection。压缩、裁剪、摘要和记忆
注入只改变 Projection，不删除、重排或重写 Canonical Transcript。

Context Reduction 采用条件式 C1-C4 策略图，而不是固定 C1→C2→C3→C4 流水线：

| 策略 | 独立语义 | 适用条件 | 禁止行为 |
| --- | --- | --- | --- |
| C1 大载荷缩减 | 将当前请求中的单个高体积载荷替换为有界保真表示 | 单载荷超预算且存在安全缩减器 | 不静默丢弃、不伪造原文 |
| C2 旧 Tool 输出清理 | 清除低价值旧 Tool Result 正文，保留类型化占位和配对 | 历史 Tool 输出占压且不再是当前任务证据 | 不留下孤儿协议块 |
| C3 滚动记忆 | 把一段已完成历史归纳为可继续滚动的记忆块 | 仍需保留近期交互和长期事实 | 不覆盖未完成 Tool 批次 |
| C4 全量摘要 | 将可压缩历史边界归纳为一个受验证摘要 | 其他安全策略不足且摘要能力可用 | 失败时不提交边界 |

Planner 可以选择零个、一个或多个策略；每次选择后重新计算预算，满足容量即停止。策略顺序由当前
Projection 的压力来源、收益估计、保真风险和协议边界决定，不能硬编码“每次都跑四步”。

## 独立 Java 契约

截至 2026-08-05，C1/C2 确定性 Projection、C3/C4 摘要、A1 Runtime Projection seam 与 A2 typed
overflow 单恢复接缝已形成以下独立 API；A2 仍不包含 Provider 分类 Adapter，也不构成 Capability Level
或 Gate 提升：

```text
ContextCapacity(modelId, maximumInputTokens, reservedOutputTokens, safetyMarginTokens)
ContextUsage(systemTokens, instructionTokens, transcriptTokens, toolTokens,
             memoryTokens, totalTokens, remainingTokens, estimateKind)
ProjectionRequest(canonicalMessages, systemInputs, instructionInputs,
                  readyMemories, capacity, overflowRecoveryAvailable)
ContextProjection(messages, usage, appliedReductions, sourceRevision)
ContextReduction(strategy, tokensBefore, tokensAfter, affectedMessages)
ContextReductionOutcome(status, projection, initialUsage, finalUsage, reason)
SummaryRequest(tier, boundedInputSnapshot, sourceRevision, sourceMessageIds,
               requiredProtectedAnchors, maxOutputUtf8Bytes, maxOutputTokens,
               sourceEstimatedTokens)
SummaryCandidate(tier, summary, sourceRevision, sourceMessageIds,
                 utf8Bytes, estimatedTokens)
SummaryOutcome(status, previousProjection, projection, attemptedTiers,
               diagnostics, adoptedCandidate)

ContextProjectionPlanner.plan(ProjectionRequest, CancellationToken)
ContextReducer.reduce(ProjectionRequest, CancellationToken)
ContextTokenEstimator.estimate(messages, capacity) -> ContextUsage
ContextSummarizer.summarize(SummaryRequest, CancellationToken) -> Optional<SummaryCandidate>
SummaryReductionCoordinator.reduce(...) -> SummaryOutcome
ContextOverflowRetryCoordinator.execute(...) -> at-most-two model attempts
ContextPreparationService.prepare(canonical ModelRequest, CancellationToken) -> projected ModelRequest
ContextPreparationService.closeRun(RunId) -> per-run cleanup
```

- `ContextProjectionPlanner` 与确定性 Reducer 属于 Core；`ContextSummarizer`、精确 Provider token count 和
  外部载荷读取通过 Port 注入。
- `AgentRuntime` 仍先由 `DefaultContextAssembler` 构造 Canonical `ModelRequest`，再交给单个
  `ContextPreparationService` 生成只供该模型回合使用的 Projection；旧构造器固定使用 no-op 路径。
  A2 仅在 `ModelGatewayException.FailureKind.CONTEXT_OVERFLOW` 且首次请求尚未产生可见流式文本时，使用
  同一 Run 的 `ContextOverflowRetryCoordinator` 强制尝试 C3/C4；只有摘要 ADOPTED 才以完全相同且有序的
  Tool Definitions 发送第二次请求。非 overflow、空/拒绝/取消/关闭摘要均不重试，第二次 overflow 直接映射
  `CONTEXT_LIMIT_REACHED`。`finally` 按 Run 调用 `closeRun`，关闭并移除摘要与 retry 状态；Projection 不进入
  `AgentSession` 或 `SessionJournal`。
- Domain/Core 类型保持不可变、框架无关，不携带 Spring AI、Reactor、Path、JSON、FileLock、Ink 或
  Node 类型。
- `sourceRevision` 标识构建 Projection 时读取的规范历史版本；候选结果提交前必须确认来源未变化。
- `ReductionOutcome` 只携带分类、计数和有界摘要，不记录完整 Prompt、源码、Tool 输出或记忆正文。

## 容量与恢复

1. 容量由模型输入上限减去输出保留和独立安全余量得到；估算结果必须标记精确或估计，不能把估计
   冒充 Provider Usage。
2. 普通请求先构造 Projection；只有超过软预算才进入 Reduction，无法安全满足硬预算时返回
   `CONTEXT_LIMIT_REACHED`。
3. Provider 明确返回 Context Overflow 时，同一个模型回合最多进行一次恢复；恢复请求必须记录
   `overflowRecoveryAvailable=false`，再次溢出直接终止。
4. 同一规范 revision、压力区间和失败策略组合进入冷却；没有新规范输入或可证明收益时不得反复摘要。
5. 取消在模型请求发送前生效；候选摘要或缩减未提交时全部丢弃。

## 协议不变量

- Assistant Message 中的同批 Tool Calls 只能整体保留或在受验证边界中整体归纳；不得复制该 Assistant
  Message，也不得拆散 Call ID 对应关系。
- 每个保留的 Tool Call 必须恰有一个对应 Tool Result；缩减后的协议孤儿数必须为 0。
- 未完成 Tool、Recovery Gate 和当前活动批次不进入可删除边界。
- C2 的占位必须明确说明内容已从 Projection 清理、保留原结果类型和截断状态，但不得声称是原结果。
- C3/C4 摘要必须保留用户目标、已确认约束、关键决策、未完成工作、失败状态和必要文件/符号引用；
  不得产生新权限、成功结论或 Tool 执行事实。

## 摘要提交 Gate

摘要只有同时满足以下条件才可成为 Projection 输入：

1. 文本非空且在独立大小上限内；
2. 摘要模型没有返回 Tool Call、非法协议块或不完整流；
3. 请求未取消、来源 revision 未变化；
4. 摘要覆盖的边界是完整协议区间且不含活动/未完成 Tool；
5. 事实与约束检查器没有发现必须保留项缺失；
6. 预估后的 Projection 确实释放足够空间。

任一条件失败时保留原 Projection，发布失败 outcome；不得写入 Canonical Transcript，也不得把失败摘要
当作下一次摘要的事实来源。当前离线 Core 还要求候选 tier 匹配、source revision 匹配、稳定 source
message ID 有序精确覆盖、严格 UTF-8、请求级 byte/token 上限、输出估算严格低于来源、所有 protected
anchor 原样保留且正文不含 Tool Call/Result 协议片段。C3 只在 C1/C2 仍超预算且 rolling window 是完整
协议区间时尝试；C4 只在 C3 仍未满足容量且完整摘要前提成立时尝试。同一 Run/source revision/tier
最多调用摘要 Port 一次，候选提交后只追加 Projection Reduction，Canonical Transcript 保持深度相等。
冷却与 overflow retry 状态由构造时绑定唯一 Run 的 `AutoCloseable` 对象持有；Run owner 结束时调用
`close()`，并发 close/acquire 在同一锁上线性化，关闭后 fail-closed 且清空 revision/tier Key，不建立跨 Run
全局 registry。摘要最终采用也通过 Guard 的同一生命周期锁提交：commit 先获得锁时 ADOPTED 在线性化点上
先于 close；close 先获得锁时 commit 不执行终态构造并丢弃候选，不能在 close 胜出后返回 ADOPTED。
Coordinator 还逐条比较原 Canonical protected tail 与 Projection 尾部；任何差异在摘要前拒绝。
Provider Adapter 把 `ContextSummaryMessage` 固定映射为版本化 User JSON envelope，摘要正文以 UTF-8 Base64
承载，不能映射成 Assistant/ToolResponse 或在 envelope 中裸露可伪装 Tool 协议的片段。

## Context Usage 投影

S07 Core 提供可序列化但非稳定外部协议的 Usage View，至少按 System、Project Instructions、Canonical
Transcript、Tool Payload、Memory Projection 和 Free/Reserved 分类展示估算。当前只冻结内部 View；完整
`/context` 命令路由、帮助文本和 Slash Command UX 归 S08。TUI 只消费事件，不决定 Reduction。

## 可证伪退出测试与度量

- 用 Scripted Fake Model 回放直接完成、单/多 Tool Call、Overflow、摘要失败、取消和 Resume；每个
  Projection 的 Tool 协议孤儿数必须为 `0`。
- 用策略矩阵证明 C1-C4 条件选择：低压力为零策略；大单载荷可只选 C1；旧 Tool 压力可只选 C2；
  滚动历史可选 C3；只有其他策略不足时才选 C4；组合策略满足预算后停止。
- 同一 Overflow 只出现一次恢复请求；同 revision 重复失败进入冷却，不形成无限模型循环。
- 独立长会话 Eval 中，固定事实与硬约束保持率均达到 `100%`，任务完成率不低于未压缩对照，进入
  Reduction 的样本中模型输入 Token 中位数至少下降 `30%`。这些是 cc-java S07 退出阈值，不是参考常量。
- 空摘要、Tool Call 摘要、损坏边界、活动 Tool、来源竞态和取消均不得提交。
- Resume 前后对相同 Canonical Transcript 的 Projection 规范化结果一致；journal 内容和 digest 不变。

## 被否决方案

- **固定四步串行**：浪费模型调用且把无关策略强加给每次请求，否决。
- **直接重写 S06 JSONL**：破坏规范事实、恢复和 Behavior Replay，否决。
- **只按字符截断历史**：会破坏 Tool 配对和关键约束，否决。
- **Overflow 无限重试**：可能形成费用与延迟循环，否决。
- **让 TUI 或 Provider Adapter 决定压缩**：破坏 Core 所有权和多 Surface 一致性，否决。
- **把 Provider 原生 Context Editing 作为 S07 必需能力**：形成供应商耦合，延期 S14 对照。

## 延期与能力声明

S08 负责分层 Instructions、持久 Settings、完整 `/compact`/`/context` UX；S12 负责 Sub-Agent 独立窗口；
S14 负责 Provider Cache/Context Editing、稳定机器协议和跨版本持久兼容。当前 C1-C4 已通过 `ContextPreparationService` 接入 `AgentRuntime` 的 ModelRequest 前置 seam，A2 又接入
Provider-neutral typed overflow 与 Runtime 精确一次恢复；旧构造器仍走 no-op，纯数据 `ContextSummarizer`
Port、候选 Gate 与 per-run cooldown 保持不变。尚无真实 Provider overflow 分类或 Provider summarizer
Adapter。G3-G6 完成前，README
和矩阵继续把上述 S07 Capability 标为 L0，不得描述为完整可用。
