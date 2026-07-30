# S04 Patch/Write 学习 Demo

## 目标

本 Demo 证明 S04 第二切片的最小闭环：

```text
模型提出精确文本修改
→ DEFAULT 返回 ASK
→ 终端展示相对路径、修改类型和增删行数
→ Allow Once
→ WorkspaceGuard 与内容前置条件重新检查
→ 同目录暂存并单次 Move
→ 返回有界 Patch 摘要
→ 模型继续推理
```

Stage 为 `S04`；对应 Feature 为 `TOOL-08`、`TOOL-09`、`TOOL-14`、`CLI-05`、
`SEC-01`。当前目标等级为 L1；S04 后续已在实现 Commit `16b4767` 上通过
Commit-scoped G0-G6，Stage Exit 为 Accepted，见
[S04 Stage Exit 证据](../evidence/S04-stage-exit-2026-07-30.md)。

## Tool 契约

`apply_patch` 使用项目独立定义的精确上下文格式：

```json
{
  "path": "src/main/java/example/App.java",
  "oldText": "return oldValue;",
  "newText": "return newValue;",
  "replaceAll": false
}
```

- 路径只能相对 Workspace；
- `oldText` 必须非空，且默认只能匹配一次；
- 多匹配必须提供更多上下文，或显式设置 `replaceAll=true`；
- 文件在提交前发生变化时返回 `FILE_CONFLICT`，不覆盖新内容；
- 修改后文件仍受 2 MiB UTF-8 字节上限约束。

`write_file` 首版只创建新文件：

```json
{
  "path": "src/test/java/example/AppTest.java",
  "content": "class AppTest {}\n"
}
```

直接父目录必须已经存在；已有目标、敏感路径、外部 Symlink/Junction 和缺失父目录均拒绝。

## 离线复验

Windows PowerShell：

```powershell
.\mvnw.cmd -pl cc-java-tools-local -am `
  "-Dtest=WorkspaceGuardTest,WindowsJunctionWorkspaceGuardTest,ApplyPatchToolTest,WriteFileToolTest,AtomicUtf8FileWriterTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest,StdioApprovalCoordinatorTest,RuntimeStdioCommandHandlerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

npm.cmd --prefix cc-java-tui run check
```

普通测试使用 Fake Model，不需要网络或 API Key。`HeadlessRuntimeSessionTest` 同时证明：

- 非交互入口遇到 ASK 时拒绝，文件保持不变；
- 显式 Allow Once 后，真实 `apply_patch` 经规范 Tool Pipeline 执行；
- stdio 审批事件不包含 `oldText`、`newText` 或完整参数对象。

## 仍未证明

- `run_command`、stdout/stderr 流、超时与 Windows 进程树；
- “修改 → 测试失败 → 再修改 → 成功”的完整公开 Fixture；
- Session Allow、持久规则、Checkpoint/Undo 和 OS Sandbox。
