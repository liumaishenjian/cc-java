# ADR-062：S12 Sub-Agent Runtime、后台任务与 Worktree 独立契约

- Status: Accepted
- Date: 2026-08-10
- Stage: S12 Sub-Agent + Worktree（G1-G2 架构/范围冻结）
- Feature IDs: `SUB-01`～`SUB-10`、`CTX-15`、`HOOK-08`、`HOOK-11`、`TOOL-15`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Depends On: ADR-039、ADR-041、ADR-043、ADR-047、ADR-052、ADR-059、ADR-060、ADR-061

## 决策摘要

S12 不新增 Maven 模块。Domain/Core 增加框架无关委托协议和 `AgentSupervisor`；CLI/Application 负责 definition discovery、任务持久状态与 Composition；`cc-java-tools-local` 提供固定 argv 的 Git Worktree Adapter；TUI 只消费 privacy-safe 任务事件。每个子 Agent 重新装配现有 `AgentRuntime`，持有独立 Session/Context/Tool Registry/Permission state/Budget/Cancellation，所有真实 Tool 仍走唯一 Pipeline。

## G1：Feature 与退出目标

| Feature | 当前 | S12 Exit | 冻结目标 |
| --- | ---: | ---: | --- |
| `SUB-01` Agent Definition | L0 | L2 | user/project 严格定义、immutable snapshot、来源/诊断/冲突与内容身份 |
| `SUB-02` Runtime Reuse | L0 | L2 | 复用同一 `AgentRuntime` factory，不建立第二套 Loop |
| `SUB-03` Isolated Context | L0 | L2 | 独立子 Session、Canonical/Projection、mutable state 与 transcript |
| `SUB-04` Parent/Child Task | L0 | L2 | 显式 delegation、状态、结果摘要和失败分类 |
| `SUB-05` Tool-restricted Agent | L0 | L2 | Tool visibility 纯交集，Permission/Approval 逐调用重评估 |
| `SUB-06` Model/Budget Override | L0 | L1 | 已配置模型子集与父预算原子预留；不做模型发现/价格治理 |
| `SUB-07` Concurrent Agents | L0 | L2 | Session 级公平有界并发，嵌套共享容量，无超卖 |
| `SUB-08` Background Agent | L0 | L2 | 同进程后台任务状态、通知、inspect/wait/cancel 与恢复 Gate |
| `SUB-09` Cancellation | L0 | L2 | 父/显式/timeout/shutdown 传播，唯一终态与资源清理 |
| `SUB-10` Git Worktree | L0 | L2 | create/enter/keep/remove、独立 root 重装配、保守清理/恢复 |
| `CTX-15` Sub-Agent Isolation | L0 | L2 | 父只接收有界 report；完整子 transcript 不注入父 Context |
| `HOOK-08` Sub-Agent Hooks | L0 | L2 | trusted start/stop lifecycle、start 可阻断、stop 只观察 |
| `HOOK-11` Prompt / Agent Hook | L0 | L1 | 只做宿主预注册、受信的 definition/delegation 收窄 Hook 骨架 |
| `TOOL-15` 并行安全工具 | L0 | L2 | 白名单只读同批并发、稳定归并、单项取消/失败与协议完整 |

全部 Capability 在 G0-G2 后仍保持 L0；只有实现、测试、Demo 与同一变更对账后才能提升。

## 产品行为

### Agent definition

定义采用项目自有严格 schema，候选字段为稳定 ID、description、instructions、visible tool allowlist、permission ceiling、model override、limits、background default 与 isolation policy。User/Project 两层按 S08 provenance 与 Trust 规则解析；同层重复、未知字段、非法 UTF-8、越界/链接、未知 Tool/Model 或权限放宽均隔离。Session 捕获 content digest snapshot；磁盘更新只影响新 Session。

### 委托与结果

父模型通过普通 `delegate_agent` Tool 提出委托意图，该 Tool 自身进入 Registry/Pipeline。执行器只调用 `AgentSupervisor`，不能直接执行子 Tool。嵌套 depth 是 Host-owned provenance：顶层固定为 1，child scope 只能从父请求递增，并复用同一 Supervisor/ledger/active queue；模型 schema 不接受 depth。同步委托等待子终态后返回 `ChildTaskReport`；后台委托立即返回 task identity，随后由 `task.inspect`、`task.wait`、`task.cancel` 或终态通知观察。父 Context 只接收 report，不复制完整子消息历史、Tool output 或 Prompt。

`ChildTaskReport` 只包含 task/definition/status、固定 stop/failure code、model/tool/turn/token/elapsed 计数、有界 UTF-8 摘要、验证状态与可选 Worktree disposition；不包含绝对路径、Provider 原文、完整命令/Tool 参数或 Secret。

### HOOK-08 / HOOK-11

