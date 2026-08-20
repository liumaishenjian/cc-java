# ADR-077：S15 Continuous Plan Runtime 与能力边界

- Status: Accepted
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 2）
- Feature IDs: `PLAN-01`
- Current → Batch Target: `L1 → L1`；补齐用户可用链路但仍缺 Batch 3 执行与真实 Provider Eval
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Supersedes in Part: ADR-074 的用户严格 JSON proposal、静态五 Tool 白名单与一次性提案路径
- Builds On: ADR-076 的 Session-owned durable Markdown `PlanArtifact`

## 1. 决策

`/plan <自然语言任务>` 在同一 Session 内启动普通多轮 Agent Runtime。模型可以读取、搜索、请求受控网络读取、以 revision+digest CAS 增量修改唯一 PlanArtifact、提出结构化单选问题，并显式请求 review。规划模型的 Tool payload、最终 JSON 或隐藏的 objective/title/detail/expectedDigest 三元组不进入 console；review 事件直接读取已提交 Markdown revision。

规划期采用 `PlanToolCapability` + `ToolEffect` 的确定性资格策略，而非工具名白名单：

| 能力 | 规划期行为 |
| --- | --- |
| `READ_ONLY_LOCAL` | 可见并执行，仍走唯一 Pipeline |
| `READ_ONLY_NETWORK` | 可见，但仍经 Permission/AutoReview/Approval；拒绝时不访问网络 |
| `PLAN_ARTIFACT_WRITE` | 仅内置受控 artifact Tool 可用；不属于 Workspace write/checkpoint |
| `USER_QUESTION` | 暂停同一 Run，以 callId 等待结构化选项答案后继续 |
| `BOUNDED_READ_ONLY_SUBAGENT` | 契约保留；本批生产装配未启用 |

Workspace write、process execution 和 system/destructive effect 在规划期固定拒绝。MCP、Plugin、外部 Tool 默认不可用；只有可信注册边缘显式声明匹配安全 capability 才能进入 definitions，并在 Pipeline 再次检查。**这是 cc-java 的独立安全强化；受控研究未证明参考产品对整个 Tool registry 采用同样的通用 capability filter。**

## 2. 受控参考研究

2026-08-20 至 2026-08-21 在仓库外对 `G:\AI Cloud\claude-code-main` 进行只读研究，核对 Plan mode/permission setup、计划文件关联、退出 review Tool、AskUserQuestion Tool 与 keyboard picker、REPL 状态、Session 恢复和 Plan attachment 投影。只提炼职责、状态转换、边界、恢复和测试方法；未复制或翻译函数体、Prompt、文案、私有命名、布局、常量、Fixture 或字节。

| 分类 | 机制结论 |
| --- | --- |
| Observed | 规划是持续模式而非“一个最终 JSON 回合”；同一会话可反复读取、更新计划文件、提问并最终请求审批。 |
| Observed | 计划正文与退出审批分离；review UI 使用计划内容，批准、继续规划与拒绝是不同决定。 |
| Observed | 结构化问题以 Tool call 触发，UI 用键盘选择器回答；回答恢复同一模型/工具循环。 |
| Observed | Plan mode 对文件修改和非只读操作施加限制，计划文件是特殊受控写例外；恢复重建上下文但不自动重放副作用。 |
| Inferred | cc-java 应以 durable artifact revision 作为 review 事实，以 callId 关联问题，而非打印模型 JSON 或复用 y/n 审批输入。 |
| Inferred | tool visibility 与 execute-time hard gate 应复用同一 capability policy，避免隐藏后猜名调用绕过。 |
| Unknown | 准确上游 Revision、全部远程/协作语义、所有外部 Tool 的分类保证，以及参考实现是否具有通用 capability registry。 |

## 3. 独立契约与状态

1. `revise_plan_artifact(markdown, expectedRevision, expectedContentDigest)`：创建或替换当前 Session 的唯一 Markdown 工件；不接受路径、Session ID 或 Plan ID。
2. `request_plan_review(revision, contentDigest)`：仅把匹配的 `DRAFT` revision 推进到 `AWAITING_APPROVAL`；review 事件携带同一已提交正文、revision 和 digest。
3. `ask_plan_question(question, options)`：2–4 个封闭选项；stdio `question.requested` 与 `question.resolve` 使用原始 callId 关联。重复、迟到、跨 Run、未知 option、取消和断连全部失败关闭。
4. 用户选择“继续修改计划”时执行 `AWAITING_APPROVAL -> DRAFT`，revision `+1`，保持同一 sessionId/planId/revision chain；下一 `/plan <反馈>` 继续同一 Session。
5. 最终 Assistant 文本在 Plan stdio 路径被抑制；只有 durable review Markdown 可展示。旧 parser/protocol 仅保留内部兼容，TUI `/plan task` 不再调用它。

## 4. 安全与架构不变量

- 模型只提出 Tool intent；资格、CAS、状态迁移、问题答案关联与 review 发布均由确定性 Java 代码决定。
- 所有本地、网络和控制 Tool 都进入统一 `ToolExecutionPipeline`，保持 Call/Result ID 和多调用顺序。
- Artifact write 不触碰 Workspace，不创建普通文件 Checkpoint，也不授予执行权限。
- 网络读取继续使用既有 Permission/AutoReview；显式 deny、Hard Denial、Hook 和 cancellation 不变。
- Domain/Core 不依赖文件系统、Jackson、stdio 或 Ink；stdio/TUI 只适配结构化端口。
- Session recovery、writer fence、canonical JSONL、Context、Checkpoint、Sandbox 与取消语义不因 Plan 放宽。

## 5. 可证伪证据

离线 Fake 与 E2E 覆盖：

- 同一 session：read → artifact update → ask → answer → update → request review；
- definitions 与 Pipeline 双 Gate 拒绝 Workspace mutation/process，外部 Tool 未声明 capability 默认隐藏；
-受控网络读取在执行前仍产生 Permission 请求，deny 时真实网络 hit=0；
- stdio/TUI 不出现 Tool payload JSON、模型最终 JSON、objective/title/detail/expectedDigest；
- callId picker、重复/迟到/未知答案、取消/断连；
- review Markdown 来自 durable artifact；反馈 revision 和 Resume 保持同一身份链。

## 6. Batch 3 seam 与剩余差距

本批停在 `plan.review.requested`：它提供稳定 planId/revision/contentDigest/Markdown review seam，但**不实现最终批准到执行**。现有 legacy `plan-approve/plan.execute` 只为兼容保留；Batch 3 必须以 durable review revision 重新设计批准绑定、执行 prompt/projection 和完整恢复 E2E，不能把 Markdown 自动解析成命令或绕过 Pipeline。

`PLAN-01` 保持 L1：尚缺 Batch 3 durable approval-to-execution、真实 Provider 计划质量/误操作率/延迟成本评测、多人/远程冲突、稳定跨版本 migration 和完整 S15 A/B Eval。
