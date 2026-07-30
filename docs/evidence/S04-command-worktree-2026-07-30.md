# S04 Command 工作区证据

- Date: 2026-07-30
- Stage: S04 Write + Command
- Feature IDs: TOOL-10, TOOL-11, TOOL-14, SEC-04, SEC-05
- Reference Baseline: R2026.03
- Authorized Snapshot: AUTH-SRC-2026-07-29-A
- Classification: WORKTREE_VERIFIED
- Commit: WORKTREE

## 受控研究结论

授权快照只用于提炼 Shell Provider、timeout 上限、输出进度与裁剪、子进程环境清理、
取消后的资源所有权和跨平台进程辅助职责。本项目没有复制函数体、Prompt、错误文案、
私有类型名、文件布局或常量。独立 Java 契约固定在 ADR-035。

## 已验证行为

- Windows 优先固定 PowerShell 7，缺失时回退系统 Windows PowerShell；其他平台使用
  `/bin/sh`；
- 模型只能提供 `command` 和 1～120 秒 timeout，不能选择 Shell、cwd、环境或 stdin；
- stdio/TUI 审批展示与执行一致的完整命令、Shell ID 和 cwd `.`；
- 子进程环境为 allowlist，不继承 OpenAI/Provider Key 或未知 Secret；
- stdout/stderr 并发消费、逐步发布，模型结果合计 48 KiB 后显式截断并继续排空；
- 非零退出码作为 Agent 可恢复的验证证据；
- timeout 与 Run 取消共用进程树清理；
- Windows 先终止已捕获后代，再执行 `taskkill /T /F` 和 `ProcessHandle` 兜底；
- Windows 无孤儿专项通过：延迟子进程在 timeout 后未能写出 Marker。

## 当前验证

已通过：

- 7 项 Command Executor/Tool 专项，其中 Process Executor 5/5；
- 19 项 Runtime/stdio/Headless 专项；
- `./mvnw.cmd verify`：169 项 Java 测试，166 通过，3 项真实 Provider/Symlink
  环境跳过；
- `./mvnw.cmd javadoc:aggregate`；
- `npm.cmd --prefix cc-java-tui run check`：38 项 TUI 测试与 TypeScript 检查。

当前托管沙箱会拒绝 JUnit `%TEMP%` 工作区的 `toRealPath()`，因此依赖该目录的全量
复验在沙箱外执行。复验同时暴露 Windows Java/PowerShell 冷启动可能超过原先 2～5 秒
的测试抖动：生产 timeout 契约未改变，只放宽测试 Harness 的等待预算；最终全量和
Command/ripgrep 15 项专项均通过。

## 剩余差距

公开 Fixture 的“修改 → 测试失败 → 再修改 → 成功”编码闭环已在
[S04 Coding Loop 工作区证据](./S04-coding-loop-worktree-2026-07-30.md)完成，
`EVAL-01` 达到 L1。Stage Exit 仍等待退出 Commit 上复验。Windows Job Object、OS
文件/网络隔离和攻击性进程逃逸回归属于 S13；后台执行属于 S12。