- `SUB_AGENT_START` 在 child Scope 物化前执行，可阻断或附加有界 untrusted Context；不能新增 Tool、扩大 Permission/Budget/Workspace。
- `SUB_AGENT_STOP` 在 terminal outcome durable 后执行，只观察并可附加下一父回合的 transient Context；不能改写子终态或重启执行。
- S12 的 `AGENT_DEFINITION` Hook 只能由宿主预注册且经过 Trust，输出为纯收窄 patch；任何未知/放宽/超限结果 Fail Closed。通用模型 Prompt/Agent Hook 延期 S15。

## 独立 Java 契约

以下名称和边界由 cc-java 独立定义；精确字段在 G3 测试先行后落地：

```text
AgentDefinitionId
AgentDefinitionSnapshot
DelegationId
ChildTaskId
ChildTaskRequest
ChildTaskStatus
ChildTaskOutcome
ChildTaskReport
ChildRuntimeScope
ChildBudgetReservation
WorktreeLease
WorktreeDisposition

AgentDefinitionCatalog.snapshot()
ChildRuntimeScopeFactory.create(parentScope, definition, request, reservation, workspace)
AgentSupervisor.submit(request, cancellation) -> ChildTaskHandle
ChildTaskHandle.inspect() / await(timeout) / cancel()
ChildResultSummarizer.summarize(outcome, cancellation) -> bounded report
WorktreeManager.create(request) / inspect(lease) / keep(lease) / removeClean(lease)
ParallelToolBatchExecutor.executeSafeBatch(batch, cancellation)
```

### 模块所有权

| 模块 | S12 职责 | 禁止职责 |
| --- | --- | --- |
| `cc-java-domain` | immutable definition/task/status/report/budget/worktree 值对象 | Path、JSON、线程、Git、Spring/Ink 类型 |
| `cc-java-core` | supervisor、scope factory port、budget reservation、cancel tree、并发调度、结果归并 | 文件发现、Git 命令、Surface 状态 |
| `cc-java-cli` | catalog/strict parser、Session composition、task journal adapter、stdio command/event | 第二套 Loop、直接 Tool 执行 |
| `cc-java-tools-local` | Git repository/worktree 固定 argv Adapter 与 realpath/状态验证 | 自动 commit/merge/push、OS Sandbox |
| `cc-java-tui` | task 卡片、状态、wait/cancel/keep/remove 意图 | 调度、权限、Git 删除、终态推断 |

## 预算、并发与取消

- 默认每 Session active child 上限为 `4`，最大 queue 为 `32`，委托深度最大 `2`；这是 cc-java 的 S12 保守上限，不来自参考实现。放宽需新 ADR 与压力/安全回归。
- 父创建子任务前对 turn/tool/time/token/output 预算执行单次原子 reservation；拒绝或创建失败完整释放，terminal 只退还可证明未使用部分。
- 同一 Supervisor 的所有嵌套 child 共用 active permits；不得每层新建 semaphore 绕过总上限。公平顺序以入队序号为 tie-break。
- parent Run cancellation 默认取消其所有非 detached child；S12 不提供 detached ownership。后台只表示父 Run 可继续，不表示父 Session/shutdown 不拥有它。
- terminal CAS 先于摘要/通知/Worktree检查；取消竞态只有首次 terminal 胜出。所有资源以逆序、幂等 `close` 清理。
- S06 journal 记录 task requested/started/terminal 的聚合语义状态；恢复发现 requested/started 无 terminal 时标记 `INTERRUPTED_UNKNOWN`，绝不自动重放模型、Tool 或 Worktree Git 操作。

## TOOL-15：并行安全 Tool

只在同一 Assistant tool batch 中并发执行同时满足以下条件的调用：

1. definition effect 为 `READ_WORKSPACE`；
2. Tool 名称在宿主只读并发 allowlist；
3. 使用同一 immutable Workspace identity，不依赖兄弟调用结果；
4. 不涉及 Approval、Checkpoint、Process、Network、Plugin/MCP remote 或 dynamic Skill activation；
5. 批次预算在启动前原子预检。

并发完成顺序不改变协议：Assistant Message 仍追加一次；结果按原调用顺序、原 Call ID 恰好各追加一次；单项异常归一化为该 Tool Result，pipeline/journal 不变量失败则 fence 整个 Run；取消传播全部在飞调用并等待有界清理。写、命令、远程和未知 Tool 始终顺序。

## Worktree 契约

```text
REQUESTED → CREATING → READY → IN_USE
IN_USE → KEEPING → KEPT
IN_USE → REMOVING → REMOVED
任意非终态失败 → FAILED_PRESERVED
```

- 创建只基于当前 canonical repository 的明确 base commit；使用结构化 Git argv、关闭交互凭证提示、固定 timeout/output ceiling。
- slug 为宿主生成的有界 ASCII 标识；目标必须位于项目私有 worktree root，父目录 realpath 后验证无链接逃逸。
- Worktree ready 后重新构造 `LocalWorkspaceBootstrap`、WorkspaceGuard、Settings/Instructions、Session fingerprint、Tool adapter；不得携带父 root 的 Path-bearing cache。
- remove 必须验证 lease identity、Git registration、无 active owner、`status --porcelain` 为空且 base 后无新 Commit；任何 Git 命令失败或发现价值均 `FAILED_PRESERVED/KEPT`。
- 不复制 `settings.local`、Provider Secret、任意 gitignored 文件或依赖目录；依赖安装由子 Agent 作为普通受控 Command 决定。
- S12 不自动合并结果。父报告 Worktree path 使用项目内 opaque ID；用户显式选择 keep/remove，后续整合仍按普通 Git/Permission 流程。

