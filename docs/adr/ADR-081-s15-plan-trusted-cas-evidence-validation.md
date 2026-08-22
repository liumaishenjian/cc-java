# ADR-081：S15 Plan trusted CAS 与 evidence locator 校验

- Status: Accepted
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 6 correctness correction）
- Feature IDs: `PLAN-01`
- Current → Target: `L1 → L1`；纠正生产契约，不提升 Capability
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Supersedes in Part: ADR-077 的模型手工 revision/contentDigest Tool 契约
- Preserves: ADR-076 durable store CAS、ADR-078 approval/execution CAS、ADR-080 Evidence Gate

## 1. 问题

ADR-077 最初让模型在 `revise_plan_artifact` 和 `request_plan_review` 中手工携带 revision/contentDigest。
这把 application-owned optimistic concurrency bookkeeping 暴露给了不可信模型。`declare_plan_evidence`
会独立推进同一个 durable artifact revision，因此模型即使正确记住上一次正文 revision，也会在 evidence
mutation 后自然变成 stale。真实序列中，正文 revision 2 经两次成功 evidence declaration 推进到 4，模型仍
以 revision 2 请求 review，最终 store conflict 又被 Pipeline 压成 generic `EXECUTION_FAILED`。

## 2. 受控参考研究与边界

本批按 ADR-022 在仓库外只读复核授权快照的 Plan 文件维护、退出规划 Tool、审批 picker、REPL keep/clear
交接、Resume/Fork 与缺失文件恢复。只提炼职责、状态和失败恢复；未复制函数体、Prompt、文案、私有名称、
布局、常量、Fixture 或字节。

| 分类 | 最小结论 |
| --- | --- |
| Observed | 模型维护用户可读计划内容；退出规划 Tool 从当前 Session 关联读取计划并请求用户决定，模型不负责维护文件版本 CAS。 |
| Observed | UI 只收敛批准/继续规划/拒绝及执行权限选择；实际状态转换和执行控制由应用 Runtime 持有。 |
| Observed | Resume 尝试恢复同一计划关联，Fork 创建独立可写计划身份；恢复不自动重放副作用。 |
| Inferred | codej 的 revision/contentDigest 是其独立 durable store 实现细节，应位于 trusted application control plane，而不是模型协议。 |
| Unknown | 参考快照是否存在等价 CAS、多人编辑协议、全部远程一致性和稳定外部 schema。 |

`PlanEvidenceLedger`、requirement identity、registered-tool locator 校验和以下 typed error 均为 **codej
独立增强**，不声明为参考产品内部机制。

## 3. 决策

### 3.1 模型契约与内部 CAS

- `revise_plan_artifact` advertised schema 只接受 `markdown`；Tool 执行时重新加载当前 Session-owned DRAFT，
  创建或基于当前 revision/digest 构造下一 revision，并调用 durable store CAS。
- `request_plan_review` advertised schema 是空对象；Tool 执行时重新加载最新 DRAFT，再以该 revision/digest
  原子提交 `AWAITING_APPROVAL`。
- Store 的 revision+digest CAS、Session single-writer fence、journal/manifest commit point 和真正并发漂移
  Fail Closed 全部保留。CAS 是 application-owned trusted control plane，不是模型记忆任务。
- `runPlan` 在同一 lifecycle lock 内完成 active Run 检查、review feedback 转回 DRAFT 和 Plan Scope 占用；
  因并发 active Run 被拒绝的请求不得提前修改 durable 状态。
- 旧三字段 revise 和两字段 review payload 仅作为未宣传 compatibility input 接受；CAS 字段被忽略，且不出现
  在 advertised schema、System projection、stdio/TUI 或 final payload 中。该兼容层不能重新成为模型要求。

### 3.2 Evidence declaration

- `VERIFICATION` locator 除稳定名称格式外，必须属于当前 Runtime 实际注册的 `BUILT_IN` Tool 集合；例如
  当前 XLSX 场景允许 `run_command`，不存在的 `validation-output` 必须在执行前拒绝。
- 校验反馈只列有界 Tool 名 alternatives，不包含路径、Markdown、命令、Tool 输出或 Secret。
- DRAFT 中相同 `requirementId` 表示同一逻辑要求：完全相同声明幂等且不执行 durable save/不推进 revision，
  内容变化原位替换。这样旧的语义错误 locator 可确定纠正，顺序、identity、最大 64 项和批准后冻结不变量保持。

### 3.3 类型化失败

只有来源、Effect 与 capability 都证明为 trusted BUILT_IN Plan artifact Tool 的
`PlanArtifactStoreException` 不再落入 generic `EXECUTION_FAILED`；普通/MCP/Plugin Tool 不能借同一异常类型
伪造 Plan 恢复语义或绕过 repeated-failure governance：

- stale revision、digest conflict、not found、already exists、invalid lifecycle state 映射
  `PLAN_ARTIFACT_CONFLICT`，仅返回封闭 reason 与恢复 action；同一空 review intent 可在 Runtime 重新加载后重试；
- corruption、identity/path、limit、atomic move 与 I/O 不确定性映射 `PLAN_ARTIFACT_UNAVAILABLE`，要求停止
  mutation 并安全 Resume；
- 两类错误均不拼接底层异常、物理路径、正文或 JSON。

## 4. 可证伪验证

- revision 2 → DELIVERABLE success → invalid semantic VERIFICATION → corrected VERIFICATION success →
  stale legacy review 2，最终 review 最新 durable revision 并进入 `AWAITING_APPROVAL`；
- evidence 后 revise 重新加载当前 revision，保留 Ledger；
- `validation-output` 拒绝且反馈包含 `run_command` alternative，失败不推进 revision；
- DRAFT requirement 同 identity 原位 replacement；完全相同重试不 save、不推进 revision，批准后仍冻结；
- active ordinary Run 期间的 feedback `runPlan` 被拒绝且 AWAITING_APPROVAL revision 不变；
- 非 trusted Plugin 抛同类型异常仍是 generic failure，第二次同 fingerprint 被治理阻断；
- store 在 load/save 间注入真实 concurrent evidence mutation：首次 review typed conflict、无 review event/commit，
  相同空 intent 重试最新 DRAFT 成功；
- stdio/TUI 不显示 Tool payload JSON、CAS 字段或 final JSON；review/evidence/revision 经 Resume 保留；
- real Java Plan fixture 必须在 review 前成功声明 evidence。

## 5. 等级与差距

本纠正关闭模型手工 CAS 导致的确定性生产缺陷，不提供真实 Provider 计划质量、多人/远程编辑、稳定 migration、
跨平台安装矩阵或 L4 A/B 收益证据。`PLAN-01` 保持 L1，S15 Exit 保持 OPEN。
