# S12 Sub-Agent + Worktree 可复现 Demo（Worktree Candidate）

> 状态：G5 WORKTREE CANDIDATE。当前结果来自未提交 dirty worktree；必须由协调者创建真实 implementation Commit 并重新执行 commit-scoped G3-G6，Stage Exit 才可验收。

## 实际执行

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=FileAgentDefinitionCatalogTest,FileChildTaskJournalTest,LocalGitWorktreeManagerTest,AgentSupervisorS12Test" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl cc-java-core -am `
  "-Dtest=S12MultiAgentEvalTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

npm --prefix cc-java-tui run check
```

## 已观察结果

| 场景 | 实际结果 |
| --- | --- |
| strict definition | 3/3：未知字段隔离、User/Project 冲突、Project trust-required、snapshot 磁盘更新不漂移 |
| Supervisor | 5/5：同 Runtime、确定性隐私 report、原子预算/refund、active 上限、queue cancel、Hook 放宽零创建、恢复 registry 与 Stop Context 投影 |
| recovery | 1/1：requested/started 无 terminal → `INTERRUPTED_UNKNOWN`，不重放模型/Tool/Git |
| real Git Worktree | 2/2：clean create/enter/remove 无 registration/branch/path 泄漏；dirty/new commit 均 preserve；traversal slug 拒绝 |
| six-seed real Eval | 1/1，6 seed × 5 replay × 单/多策略 = 60 个真实 Supervisor/AgentRuntime child runs；两侧完成率 100%，冲突/未审批副作用 0；实测墙钟中位数改善 ≥20%，不伪造 Token 收益 |
| TUI | build + 129/129 Vitest；`task.status/task.terminal` 投影通过类型检查与既有回归 |

## 生产链路

```text
delegate_agent ordinary Tool
→ unique ToolExecutionPipeline
→ frozen definition + trusted pure narrowing
→ atomic ChildBudgetLedger reservation
→ fair bounded AgentSupervisor
→ optional fixed-argv Worktree lease
→ child-root LocalWorkspaceBootstrap + independent Session/Permission/Registry
→ same AgentRuntime
→ terminal CAS + durable aggregate journal
→ deterministic bounded ChildTaskReport
→ foreground result / stdio inspect-wait-cancel / TUI task projection
```

## 边界

- Worktree 只是 Git working copy，不是 OS Sandbox。
- 不自动 commit、merge、cherry-pick 或 push；dirty/new commit/identity 不确定时保留。
- 后台任务不 detached，仍由父 Session/shutdown 拥有。
- 父 report 不复制子 Prompt、最终正文、Tool 参数/输出、Provider 原文或绝对路径。
- `SUB-11` Team Board、remote/daemon、跨重启继续执行、稳定 task protocol、模型 Agent Hook 与 OS Sandbox 仍延期。
