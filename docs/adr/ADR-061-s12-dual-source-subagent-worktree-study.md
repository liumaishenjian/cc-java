# ADR-061：S12 Sub-Agent + Worktree 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-10
- Stage: S12 Sub-Agent + Worktree（G0-G2 范围冻结）
- Feature IDs: `SUB-01`～`SUB-10`、`CTX-15`、`HOOK-08`、`HOOK-11`、`TOOL-15`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenAI Codex `rust-v0.147.0`，Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Classification: 授权快照为 `Observed / Inferred / Unknown`；Codex 固定公开源码为 `Documented / Observed`；采纳边界为本项目 `Documented`

## 背景

S01-S11 已建立显式 `AgentRuntime`、统一 Tool Pipeline、Permission、Session/Checkpoint、Context Projection、Hooks、MCP、Skills 与 Plugins。S12 要在这些真实边界上实现受控委托，而不是另建一套 Loop、让后台任务绕过权限，或把 Git Worktree 宣称为 S13 OS Sandbox。

本轮遵循 ADR-022，只读研究仓库外 `AUTH-SRC-2026-07-29-A`；同时复用并重新核验官方 OpenAI Codex `rust-v0.147.0` 固定 tag：Git annotated tag object `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d` 指向 Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`，本地 detached clone 的 `HEAD` 与 exact tag 均一致。两类来源只用于提炼职责、状态、不变量、失败恢复和验证方法；不复制函数体、Prompt、注释、错误文案、私有名称、布局或常量。

## 双源研究结论

| 机制结论 | 授权快照 | Codex 0.147.0 | cc-java 采纳 |
| --- | --- | --- | --- |
| Agent definition 与一次运行实例分离，定义可约束 instructions、model、tools、permission 与 limits | Observed | Observed | 严格 `AgentDefinitionSnapshot`，Session 启动冻结内容身份 |
| 子任务复用同一 Agent Loop，但拥有独立 Context、运行状态、Tool 可见集和取消链 | Observed | Observed | `ChildRuntimeScope` 重新装配同一 `AgentRuntime`，不共享可变 `AgentSession`/Run state |
| 父子关系、调用身份和终态是显式状态；父只消费有界结果，不直接拼接子完整 Transcript | Observed | Observed | `DelegationId`、`ChildTaskId`、状态机与 `ChildTaskReport` 摘要投影 |
| 模型/预算覆盖在创建边界解析并受父级与可信配置上界限制 | Observed / Inferred | Observed | 只允许已配置模型；先原子预留子预算，不能通过委托制造额度 |
| 并发需要 Session 级共享容量，嵌套创建也计入同一上限 | Observed | Observed | 公平、有界 `AgentSupervisor`；父子与兄弟共享 active permits |
| 前台等待、后台运行、通知与取消是不同职责；终态先收敛，慢摘要/清理不能阻塞状态观察 | Observed | Observed | durable task state 与有界通知分离；状态先终结，资源在 `finally` 清理 |
| 子生命周期有专用 start/stop Hook；附加 Context 不能改变权限或运行事实 | Observed | Observed / Inferred | `HOOK-08` 接入 S09 Coordinator，start 可阻断，stop 只观察 |
| Git Worktree 提供工作目录和分支隔离；创建失败、脏改动或未合并 Commit 时清理必须 Fail Closed | Observed | 未发现 S12 等价主链 | 独立 `WorktreeManager` Adapter；显式 create/keep/remove，默认不自动删除有价值工作 |
| 安全只读 Tool 可并行，但结果协议仍按原模型批次顺序提交 | Inferred | Observed（并发任务/状态） | `TOOL-15` 仅对白名单 `READ_WORKSPACE`、同一 Workspace snapshot 并发 |

## 状态与不变量

### 子任务状态

```text
CREATED → QUEUED → STARTING → RUNNING
RUNNING → SUCCEEDED | FAILED | CANCELLED
QUEUED/STARTING → CANCELLED
terminal → CLEANING → RELEASED
```

- 每个 `ChildTaskId` 恰有一个 terminal outcome；terminal 后不得再次执行模型或 Tool。
- `STARTING` 前必须已完成 definition、scope、预算、并发 permit、Session/Recovery 与可选 Worktree Gate。
- 创建失败不留下 active task、预算占用、Session writer、Hook lease 或半注册 Worktree。
- 父终止、显式取消和 Stage shutdown 触发结构化取消；后台不是脱离所有权。
- 子结果仅包含固定状态、计数、已验证摘要和可选 Worktree disposition；完整子 Transcript 仍由子 Session 自己持有。

### Scope 与权限

- 子 Agent 创建新的 `AgentSession`/Context Projection；不得把父 `AgentSession`、active Run、Permission Grant、Skill Hook lease 或 mutable settings overlay 作为共享状态。
- definition Tool 集必须是父运行可委托 Tool、宿主允许集与 definition allowlist 的交集。权限模式只能保持或收窄；每次真实 Tool 调用仍执行 S05 Permission → Approval → Hook → Pipeline。
- 后台任务不能直接显示不可关联的审批；需要 ASK 时必须通过父 Surface 的精确 task/call 关联，或在无可用交互面时 Fail Closed。
- 子模型只能从当前 Session 已验证的模型目录选择；S12 不实现 Provider discovery、Fallback 或成本价格表。
- 父预算先原子预留 `turn/tool/time/token/output` 上限；子实际消耗回收未用余额，不能透支或重复返还。

### Worktree

- Worktree 是 Git working copy 隔离，不是文件、进程或网络 Sandbox；子进程仍以当前 OS 用户权限运行。
- 只接受已验证 Git repository、规范化 slug 与宿主派生路径；固定 argv 执行 Git，不经 Shell，不复制 Provider Secret 或任意 gitignored 文件。
- 基线记录 canonical repository identity、base commit、worktree path identity 与 branch；进入子 Scope 后 WorkspaceGuard、Settings/Instructions/Session fingerprint 全部基于新 root 重建。
- 删除前必须证明目标仍是该 lease 的 worktree，且无未提交变更、无基线后新增 Commit、无 active child/Session lease。无法证明时保留并报告，禁止 `--force` 静默丢弃。
- S12 不自动 merge、cherry-pick、commit、push 或删除用户既有分支；`KEEP` 是有价值或不确定状态的安全终态。

## 采纳与有意偏离

### 采纳

1. Agent definition snapshot、同 Runtime 重新装配的独立 Scope、显式 parent/child identity 和有界结果摘要。
2. 父预算预留、共享并发 permit、结构化取消、前台/后台统一任务状态和异步终态通知。
3. 专用 Sub-Agent start/stop Hook 与现有 Hook trust/timeout/error policy；Hook 不能直接创建已批准 Tool 执行。
4. 只读安全 Tool 的有界并行与稳定结果归并。
5. Git Worktree lease、独立 Workspace composition、显式 keep/remove 和保守恢复。

### 有意偏离与延期

- S12 不兼容两类参考的 Agent 文件、任务记录、事件、消息、Prompt 或 Worktree 布局。
- `HOOK-11` 在 S12 只达到 L1：仅提供宿主预注册、受信的 Agent-definition/委托决策 Hook 骨架，输出只能进一步收窄 definition/scope；模型驱动 Hook、通用 Prompt Hook 与质量治理延期 S15。
- `SUB-11` Team Task Board、peer messaging 和长期团队协作保持 L0，延期 S14/S15。
- 跨进程 daemon、远程 worker、稳定机器协议、跨重启后台继续执行与通用任务迁移延期 S14；S12 只要求同一 Headless 进程内 durable 可检查终态和崩溃后不自动重放。
- Worktree 不等于 S13 OS Sandbox；网络、进程、Secret、系统路径隔离及攻击性 Sandbox 回归保持 S13。
- 不让子 Agent 自动 commit/merge/push；外部 Git 写入仍需用户明确授权。

## Unknown

- `AUTH-SRC-2026-07-29-A` 的准确 Revision、发行版、许可证、权利人和公开再使用权；
- 参考实现完整的调度公平性、模型选择策略、预算计费口径、摘要 Prompt/质量阈值和跨重启后台保证；
- Windows/Linux 上 Git Worktree 锁、杀进程与异常掉电的全部时序；
- 多 Agent 对真实模型成功率、Token/墙钟收益与冲突率的参考可比基线；
- Prompt/Agent Hook 的成熟模型决策安全策略与 S15 质量门槛。

Unknown 不进入本项目格式、常量或测试 Oracle。

## 可证伪验证方向

ADR-062 和 S12 Evidence 必须验证：相同 Runtime 复用且无第二套 Loop；父/子 Context 与 mutable state 零串扰；Tool/Permission 只能收窄且旁路为 0；预算预留不超卖；嵌套并发共享上限；前台/后台唯一终态；父取消、显式取消、timeout 和 shutdown 无 orphan；Hook start/stop 配对；只读 Tool 并行后 Call ID/结果顺序完整；Worktree 创建故障无半注册、进入后 root 全量重建、脏/有 Commit 时保留、clean remove 后无 worktree/branch 泄漏；多 Agent Eval 同时报告质量、Token、墙钟与冲突。

本 ADR 完成 G0 与参考结论采纳边界，不提升 Capability Level。