# ADR-078：S15 durable Plan review 与原子执行交接

- Status: Accepted
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 3）
- Capability IDs: `PLAN-01`（保持 L1）、`PERM-05`（保持 L1）、`SESSION-08`、`CTX-06/07`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 授权快照机制为 `Observed / Inferred / Unknown`；本项目契约与测试为 `Documented / Verified in cc-java`
- Supersedes: ADR-076/077 中将 Batch 3 执行交接延期的部分；ADR-074 的 legacy step/triple 路径仅保留隐藏兼容，不再服务 durable review

## 1. 背景与问题

Batch 1/2 已提供 Session-owned、revisioned Markdown `PlanArtifact` 和持续规划 Tool，但临时审批
仍误把 `contentDigest` 当作 legacy `workspaceDigest`，并通过 `PlanDocument` 的 approve → permission
restore → `plan.execute` 三次客户端动作启动执行。这会造成 revision、正文摘要、工作区快照和权限恢复
分属不同线性化点，断线或迟到结果可能留下“已批准但 UI 误报执行”的状态。

本批次必须使一次 Enter 对精确 durable revision 作出决定，并由 Java 在同一个服务端编排命令中
批准、选择执行权限、构造执行上下文并接受真实 Agent Run；Surface 不能看到内部 approve/execute 命令。

## 2. 2026-08-21 受控只读研究

按 ADR-022，在仓库外只读检查 `G:\AI Cloud\claude-code-main` 中与退出 Plan picker、permission setup、
REPL pending query、context clear/keep、Session restore 和 Plan 文件恢复相关的机制。研究只提炼职责、状态、
失败边界和验证方法，不复制/翻译函数体、Prompt、文案、私有名称、布局、常量、Fixture 或字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | 退出规划的批准选项同时决定执行期 permission policy；UI 只收敛选择，Tool/Runtime 仍拥有执行控制 |
| Observed | keep-context 与 clear-context 是明确不同的交接路径；clear 仍保存计划引用并安排新的实现查询，而不是丢失批准工件 |
| Observed | 拒绝/继续规划会解除当前审批等待并把反馈送回同一交互循环，不把拒绝伪装成批准 |
| Observed | 自动模式在退出规划后恢复为执行期策略；权限更新不会覆盖强制拒绝或工具自身安全校验 |
| Observed | Session 恢复可重建计划关联和 canonical conversation；Fork 使用新计划身份，崩溃恢复不等于自动重放副作用 |
| Inferred | 审批决定、permission restore、计划引用与实现查询调度应由一个服务端协调器线性化；客户端多命令链无法保证断线原子性 |
| Inferred | 上下文使用率可作为默认 clear/keep 建议，但用户显式 picker 选择必须优先，且两路都保留不可变批准快照 |
| Unknown | 授权快照准确发行版本、所有阈值、完整远程/多端一致性、内部存储格式和所有 crash interleaving |

上述观察只支持机制抽象；本项目以下类型、schema、状态、阈值、提示语和测试均独立设计。

## 3. 决策

### 3.1 review 事件与单命令

`plan.review.requested` 必须绑定并展示同一已提交工件的：

- `planId`；
- `revision`；
- `contentDigest`；
- 完整 Markdown snapshot；
- 独立 `workspaceDigest`；
- 进入 Plan 前的 permission mode；
- 基于显式设置/Context 使用率的 keep/clear 建议。

`contentDigest` 永远不充当 workspace digest。TUI 只有一个 picker，顺序固定为：

1. 批准并自动执行（默认）；
2. 批准并保持普通逐 Tool 审批；
3. 带自然语言反馈继续规划；
4. 拒绝并退出。

Tab 可显式覆盖 keep/clear。一次 Enter 只发送 `plan.review.resolve`。legacy `plan.execute` 对 durable
review 显式拒绝，legacy `PlanDocument`/session command 只留给历史兼容测试，不在新面板暴露。

### 3.2 服务端线性化与失败关闭

Java 在生命周期锁内完成：

