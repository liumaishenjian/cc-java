# S01 Runtime Kernel 标准验证证据

> Stage：S01 — Runtime Kernel（Agent Loop）
> Status：In Progress（执行验证完成；Commit-scoped G4/G6 待完成）
> Release：`0.1.0-SNAPSHOT`
> Reference Behavior Baseline：`R2026.03`
> Authorized Snapshot ID：`AUTH-SRC-2026-03-31-A`
> Feature IDs：`BOOT-05`、`LOOP-01/02/03/05/06/07`、`MODEL-01/03`、
> `TOOL-01/02/03/13`、`HOOK-01`、`CTX-01`、`SESSION-01/02`、`OBS-01`、`EVAL-02`
> Current → Exit Target：19 项 `L1 → L1`（本次关闭 Stage Exit 证据，不提升 Capability Level）
> Date：2026-07-28

## 1. 被测代码身份

| 字段 | 值 |
| --- | --- |
| Base Commit | `27129342087af68d957f10c52ed807c64778fbad` |
| Code / Build Worktree Digest | `04886d5d1ab9` |
| Digest Scope | Java 源码、测试源码、全部 POM、Maven Wrapper、`.mvn` 与仓库脚本 |
| Worktree State | 非 Clean；包含本轮 Wrapper、文档和看板改动 |
| Formal G4 Identity | Open；需明确授权 Commit 后在稳定 Commit 上复验 |

`Code / Build Worktree Digest` 由
[`scripts/ProgressDashboard.java`](../../scripts/ProgressDashboard.java)按路径排序后，对每个
代码/构建输入的规范化内容做 SHA-256，再生成树摘要。它能够防止看板继续引用已经变化的
本地实现，但不替代 Git Commit。因此本文件可以核验本轮执行事实，不能单独把 G4 标记为
最终通过。

## 2. Windows Maven Wrapper 缺口

### 2.1 根因

Maven Wrapper 3.3.4 的 Windows 脚本在普通 `.m2` 目录上直接读取
`(Get-Item ...).Target[0]`。普通目录的 `Target` 在 Windows PowerShell 5.1 中为
`$null`，索引操作会在 Maven 启动前失败。

