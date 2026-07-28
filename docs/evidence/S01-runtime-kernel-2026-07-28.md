# S01 Runtime Kernel 标准验证证据

> Stage：S01 — Runtime Kernel（Agent Loop）
> Status：Accepted（G0-G6 Passed）
> Release：`0.1.0-SNAPSHOT`
> Reference Behavior Baseline：`R2026.03`
> Authorized Snapshot ID：`N/A - Not Used`
> Feature IDs：`BOOT-05`、`LOOP-01/02/03/05/06/07`、`MODEL-01/03`、
> `TOOL-01/02/03/13`、`HOOK-01`、`CTX-01`、`SESSION-01/02`、`OBS-01`、`EVAL-02`
> Current → Exit Target：19 项 `L1 → L1`（本次关闭 Stage Exit 证据，不提升 Capability Level）
> Date：2026-07-28

S01 的设计和代码在未核验材料被登记前已经完成。Verified Commit、测试与等级只依赖
ADR-017、本项目需求、公开基线和独立 Fake；后续比较性阅读不是实现来源或测试 Oracle。

## 1. 被测代码身份

| 字段 | 值 |
| --- | --- |
| Verified Commit | `5ef0bbbf54c75fcc3c8479c2c52bfbaa29beaabd` |
| Code / Build Digest | `04886d5d1ab9` |
| Digest Scope | Java 源码、测试源码、全部 POM、Maven Wrapper、`.mvn` 与仓库脚本 |
| Worktree State | 复验前后均为 Clean |
| Formal G4 Identity | Passed；全部标准命令在 Verified Commit 上执行 |

`Code / Build Worktree Digest` 由
[`scripts/ProgressDashboard.java`](../../scripts/ProgressDashboard.java)按路径排序后，对每个
代码/构建输入的规范化内容做 SHA-256，再生成树摘要。Git Commit 固定被测仓库状态，
摘要用于让进度看板检测后续代码或构建输入变化；两者共同形成本次 G4 的代码身份。

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
| 2026-07-28 18:20:39 | `.\mvnw.cmd clean verify` | `BUILD SUCCESS`；6/6 Reactor 模块成功；Core 23/23 |
| 2026-07-28 18:22:13 | `.\mvnw.cmd -DskipTests javadoc:aggregate` | `BUILD SUCCESS` |
| 2026-07-28 18:23:03 | `.\mvnw.cmd -pl cc-java-core -am test` | `BUILD SUCCESS`；23 通过、0 失败、0 错误、0 跳过 |

Core 报告：

| Suite | Passed / Total | SHA-256 |
| --- | ---: | --- |
| `AgentRuntimeTest` | 19 / 19 | `C0AE21FEE987451C47EFE95C877953B00CD3877F6A66D4B62E3F3D54FE012329` |
| `ScriptedModelGatewayTest` | 2 / 2 | `D3B2D14D40713388463635C49B81589452AC103DC5C0949BE0A16BCAD0D1ED42` |
| `ToolRegistryTest` | 2 / 2 | `9757C7CB23B31C0BF99824C089457E63FF4FD955DFF688D3ADA79ED4BB7A9E57` |

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

实际结果（2026-07-28 18:23:34，Asia/Shanghai）：

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
SHA-256 F77AE044C9A1A606D9E4553AAC675FEACB93ACABC6E0F2379C3DFC320C38A573
```

本节满足 G5 对前置条件、可复制命令、实际结果、负例和事实边界的要求。

## 5. G6 对账状态

- 19 项 Capability 保持 L1；活动矩阵纠正为 193 项后能力覆盖为 3.28%，没有因测试
  通过虚增等级；
- README、PRD、技术设计、ADR、矩阵、Demo、Gap Report 和进度看板在本轮变更中对账；
- Windows Wrapper、标准命令和可核验 Demo 三个执行缺口已经关闭；
- G4 的全部命令已在 Clean 的 Verified Commit 上复验，测试后工作区仍为 Clean；
- G0-G6 均为 Passed，S01 Stage Exit 为 Accepted。

## 6. Exit 决定

S01 已满足本阶段 L1 学习骨架的退出条件，没有剩余 Exit Blocker。退出对账提交只更新
文档、证据状态和生成的进度页，不改变 Verified Commit 的 Java、POM、Wrapper 或仓库
脚本输入。S02 当前位于启动 Gate：ADR-021 已固定 Feature ID 和目标等级；生产实现前
仍须完成官方来源/版本核验、Provider/Streaming/CLI Spike 与技术选择。