```text
校验 Session idle/writable
→ 读取精确 AWAITING_APPROVAL revision
→ 核对 planId/revision/contentDigest/workspaceDigest
→ 构造完整 execution RuntimeScope
→ 创建 immutable ExecutionBrief
→ canonical journal + manifest CAS 提交 APPROVED
→ 领取单次 ActiveRun 句柄
→ executor 接受任务
→ 仅此时回送 plan.execution.accepted
```

stale revision、摘要不匹配、工作区冲突、重复/迟到决定、断线、持久化失败和 enqueue 失败都失败关闭。
enqueue 失败释放未开始句柄，但保留 durable `APPROVED` + `ExecutionBrief`，允许重启后显式继续；绝不
提前写 `EXECUTING/COMPLETED` 或回送成功。

### 3.3 ExecutionBrief

`ExecutionBrief` 保存：批准 artifact identity、批准 revision、正文 hash 和完整 snapshot、Plan/Session ID、
可用的 planning Run/transcript locator、原始/有效 permission mode、ASK reviewer、context policy、用户反馈、
批准时 workspace digest 和时间。它随 `PlanArtifact` 一起进入 canonical journal/manifest；Markdown 正文只
保存一份，解码时由工件正文重建 brief snapshot，避免重复 1 MiB 字节。

批准 Markdown 直接作为**不可信自然语言计划上下文**投影给普通 Agent Runtime，不解析为命令，不生成
objective/title/detail/expectedDigest 三元组。执行 Tool 仍逐次经过唯一 Registry/Pipeline、Hook、Permission、
AutoReview、Checkpoint、取消和预算。

### 3.4 Permission 与 Context

- 自动执行：`PermissionMode` 恢复为进入 Plan 前的非 PLAN mode（未知/PLAN 回退 DEFAULT），Reviewer 为
  `AUTO_REVIEW`；它只收敛最终 ASK，Hard Denial、显式 DENY、PLAN capability boundary 和 Tool Adapter
  安全校验仍是最终决定；不扩大 AUTO 快速路径。
- 普通执行：相同 mode 恢复，但 Reviewer 为 `USER`，保留现有 Ask picker。
- keep：保留 canonical conversation，再插入批准工件投影。
- clear：模型请求只保留基础 System、当前执行 User message 和不可变批准工件，不删除 canonical journal；
  artifact/brief 跨 clear/compact 保留。
- 默认建议：最近一次 Context usage 达可用输入预算 70% 时建议 clear，否则 keep；picker 显式选择优先。

### 3.5 生命周期与恢复

- `AWAITING_APPROVAL → APPROVED → EXECUTING → COMPLETED/FAILED/CANCELLED/TIMED_OUT/LIMIT_EXCEEDED`；
- `AWAITING_APPROVAL → DRAFT` 保留同一 session/planId，revision + 1，并可立即用反馈启动下一次 Plan Run；
- `AWAITING_APPROVAL → REJECTED` 干净终止；
- `APPROVED` 重启后仅通过显式 `resumeApprovedPlanExecution` 领取，不自动开始；
- `EXECUTING` 重启产生 `PLAN_EXECUTION_RECOVERY` gate，不能可写 resume 或重放副作用；
- duplicate approval 不创建额外 revision，按 stale 决定拒绝。

## 4. 验证

确定性测试覆盖：

- AUTO_REVIEW 与 USER 两类执行权限；
- 一键 review resolve → execution accepted → normal Agent Run → Tool Pipeline → terminal artifact；
- keep/clear 与完整 Markdown snapshot/hash；
- stale/double decision、feedback/reject、legacy execute 禁用；
- APPROVED 显式重启，EXECUTING recovery gate；
- no legacy triple/JSON leakage、无第二次 plan.execute；
- stdio 事件和 React/Ink 四项 picker；
- ASK/AUTO/PLAN、Hard Denial 和历史测试回归。

## 5. 差距与 Batch 4

`PLAN-01` 保持 L1：尚缺真实 Provider 行为/质量 Eval、大型真实仓库长计划评测、真实 crash/transport fault
矩阵、用户反馈文本编辑器（当前协议已支持反馈字段，TUI 本批次以空反馈提交，下一次 `/plan <反馈>` 仍可
继续）、稳定外部协议和多端并发。Batch 4 应完成这些 Eval/UX/故障注入并决定是否达到 L2/L4；本 ADR 不
以实现存在自动提升 Capability。
