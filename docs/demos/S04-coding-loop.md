# S04 Mini Coding Agent 端到端 Demo

- Stage: S04 Write + Command
- Feature IDs: `TOOL-08`、`TOOL-10`、`TOOL-14`、`PERM-02`、`PERM-07`、
  `EVAL-01`
- Current → Target: `EVAL-01 L0 → L1`；其余条目保持已达到的 S04 等级
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制 `Observed / Inferred`；Fixture、实现和结果
  `Documented / Observed`

## 1. Demo 证明什么

本 Demo 使用公开、独立编写的最小 Java Fixture 和 Scripted `ModelGateway`，通过真实
`HeadlessRuntimeSession`、`ToolExecutionPipeline` 和本地 Tool 完成：

```text
读取任务与源码
→ 提出越权修改并被审批策略拒绝
→ 加入一个有缺陷的 divide 实现
→ 增加确定性自测
→ 运行测试并得到非零退出证据
→ 根据失败结果精确 Patch
→ 再次运行测试并看到 ACCEPTANCE_OK
→ git_diff 确认只修改允许文件
→ 最终回答
```

Fixture 位于
`cc-java-cli/src/test/resources/fixtures/s04-coding-loop/`，包含：

- `TASK.md`：与 PRD S04 验收任务一致的 `divide`、零除数异常和测试要求；
- `src/Calculator.java`：初始代码和可执行 `--self-test`；
- `AGENTS.md`：允许修改范围和验证要求；
- `DO_NOT_EDIT.txt`：审批越权负例。

## 2. 前置条件

- Windows、Linux 或 macOS；
- JDK 21；
- 仓库 Maven Wrapper；
- `git` 和 `java` 可从 PATH 使用；
- 不需要网络、API Key 或真实模型。

## 3. 运行命令

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=S04CodingLoopFixtureTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

## 4. 可核验观察点

测试固定断言：

1. `DO_NOT_EDIT.txt` 的 Patch 返回 `DENIED`，文件保持原样；
2. 第一次 `divide` 实现对零除数返回 `0`，自测以非零退出；
3. Scripted Model 只能在收到失败 Tool Result 后提出第二次 Patch；
4. 第二次命令返回 `exitCode: 0` 和 `ACCEPTANCE_OK`；
5. `git_diff` 只包含 `src/Calculator.java`；
6. Run 以 `COMPLETED` 结束，共 9 次 Tool Call、10 个模型回合；
7. Fixture 在构建目录的唯一临时仓库中执行，结束后清理，不修改源 Fixture。

2026-07-30 的工作区实测结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 5. 事实边界

- 这是普通 CI 可重复的单个 Seed Task，证明 `EVAL-01` 的学习骨架，不是 S14 任务集、
  成功率、成本或真实模型评测；
- Scripted Model 固定 Tool 意图，但每一步都必须消费真实 Tool Result，不能直接修改
  Fixture；
- 审批是应用层控制，不是 OS Sandbox；获准 Shell 仍使用当前用户权限；
- 后台命令、Session Allow、持久 Permission、Checkpoint/Undo 和攻击性安全 Eval
  仍分别属于 S05、S06、S12-S14。
