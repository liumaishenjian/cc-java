# ADR-074：S15 内存 Plan 步骤 Gate


> **Superseded in part by ADR-076/077 (2026-08-21):** 本 ADR 的用户严格 JSON proposal、静态五 Tool registry 与一次性提案 UX 已退出生产 `/plan task` 路径，仅保留内部兼容。当前用户路径是 durable Markdown continuous planning；本 ADR 的批准前无 Workspace 副作用、批准后唯一 Pipeline、关联与失败关闭不变量继续有效。Batch 3 将重新实现 durable approval-to-execution。

- Status: Accepted
- Date: 2026-08-17
- Stage: S15 Independent Innovation
- Features: `PLAN-01`

## Decision

Plan selection 的真实 Headless Run 复用现有 `AgentRuntime`、`ModelGateway`、Canonical Session、Context Projection 与 `ToolExecutionPipeline`。规划 Scope 只发布 `list_files/read_file/search_text/git_status/git_diff` 五个有界 Workspace 只读 Tool，并固定 `PLAN` Permission；`apply_patch`、`write_file`、`run_command`、Web/MCP/Plugin/Skill/Subagent 以及未知 Tool 均不进入该 Registry，因此在审批前不能产生副作用。

模型完成只读探索后必须返回精确 JSON Object：`objective` 与 `steps[{title,detail}]`。Java 在最终 Assistant 追加前执行严格、有界解析：未知字段、Markdown 包裹、空步骤、超限、控制字符或畸形 JSON 均以 `INVALID_MODEL_RESPONSE` 失败关闭；plan ID、连续 ordinal、status 与 workspace digest 全部由 Runtime 生成。该 JSON 是内部协议，不投影为普通 Assistant 文本；用户只看到规范化 Plan 面板。提案安装到同一 `AgentSession` 的 `PlanModeCoordinator`，不创建第二份 Transcript，并发布有界 `PlanProposalEvent` / stdio `plan.proposed` 供 TUI review。

用户 API 采用 `/plan [自然语言任务]`。TUI 进入时必须先发送绑定 commandId 的 `permissions query` 并保存当前公开 selection，再等待 PLAN selection 成功；带参数随后通过专用 `plan.start` 向真实只读 Plan runtime 提交完整自然语言任务，无参数随后发送 `plan-status`。`workspaceDigest`、结构化步骤、plan ID 与执行上限均是服务端内部协议状态，用户不输入也不维护。TUI 完整展示 `plan.proposed` 后提供“批准并执行 / 继续修改计划 / 拒绝并退出”三个方向键选项；批准先发送绑定所展示 `planId + workspaceDigest` 的内部 `plan-approve`，成功后恢复进入前 selection（若原 selection 已是 PLAN，安全使用 ASK），等待 permissions 成功后才启动绑定 planId+digest 的 `plan.execute` Run。该 Run 使用正常 Agent Runtime 与完整 Tool Registry 逐步落实计划；每次 Tool 调用仍经过 Permission/Approval/Hook/Pipeline。没有 action 的自然语言步骤不再被替换成 `git_status`，只有 Run 正常完成后 Plan 才进入 `COMPLETED`。恢复失败保持 Plan APPROVED 且不执行；继续修改拒绝当前提案但保持 PLAN，拒绝退出在 reject 成功后恢复 selection。所有结果按 commandId、planId、digest 关联；Session resume、transport failure 清除 pending 状态。

既有封闭 `plan`、`plan-status`、`plan-approve`、`plan-reject`、`plan-step-begin`、`plan-step-complete` 与 `plan-execute` Java Session Command 继续作为内部兼容协议，但除 `/plan` 与只读 `/plan-status` 外均从 Slash suggestions 与 `/help` 移除。`plan-step-begin` 仍仅在 Plan 已显式批准、没有活动 Run 且摘要一致时原子领取步骤；Permission picker 保留 `Plan / Ask for approval / Approve for me` 三项，不把步骤 Gate 变成第四个权限选择。

## Evidence and gap

确定性 Fake Model 覆盖自然语言 `plan.start`、只读多回合探索、规划 JSON 不投影、写/命令 Tool 在批准前不可见、畸形 proposal、字段/步骤 ceiling、规范化 proposal event，以及同摘要批准转移。TUI protocol/Ink 测试覆盖严格 query→PLAN→start/status、approve→restore→`plan.execute`、恢复失败不执行、revise 保持 PLAN、reject exit 恢复与安全 ASK fallback；真实 Java 跨进程 E2E 进一步证明规划 JSON 没有 `model.text.delta`、批准后普通 Runtime 实际调用 Tool、模型继续到最终文本，并且用户可见 Run 终态前 Plan 已是 `COMPLETED`。当前实现仍是 L1、进程内内存状态；内部协议仍接受兼容的人工 Plan 创建。258k Context compaction 的既有证据继续有效，但 Plan durable checkpoint/restart recovery 与真实 Provider proposal 质量 Eval 尚未实现，不能宣称持久化、重启恢复或 L4 收益。