## G3 实施批次（完整 S12，不拆成 G1/G2 微任务）

1. **Batch A — Scope + single delegate**：Domain contracts、strict definition snapshot、child Session/Scope factory、同 Runtime 单前台委托、summary、HOOK-08 与 recovery journal。
2. **Batch B — bounded concurrency + background + TOOL-15**：Supervisor/queue/reservation/cancel tree、inspect/wait/cancel/notification、safe read batch executor、stdio/TUI 状态投影。
3. **Batch C — Git Worktree + integrated Eval**：Local Git Adapter、lease/disposition/recovery、child root re-composition、并发写任务 Demo、多 Agent quality/cost/time/conflict Eval。

每个 Batch 必须保持前一批回归；只有 A-C、G4、G5、G6 全部完成才退出 S12。

## G4 可证伪矩阵与量化

### 隔离/权限/协议

- parent/child canonical、context revision、Session ID、Permission Grant、Skill/Hook lease、settings overlay、read cache 串扰次数为 `0`。
- 子 visible tools 始终为允许集合交集；definition/Hook 企图放宽时 adapter/permission/execution 次数为 `0`。
- 所有 child Tool Call 都有唯一 Pipeline lifecycle、准确 Call ID 与 durable result；旁路为 `0`。
- 父 report 序列化对 Prompt、正文、Tool 参数/输出、selector、Secret、绝对路径 sentinel 泄漏为 `0`。

### 并发/后台/取消

- active 数不超过 `4`、queue 不超过 `32`、深度不超过 `2`；嵌套 child 同样计数，预算超卖为 `0`。
- normal/failure/cancel/timeout/shutdown/no-replay task-journal recovery 每任务恰一 terminal；orphan model/tool/process/hook/session/permit 为 `0`。
- Worktree 自动证据只计算真实覆盖的 identity/registration、active/ignored/dirty/new commit 保留与 clean remove；ancestor reparse、Git fault/timeout 和 Windows remove/cancel recovery 未有确定性故障注入时必须记录为 gap，不得用实现描述替代证据。
- status terminal 必须在任何可故障摘要/通知/Worktree清理之前可观察；慢 observer 不阻塞 `await`。
- TOOL-15 在 4 个确定性慢读 Tool 上墙钟相对顺序基线至少降低 `40%`，返回顺序/Call ID/协议孤儿仍分别稳定/准确/`0`。

### Worktree

- create 的每个故障点均无半注册可用 lease；ready root 与 parent root 不同，WorkspaceGuard/Settings/Session fingerprint 全部指向 child root。
- dirty、untracked、new commit、active owner、identity mismatch、Git failure 均删除次数 `0` 并报告 preserved。
- clean remove 后 worktree registration、目录与临时分支泄漏为 `0`；取消中创建按可证明状态清理，否则 preserve。
- 任意路径 traversal、absolute slug、symlink/junction root、凭证提示或超时均 Fail Closed。

### 多 Agent Eval

在至少 6 个公开、确定性 Seed Tasks 上分别跑单 Agent 与多 Agent 策略，每项至少 5 次 Fake/固定模型回放；报告任务完成率、事实/约束保持、总模型回合、Tool 次数、估算 Token、墙钟、取消延迟、文件冲突和未审批副作用。S12 的门槛为：完成率不低于单 Agent，隔离/权限/协议/孤儿/冲突安全违规均为 0；墙钟或输入 Token 至少一项中位数改善 ≥20%，另一项不得恶化 >25%。真实 Provider 只作 opt-in，不作为普通 CI 前提。

## 被否决方案

- 在 CLI/TUI 写第二套 Agent Loop；
- 共享父 `AgentSession` 或复制父可变 Runtime Scope；
- 把 allowed tools 当成自动批准，或把父 Session Grant 泄漏给 child；
- 无界虚拟线程、每层独立 semaphore、轮询 busy wait；
- 后台 child 使用不受 Session/shutdown 拥有的独立取消源；
- 自动删除 dirty/unmerged Worktree，或自动 commit/merge/push；
- 把 Worktree、Permission、Checkpoint 或进程清理描述为 OS Sandbox；
- 在 S12 实现 Agent Team/peer messaging、远程 worker、稳定 daemon protocol 或模型 Hook 决策系统。

## Gate 结论

ADR-061/062 冻结 G0-G2 后，Batch A-C、Evidence/Demo/Gap 与权威文档已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 commit-scoped G0-G6 对账，S12 Stage Exit Accepted。`SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 达到 L2，`SUB-06/HOOK-11` 达到 L1；延期项与 Worktree 自动故障注入 gaps 保持不变。