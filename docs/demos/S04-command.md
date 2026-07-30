# S04 Command Demo

- Stage: S04 Write + Command
- Feature IDs: `TOOL-10`、`TOOL-11`、`TOOL-14`、`SEC-04`、`SEC-05`
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`

## 目标

验证 Java Runtime 而不是 React/Ink 执行命令，并能观察：

```text
模型提出 run_command
→ Java 校验 command/timeout
→ TUI 展示准确 command、Shell 和 cwd
→ Allow Once
→ 固定 Workspace/最小环境启动前台 Shell
→ stdout/stderr 逐步展示
→ 返回 exitCode、timeout/cancel 与截断状态
```

## 启动

在任意 PowerShell 目录运行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File "E:\Java\cc-java\scripts\RunS02TuiSpike.ps1" `
  -Timeout "60s" `
  -SkipBuild
```

输入一个只需要本地验证的任务，例如：

```text
请先查看 pom.xml，然后使用 run_command 执行 Maven 单元测试。执行前说明命令，
只允许本次批准；根据退出码和输出告诉我是否通过。
```

TUI 必须展示：

- `run_command`；
- 实际 Shell 为 `powershell` 或 `sh`；
- 工作目录为 `.`；
- 完整命令正文；
- `Y` 允许一次、`N` 拒绝。

批准后应逐步看到命令输出，最终 Tool 摘要包含退出码证据。按 `Ctrl+C` 应取消 Run，
Java 必须终止命令主进程和已观察到的后代；再次按下才是强制终端退出。

## 确定性专项验证

```powershell
.\mvnw.cmd -pl cc-java-tools-local -am `
  '-Dtest=LocalCommandExecutorTest,RunCommandToolTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

.\mvnw.cmd -pl cc-java-cli -am `
  '-Dtest=RuntimeStdioCommandHandlerTest,StdioApprovalCoordinatorTest,HeadlessRuntimeSessionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

npm.cmd --prefix cc-java-tui run check
```

专项测试使用真实平台 Shell，覆盖非零退出码、stdout/stderr、48 KiB 截断、环境过滤、
取消、timeout，以及超时后子进程不能留下延迟 Marker。

## 明确边界

- 命令运行在当前用户账户下，不是 OS Sandbox；
- S04 不提供交互式 TTY、后台任务、持久 Shell、Session Allow 或网络隔离；
- 审批能够降低误操作，不能限制已批准 Shell 的全部系统能力；
- 自动 Commit、Push、Publish 和 Deploy 不属于本 Demo。