本项目把 `Target` 规范化为零或一个非空目标：普通目录回退到
`<MAVEN_USER_HOME>/wrapper/dists`，链接目录继续使用其目标。该问题也记录在 Apache
Maven Wrapper 官方仓库的
[#395](https://github.com/apache/maven-wrapper/issues/395)；截至本次验证日期，候选修复
[#416](https://github.com/apache/maven-wrapper/pull/416)仍未形成可直接采用的已发布版本。

### 2.2 修复后结果

```text
Apache Maven 3.9.16
Java version: 21.0.11, vendor: Eclipse Adoptium
OS name: windows 10, version: 10.0, arch: amd64
```

`.\mvnw.cmd --version` 在普通 `C:\Users\yliu27\.m2` 目录上返回退出码 `0`，证明脚本已经
进入并启动仓库固定的 Maven 3.9.16。

## 3. G4 标准验证运行

环境：

```text
Windows 10 10.0 amd64
Eclipse Temurin 21.0.11+10
Maven Wrapper 3.3.4
Apache Maven 3.9.16
Default locale zh_CN
Platform encoding UTF-8
```

| 完成时间（Asia/Shanghai） | Command | Result |
| --- | --- | --- |
| 2026-07-28 17:52:18 | `.\mvnw.cmd clean verify` | `BUILD SUCCESS`；6/6 Reactor 模块成功；Core 23/23 |
| 2026-07-28 17:55:21 | `.\mvnw.cmd -DskipTests javadoc:aggregate` | `BUILD SUCCESS` |
| 2026-07-28 17:56:11 | `.\mvnw.cmd -pl cc-java-core -am test` | `BUILD SUCCESS`；23 通过、0 失败、0 错误、0 跳过 |

Core 报告：

| Suite | Passed / Total | SHA-256 |
| --- | ---: | --- |
| `AgentRuntimeTest` | 19 / 19 | `CD568F73A772FE0B91B3C711CDEE6DE77815832EE6FADC1FB8227E65A62DEE7A` |
| `ScriptedModelGatewayTest` | 2 / 2 | `F47831EC8917B64FEDE9A3F5681B21BA66DADD528A901F080A188E831E7BF9C0` |
| `ToolRegistryTest` | 2 / 2 | `BC2C5C435B83BE4E07A96EE645B4D7D6A9B49ECA5C3C5CFF8159210CD8043436` |

这些哈希标识本次本地 Surefire XML，不是跨机器 Golden Output；报告含时间、路径和环境
属性，重新执行时字节哈希可以变化。可复验依据是稳定 Commit、命令、测试名称、断言和
通过计数。

### 3.1 覆盖范围

- 正常路径：直接 Final、单轮和多轮 Tool Loop；
- 协议边界：单回合多个 Tool Call、唯一 Assistant Message、Call/Result ID 精确配对；
- 失败与恢复：模型异常、空响应、未知 Tool、非法参数、Tool 异常和审批拒绝；
- 预算边界：模型回合上限、Tool Call 上限、整批 Tool Call 原子拒绝；
- 可观察性：生命周期事件顺序和唯一 Run 终态；
- Session：同一进程内跨 Run 的追加式消息历史；
- 安全边界：S01 只验证 Fake Permission/Approval 拒绝路径，不声明真实副作用权限或
  OS Sandbox。

模型流取消与流式中断、文件边界、Shell/进程取消、完整权限策略、持久化恢复与 OS 隔离
分别 Deferred 到 S02、S03、S04、S05、S06 和 S13，不伪造尚未进入 S01 的测试结果。

### 3.2 量化结果

| Metric | Before | After |
| --- | ---: | ---: |
| Windows Wrapper 可启动固定 Maven | 0 | 1 |
| Reactor 成功模块 | 0 / 6 | 6 / 6 |
| 标准 Core 测试 | 未进入 Maven | 23 / 23 |
| 聚焦 Demo 场景 | 无正式结果 | 5 / 5 |

## 4. G5 可复现 Demo

运行命令：

```powershell
.\mvnw.cmd -pl cc-java-core -am `
  "-Dtest=AgentRuntimeTest#continuesUntilFinalResponseAcrossMultipleToolTurns+appendsMultiCallAssistantMessageExactlyOnce+rejectsEntireMultiCallBatchWhenRemainingBudgetIsInsufficient+returnsStructuredUnknownToolResultAndLetsModelRecover+emitsOrderedEventsForToolLoop" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dsurefire.reportNameSuffix=s01-demo" `
  test
```

实际结果（2026-07-28 17:56:45，Asia/Shanghai）：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 类型 | 场景 | 观察点 |
| --- | --- | --- |
| 正例 | `continuesUntilFinalResponseAcrossMultipleToolTurns` | Tool Result 驱动下一模型回合直至 Final |
| 正例 | `appendsMultiCallAssistantMessageExactlyOnce` | 多 Tool Call 只写入一条 Assistant Message |
| 负例 | `rejectsEntireMultiCallBatchWhenRemainingBudgetIsInsufficient` | 预算不足时整批不写入、不执行 |
| 恢复 | `returnsStructuredUnknownToolResultAndLetsModelRecover` | 未知 Tool 转为结构化结果，模型可恢复 |
| 顺序 | `emitsOrderedEventsForToolLoop` | Model/Permission/Tool/Run 事件保持控制流顺序 |

Demo Surefire XML：

```text
cc-java-core/target/surefire-reports/
TEST-io.github.liumaishenjian.ccjava.core.AgentRuntimeTest-s01-demo.xml
SHA-256 D122F770E1038C06BA7D5CF0F236CACAA273D0ED6AC59311369E854D5571A9CB
```

本节满足 G5 对前置条件、可复制命令、实际结果、负例和事实边界的要求。

## 5. G6 对账状态

- 19 项 Capability 保持 L1，能力覆盖仍为 3.25%，没有因测试通过虚增等级；
- README、PRD、技术设计、ADR、矩阵、Demo、Gap Report 和进度看板在本轮变更中对账；
- Windows Wrapper、标准命令和可核验 Demo 三个执行缺口已经关闭；
- G4 仍缺稳定 Commit 上的同命令复验，因此 G6 与 S01 Stage Exit 必须保持 Open；
- 未获得单独 Git Commit 授权前，不提交、不把 S01 标为 Accepted，也不启动 S02。

## 6. 唯一剩余 Exit Blocker

获得明确 Git Commit 授权后：

1. 把当前 S01 代码、Wrapper、证据与文档固化为稳定 Commit；
2. 在该 Commit 上重跑本文件第 3、4 节命令；
3. 把 Commit 和复验结果写回本证据包；
4. 将 G4、G6 与 S01 Stage Exit 更新为 Passed / Accepted；
5. 再以独立变更启动 S02。
