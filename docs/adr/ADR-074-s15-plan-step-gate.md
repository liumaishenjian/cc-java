# ADR-074：S15 内存 Plan 步骤 Gate

- Status: Accepted
- Date: 2026-08-17
- Stage: S15 Independent Innovation
- Features: `PLAN-01`

## Decision

Plan selection 的真实 Headless Run 复用现有 `AgentRuntime`、`ModelGateway`、Canonical Session、Context Projection 与 `ToolExecutionPipeline`。规划 Scope 只发布 `list_files/read_file/search_text/git_status/git_diff` 五个有界 Workspace 只读 Tool，并固定 `PLAN` Permission；`apply_patch`、`write_file`、`run_command`、Web/MCP/Plugin/Skill/Subagent 以及未知 Tool 均不进入该 Registry，因此在审批前不能产生副作用。

模型完成只读探索后必须返回精确 JSON Object：`objective` 与 `steps[{title,detail}]`。Java 在最终 Assistant 追加前执行严格、有界解析：未知字段、Markdown 包裹、空步骤、超限、控制字符或畸形 JSON 均以 `INVALID_MODEL_RESPONSE` 失败关闭；plan ID、连续 ordinal、status 与 workspace digest 全部由 Runtime 生成。提案安装到同一 `AgentSession` 的 `PlanModeCoordinator`，不创建第二份 Transcript，并发布有界 `PlanProposalEvent` / stdio `plan.proposed` 供 TUI review。

用户 API 采用 `/plan [自然语言任务]`。TUI 进入时必须先发送绑定 commandId 的 `permissions query` 并保存当前公开 selection，再等待 PLAN selection 成功；带参数随后通过专用 `plan.start` 向真实只读 Plan runtime 提交完整自然语言任务，无参数随后发送 `plan-status`。`workspaceDigest`、结构化步骤、plan ID 与执行上限均是服务端内部协议状态，用户不输入也不维护。TUI 完整展示 `plan.proposed` 后提供“批准并执行 / 继续修改计划 / 拒绝并退出”三个方向键选项；批准先发送绑定所展示 `planId + workspaceDigest` 的内部 `plan-approve`，成功后恢复进入前 selection（若原 selection 已是 PLAN，安全使用 ASK），等待 permissions 成功后才发送同样绑定 planId+digest 的 `plan-execute`。恢复失败保持 Plan APPROVED 且不执行；继续修改拒绝当前提案但保持 PLAN，拒绝退出在 reject 成功后恢复 selection。所有结果按 commandId、planId、digest 关联；Session resume、transport failure 清除 pending 状态。

既有封闭 `plan`、`plan-status`、`plan-approve`、`plan-reject`、`plan-step-begin`、`plan-step-complete` 与 `plan-execute` Java Session Command 继续作为内部兼容协议，但除 `/plan` 与只读 `/plan-status` 外均从 Slash suggestions 与 `/help` 移除。`plan-step-begin` 仍仅在 Plan 已显式批准、没有活动 Run 且摘要一致时原子领取步骤；Permission picker 保留 `Plan / Ask for approval / Approve for me` 三项，不把步骤 Gate 变成第四个权限选择。

## Evidence and gap

确定性 Fake Model 覆盖自然语言 `plan.start`、只读多回合探索、写/命令 Tool 不可见且执行次数为零、畸形 proposal、字段/步骤 ceiling、规范化 proposal event，以及同摘要批准转移；Parser 单测覆盖严格 schema 与 UTF-8/字段上限。TUI protocol/Ink 测试覆盖严格 query→PLAN→start/status、approve→restore→execute、恢复失败不执行、revise 保持 PLAN、reject exit 恢复、安全 ASK fallback 和迟到 command 防护；Java Pipeline 测试以真实 `write_file` 证明退出 PLAN 后副作用仍经既有审批管线，跨进程 E2E 明确要求并实际拼入 `CC_JAVA_PLAN_FAKE_CLASSPATH`，缺失时失败而不空跑。当前实现仍是 L1、进程内内存状态；内部协议仍接受兼容的人工 Plan 创建。258k Context compaction 的既有证据继续有效，但 Plan durable checkpoint/restart recovery 与真实 Provider proposal 质量 Eval 尚未实现，不能宣称持久化、重启恢复或 L4 收益。
