# S04 Coding Loop 工作区证据

> Status：Worktree Verified；Stage Exit 等待 Commit-scoped 复验

## 元数据

- Stage: S04 Write + Command
- Release / Commit: `WORKTREE`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Feature IDs: `TOOL-08`、`TOOL-10`、`TOOL-14`、`PERM-02`、`PERM-07`、
  `EVAL-01`
- Current → Exit Target: `EVAL-01 L0 → L1`
- Date: 2026-07-30

## G0-G3：来源、范围、设计和实现

ADR-035 已记录 Permission、Patch 和 Command 的授权源码受控研究以及独立 Java/TypeScript
契约。本切片没有继续读取参考源码，也没有引入参考 Fixture、Prompt、函数体、常量或
Golden Output；它只按 PRD 已固定的公开验收任务组合现有 Runtime 能力。

Fixture 独立包含任务、初始代码、自测、允许修改范围和越权探针。Scripted Model 不能
直接写文件，每个动作都通过生产 `HeadlessRuntimeSession` 和统一 Tool Pipeline。

## G4：确定性验证

聚焦命令：

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=S04CodingLoopFixtureTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

实际结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终工作区同时通过：

- `.\mvnw.cmd verify`：169 项 Java 测试，166 通过、3 项真实 Provider/Symlink
  环境跳过；
- `npm.cmd --prefix cc-java-tui run check`：38/38；
- `.\mvnw.cmd javadoc:aggregate`；
- Command/ripgrep Windows 进程专项：15/15。

自动断言覆盖：

- 越权 `DO_NOT_EDIT.txt` Patch 被拒绝且无落盘；
- 错误 `divide` 与新增自测真实产生非零命令退出；
- 模型请求中保留匹配 Call ID 的失败证据；
- 第二次精确 Patch 后命令返回 `exitCode: 0` 和 `ACCEPTANCE_OK`；
- 最终 Git 状态和 Diff 只包含 `src/Calculator.java`；
- 9 次 Tool Call、10 个模型回合和 `COMPLETED` 终态。

当前托管沙箱对 JUnit 创建的 `%TEMP%` 目录执行 `toRealPath()` 时返回
`AccessDeniedException`，同一命令在沙箱外可以访问并完成全量复验。S04 Fixture 和
Headless Git Loop 因此在模块 `target` 下创建唯一临时仓库，并在结束时处理 Git object
的 Windows 只读位后清理；该调整不改变生产 WorkspaceGuard。

## G5：可复现 Demo

[S04 Mini Coding Agent Demo](../demos/S04-coding-loop.md)记录了 Fixture、命令、
观察点、正例、越权负例和事实边界。工作区执行已通过。

## G6：工作区对账

- `EVAL-01` 从 L0 提升到 L1；
- 矩阵、README、PRD、技术设计、ADR、Demo、差距报告和看板在同一变更中对账；
- S04 G5 已有实际正负结果；
- Stage Exit 仍为 Open，仅等待维护者授权创建本地退出 Commit 后进行
  Commit-scoped G0-G6 复验，不把 Worktree 结果冒充 Accepted Commit。
