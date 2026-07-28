# S01 Agent Loop 离线 Demo

> Stage：S01 — Runtime Kernel（Agent Loop）
> Demo 类型：测试驱动的可重复协议演示
> 真实模型与 API Key：不需要
> 当前验证状态：标准 Windows Wrapper 与正反例 Demo 已通过；Commit-scoped G4 待完成
> 证据分类：Standard Worktree；构成 G5 实际执行证据，不替代最终 G4 Commit 身份

## 1. 演示目标

本 Demo 不是交互式 CLI。它使用测试源码中的 Scripted Fake Model、Fake Permission、Fake Approval 和 Fake Tool，验证 S01 的最小纵向链路：

```text
Fake User
→ Fake Model Tool Call
→ Fake Permission
→ Fake Tool
→ Tool Result
→ Fake Model Final
```

重点不是自然语言答案，而是以下可观察协议：

- `AgentRuntime` 明确掌握模型/工具循环；
- Assistant Message 与同一回合的全部 Tool Call 只追加一次；
- 一批 Tool Call 按顺序执行，每个 Result 保留原 Call ID；
- Tool Result 进入下一轮模型 Context；
- 预算不足时整批拒绝进入 canonical history；
- 未知工具、非法参数和执行异常被结构化回传；
- Run 以明确的 Stop Reason 结束；
- Session 消息和事件只保存在当前进程内。

## 2. 环境

- Windows PowerShell；
- JDK 21；
- 仓库自带 Maven Wrapper 3.3.4；
- Wrapper 下载或使用 Maven 3.9.16；
- 不需要配置任何模型 Provider 或 API Key。

首次执行时，若本机缓存中没有 Wrapper 分发包或测试依赖，Maven 仍可能需要访问 Maven Central。这里的“离线 Demo”指测试不调用在线模型服务，而不是保证首次构建完全不访问依赖仓库。

## 3. 运行

在 `cc-java` 仓库根目录执行：

```powershell
.\mvnw.cmd -pl cc-java-core -am test
```

参数含义：

- `-pl cc-java-core`：选择 Core 模块；
- `-am`：同时构建 Core 依赖的 Domain 模块；
- `test`：编译并运行相关测试。

要单独复现本阶段的代表性正例、负例、恢复与事件顺序，执行：

```powershell
.\mvnw.cmd -pl cc-java-core -am `
  "-Dtest=AgentRuntimeTest#continuesUntilFinalResponseAcrossMultipleToolTurns+appendsMultiCallAssistantMessageExactlyOnce+rejectsEntireMultiCallBatchWhenRemainingBudgetIsInsufficient+returnsStructuredUnknownToolResultAndLetsModelRecover+emitsOrderedEventsForToolLoop" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dsurefire.reportNameSuffix=s01-demo" `
  test
```

测试通过数与总数以 Maven Surefire 测试报告为准。本 Demo 的实际运行记录、报告哈希和
工作区身份见
[`S01 Runtime Kernel 标准验证证据`](../evidence/S01-runtime-kernel-2026-07-28.md)。

### 3.1 2026-07-28 实际检查

| 字段 | 记录 |
| --- | --- |
| Evidence Class | `Standard Worktree`；G5 Passed，最终 G4 等待稳定 Commit |
| Date | 2026-07-28 |
| Code Identity | Base Commit `27129342087af68d957f10c52ed807c64778fbad` + Code/Build Digest `04886d5d1ab9` |
| Environment | Windows 10 amd64；Eclipse Temurin 21.0.11+10；Apache Maven 3.9.16 |
| Standard Command | `.\mvnw.cmd -pl cc-java-core -am test` |
| Standard Result | 23 通过，0 失败，0 错误，0 跳过；`BUILD SUCCESS` |
| Focused Demo Result | 5 通过，0 失败，0 错误，0 跳过；包含整批预算拒绝负例 |
| Local Artifact | `cc-java-core/target/surefire-reports` |
| Persistent Record | [`docs/evidence/S01-runtime-kernel-2026-07-28.md`](../evidence/S01-runtime-kernel-2026-07-28.md) |

本轮已经关闭 Wrapper 启动、标准 Maven 3.9.16 验证和可核验 Demo 三个执行缺口。
由于 Wrapper 修复和证据仍在未提交工作区，G4 还缺稳定 Commit 身份；这不影响把本次实际
Demo 记为 G5 Passed，但在 Commit-scoped 复验前不得把 S01 标记为 Accepted。

## 4. 如何观察主链路

测试 Fixture 为 Fake Model 预先排好模型回合。一个包含两个 Tool Call 的代表性历史应保持以下形状：

```text
User("...")
Assistant(text?, calls=[call-1, call-2])
ToolResult(callId=call-1, ...)
ToolResult(callId=call-2, ...)
Assistant(finalText)
```

检查重点：

1. 两个调用属于同一条 Assistant Message，历史中没有重复的 Assistant 副本。
2. Tool Result 顺序与模型声明的调用顺序一致。
3. 每个 Tool Result 的 `callId` 和 `toolName` 与原调用严格一致。
4. Fake Model 的下一回合可以看到两个 Tool Result。
5. 最终 `AgentRunResult` 使用 `COMPLETED`，并报告实际模型回合数与工具调用数。
6. 生命周期事件按 Session/Run、Model Turn、Tool、Permission、Run Finished 的控制流有序记录。

这是协议形状示例；具体输入文本、ID 值和测试数量以测试源码及测试报告为准。

## 5. 如何观察批次预算原子性

设置一个小于下一批 Tool Call 数量的 `maxToolCalls`，让 Fake Model 一次返回多个调用。应观察到：

- Run 以 `TOOL_LIMIT_REACHED` 停止；
- 该 Assistant Tool-Call 批次没有写入 canonical history；
- 没有执行其中任何一个 Fake Tool；
- 没有追加该批次的 Tool Result；
- 已经存在的 User Message 和更早的完整历史保持不变。

该行为避免“Assistant 已写入，但只完成部分 Tool Result”的协议损坏。

## 6. 如何观察结构化错误

相关离线场景应覆盖：

| Fake 场景 | 反馈给下一模型回合的结果 |
| --- | --- |
| 调用未注册工具 | `UNKNOWN_TOOL` |
| 参数校验不通过或校验器异常 | `INVALID_ARGUMENTS` |
| Tool 执行抛出异常 | `EXECUTION_FAILED` |

每个错误 Result 都必须保留原始 Call ID。此类错误是可反馈的 Tool Result，不应直接让 Runtime 丢失当前 Session 的规范历史。

## 7. Stop Reason 与限额边界

S01 实际验证的 Runtime 终止路径包括正常完成、模型错误、无效模型响应、模型回合上限、Tool Call 上限和内部协议错误。S01 的有效限额仅包括：

- 最大模型回合数；
- 最大 Tool Call 数。

领域协议中出现取消、时间、Context、权限等 Stop Reason，只表示为后续 Stage 保留可扩展值，不表示对应执行路径已经完成。

## 8. 事实边界

运行本 Demo 不能证明以下能力已经实现：

- 真实模型或 Spring AI Provider；
- 流式模型输出、交互式或 Print CLI；
- 真实 list/read/write/patch/Shell 工具；
- 真实 Permission Policy、终端 Approval 或 OS Sandbox；
- 取消、超时、deadline 或进程清理；
- 持久 Session、resume/fork、Checkpoint 或崩溃恢复。

这些差距按后续 Stage 处理，详见 [`docs/gap-reports/S01.md`](../gap-reports/S01.md)。
