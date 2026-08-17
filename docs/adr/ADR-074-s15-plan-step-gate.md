# ADR-074：S15 内存 Plan 步骤 Gate

- Status: Accepted
- Date: 2026-08-17
- Stage: S15 Independent Innovation
- Features: `PLAN-01`

## Decision

Plan selection 的真实 Headless Run 复用现有 `AgentRuntime`、`ModelGateway`、Canonical Session、Context Projection 与 `ToolExecutionPipeline`。规划 Scope 只发布 `list_files/read_file/search_text/git_status/git_diff` 五个有界 Workspace 只读 Tool，并固定 `PLAN` Permission；`apply_patch`、`write_file`、`run_command`、Web/MCP/Plugin/Skill/Subagent 以及未知 Tool 均不进入该 Registry，因此在审批前不能产生副作用。

模型完成只读探索后必须返回精确 JSON Object：`objective` 与 `steps[{title,detail}]`。Java 在最终 Assistant 追加前执行严格、有界解析：未知字段、Markdown 包裹、空步骤、超限、控制字符或畸形 JSON 均以 `INVALID_MODEL_RESPONSE` 失败关闭；plan ID、连续 ordinal、status 与 workspace digest 全部由 Runtime 生成。提案安装到同一 `AgentSession` 的 `PlanModeCoordinator`，不创建第二份 Transcript，并发布有界 `PlanProposalEvent` / stdio `plan.proposed` 供 TUI review。

既有封闭 `plan`、`plan-status`、`plan-approve`、`plan-reject`、`plan-step-begin` 与 `plan-step-complete` Session Command 继续兼容。`plan-step-begin` 仍仅在 Plan 已显式批准、没有活动 Run 且摘要一致时原子领取步骤；Permission picker 保留 `Plan / Ask for approval / Approve for me` 三项，不把步骤 Gate 变成第四个权限选择。

## Evidence and gap

确定性 Fake Model 覆盖只读多回合探索、写/命令 Tool 不可见且执行次数为零、畸形 proposal、字段/步骤 ceiling、规范化 proposal event，以及同摘要批准转移；Parser 单测覆盖严格 schema 与 UTF-8/字段上限，TUI protocol 测试覆盖精确 `plan.proposed` 投影。当前实现仍是 L1、进程内内存状态；显式命令仍接受兼容的人工 Plan 创建。258k Context compaction 的既有证据继续有效，但 Plan durable checkpoint/restart、执行自动编排和真实 Provider proposal 质量 Eval 尚未实现，不能宣称持久化、重启恢复或 L4 收益。
