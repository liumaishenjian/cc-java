# S06 Session + Checkpoint Demo

- Stage: S06 Session + Checkpoint
- Feature IDs: `LOOP-14`、`SESSION-03/04/05/06/07/08/09/10/11`、`EVAL-02`
- Current → Target: ADR-041 所列 L0/L1 → L1/L2
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制 `Observed / Inferred`；本项目实现与测试 `Documented / Observed`

## 1. Demo 证明什么

本 Demo 全部使用离线 Fake Model、临时 Workspace 和临时 Session Store，通过真实 Core、Java
Headless、stdio 与 React/Ink TUI 路径验证：

```text
create / continue / resume / fork / inspect
→ append-only semantic JSONL
→ canonical history replay
→ write-ahead ordinary-file checkpoint
→ tool.started / execute once / checkpoint.completed / tool.completed
→ bounded diff
→ explicit compare-before-restore Undo
```

关键安全结论是：未完成的有副作用 Tool、损坏记录和不确定 Undo 阶段都会阻止可写恢复；系统绝不
自动重放有副作用操作，也不调用 Git reset/checkout/clean。

## 2. 前置条件

- JDK 21 与仓库 Maven Wrapper；
- Node.js 22（TUI 回归）；
- 不需要网络、API Key、真实模型或商业产品 Session 文件。

## 3. 运行命令

### 3.1 Session Schema、选择、租约与恢复

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=JsonlSessionCodecTest,FileSessionStoreTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

观察点：

- JSONL 只保存聚合 User/Assistant、Tool durable 状态和 Run 终态，不逐 token 落盘；
- Resume 恢复相同 Session ID；Fork 生成新 ID、保留 parent、复制规范历史且不改 source journal；
- Continue 跳过损坏或未完成的较新 Session；
- 第二 Writer 被拒绝，Inspect 只读；锁释放后可重新 Resume；
- `tool.started` 无 completed 时没有伪造 Tool Result，并产生未完成/潜在副作用 issue。

### 3.2 Behavior Replay 与真实活动 Run Gate

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest#resumeAndForkReplayIdenticalCanonicalHistoryIntoModel+realActiveRunBlocksCheckpointUndoUntilBlockingModelReturns" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

观察点：

- Resume 与 Fork 向 Scripted Model 提供相同的 canonical history、Tool Call ID 与 Tool Result 配对；
- 阻塞 Fake Model 通过真实 `application.run` 建立活动 Run；同一 application 的 Undo 返回
  `SESSION_ACTIVE_RUN`，释放 latch 且 Run 正常结束后才能显式 Undo。

### 3.3 Checkpoint、Diff、Undo 与崩溃阶段

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=FileCheckpointCoordinatorTest,RuntimeStdioCommandHandlerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

观察点：

| 场景 | 预期结果 |
| --- | --- |
| 已存在文件 | 保存有界 pre-image；post digest 匹配时原子恢复 |
| Agent 新文件 | post digest 匹配时删除；用户改变内容后拒绝 |
| Tool 失败且文件仍不存在 | 记录 `COMPLETED_ABSENT`，不误 fence |
| created/completed journal 抛错 | 保留 pre-image 与显式 uncertain phase |
| Undo journal 抛错 | Workspace 已恢复但写 `UNDO_JOURNAL_UNCERTAIN`，Resume 被拒绝 |
| 最终 TOCTOU | staged file 已 force 后再次校验 digest；竞态修改不被覆盖 |
| Symlink 或备份损坏 | Fail Closed，不读取链接目标、不恢复损坏备份 |
| stdio Undo 未确认 | 文件不变；只有具体 checkpoint 的 `confirmed=true` 才执行 |

### 3.4 React/Ink 受控交互

```powershell
npm.cmd --prefix cc-java-tui run check
```

观察点：TUI 的 `C`、方向键、`D`、`U` 可达 list/selection/diff/undo 请求；Undo 面板展示具体
Checkpoint ID 和相对目标，只有大写 `Y`（Shift+Y）作为二次确认。协议拒绝未知 phase、绝对/
Traversal target、额外字段、超量列表和超长 Diff；TUI 不直接打开 Session 或 Workspace 文件。

## 4. 事实边界

- S06 JSONL 与 stdio 都是项目自有内部协议，不兼容或解析商业产品内部 JSONL；
- S06 Concurrent Open 为本机单 Writer L1：没有 PID/heartbeat、stale reclaim、网络文件系统或多主机承诺；
- Checkpoint 只恢复受支持的普通文件，不恢复 Symlink/Junction、Shell、进程、网络、远端或权限副作用；
- 不提供 S14 的稳定 Export、Retention、SQLite 或跨版本 Migration；
- Permission、FileLock、Checkpoint 和进程清理都不是 OS Sandbox。
