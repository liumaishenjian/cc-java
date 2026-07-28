# ADR-017：S01 Runtime Kernel 与规范消息历史

> 状态：Accepted
> 日期：2026-07-28
> Release：`0.1.0-SNAPSHOT`
> Learning Stage：S01 — Runtime Kernel（Agent Loop）
>
> Follow-up：后续 [ADR-018](./ADR-018-authorized-reference-study.md)扩展了参考研究来源与
> 证据 Gate；本 ADR 仍准确记录 S01 当时依据公开需求独立设计的历史事实。

## 1. 背景

S01 的目标是建立一个可离线验证、可解释的 Agent Runtime 学习骨架，而不是提前交付可用于真实仓库的 Coding Agent。该骨架需要证明以下边界能够成立：

- Domain 协议不依赖 Spring AI、Reactor、CLI、文件系统或持久化框架；
- 应用代码而非模型 SDK 掌握 `User → Model → Tool → Model` 控制循环；
- Tool Call 无论来源如何，最终都必须进入同一个 `ToolExecutionPipeline`；
- Session 中的消息、Tool Call ID、Tool Result ID 和终止原因具有确定的协议；
- 普通测试不依赖网络模型、API Key 或真实副作用。

本 ADR 关闭 S01 的 Runtime Kernel 设计选择，但不关闭后续 Stage 的能力差距。

相关需求：`FR-AGENT-001`～`FR-AGENT-005`、`FR-AGENT-008`、`FR-TOOL-001`～`FR-TOOL-003`、`FR-SESSION-001`～`FR-SESSION-002`、`FR-EVENT-001`、`NFR-001`～`NFR-005`、`NFR-020`～`NFR-021`、`NFR-024`。

相关 Feature ID：`BOOT-05`、`LOOP-01`、`LOOP-02`、`LOOP-03`、`LOOP-05`、`LOOP-06`、`LOOP-07`、`MODEL-01`、`MODEL-03`、`TOOL-01`、`TOOL-02`、`TOOL-03`、`TOOL-13`、`HOOK-01`、`CTX-01`、`SESSION-01`、`SESSION-02`、`OBS-01`、`EVAL-02`。

## 2. Clean-room 边界

S01 采用 clean-room 独立实现：

1. 行为需求只来自本仓库的产品需求、技术设计、功能对照矩阵，以及其中登记的公开一手参考资料。
2. 不读取、复制、翻译或凭记忆还原受限制产品的源码、反编译结果、内部 Prompt、私有类型名、错误文案、常量、Session Schema 或测试 Golden Output。
3. Java 类型、消息协议、状态机、错误分类和测试 Fixture 均由本项目依据自身需求独立命名和设计。
4. 黑盒参考只用于确认可观察行为类别；本项目的离线验收场景和断言独立编写。
5. 本 ADR 记录的是 `cc-java` 的已接受契约，不表示与任何参考产品存在官方关系，也不表示已经达到行为对等。

若某项行为无法从公开资料和本项目需求独立解释，则不进入 S01 实现。

## 3. 已接受决策

### 3.1 固定构建基线与五模块边界

S01 固定以下工程基线：

| 项目 | 已接受值 |
| --- | --- |
| Java | 21 |
| Maven Wrapper | 3.3.4，仅脚本分发 |
| Wrapper 使用的 Maven | 3.9.16 |
| Maven `groupId` | `io.github.liumaishenjian` |
| 模块 | `cc-java-domain`、`cc-java-core`、`cc-java-model-spring-ai`、`cc-java-tools-local`、`cc-java-cli` |

`cc-java-domain` 保存框架无关的不可变协议和值对象；`cc-java-core` 保存 Runtime、Session、Model/Tool Port、Pipeline、Limits 和事件骨架。其余三个模块在 S01 只建立稳定边界，不接入真实模型、终端或本地工具。

### 3.2 Runtime 使用显式同步控制流

`AgentRuntime` 是 S01 唯一的 Agent Loop 入口。一次 Run 采用普通 Java 同步控制流：

1. 向当前内存 Session 追加一条 User Message；
2. 由 `ContextAssembler` 形成单回合 `ModelRequest`；
3. `ModelGateway` 返回已经聚合完成的 `ModelTurn`；
4. 没有 Tool Call 时，追加一次最终 Assistant Message 并以 `COMPLETED` 结束；
5. 存在 Tool Call 时，先执行批次级协议与预算预检，再顺序执行整个批次；
6. 全部 Tool Result 追加完成后，才请求下一个模型回合。

S01 不把 Reactive、流式 Chunk 或 Provider SDK 类型泄漏到 Core。流式适配属于 S02，但后续适配器仍须把完整回合交回显式 Runtime。

### 3.3 Assistant 与同批 Tool Call 只追加一次

一个模型回合中的 Assistant 文本和全部 Tool Call 构成同一条规范 Assistant Message。无论该批包含一个还是多个调用，都只向 canonical history 追加一次，不得为每个 Tool Call 重复追加 Assistant Message。

对一个已经通过批次预检的 Tool Call 批次：

- 按模型给出的顺序执行；
- 每个 Tool Call 恰好追加一个 Tool Result；
- Tool Result 保留原 Tool Call ID 和工具名；
- 整批完成后才允许进入下一模型回合；
- 批内重复 ID 或 Session 历史中的重复 ID 被视为无效模型响应。

因此，一个双 Tool Call 回合的规范历史形状是：

```text
User
Assistant(calls=[call-1, call-2])   # 只出现一次
ToolResult(callId=call-1)
ToolResult(callId=call-2)
Assistant(final)
```

### 3.4 Tool 预算采用批次原子预检

模型返回一批 Tool Call 后，Runtime 在修改 canonical history 前检查剩余 Tool Call 预算。

- 预算充足：Assistant Message 整体追加一次，然后顺序执行所有调用。
- 预算不足：以 `TOOL_LIMIT_REACHED` 停止；该 Assistant Tool-Call 批次及其任何结果都不进入 canonical history。

不采用“先追加 Assistant，再执行能负担的前几个 Tool”的部分提交方式。部分提交会留下没有 Result 的调用，破坏 Call/Result 成对不变量，并给后续恢复造成歧义。

### 3.5 所有调用进入统一 Tool Pipeline

S01 的 `ToolExecutionPipeline` 建立统一骨架，负责把一次原始调用确定性地转换为规范 `ToolResult`。当前管线覆盖：

- Tool Registry 查询；
- 参数校验；
- 最小 Permission Gate；
- `ASK` 时调用 Approval Port；
- 同步执行；
- 生命周期事件；
- 结果规范化与错误转换。

未知工具、非法参数和执行异常不作为未经分类的异常泄漏给模型，而是转换成结构化 Tool Error，并保留原始 Call ID：

| 场景 | 结构化分类 |
| --- | --- |
| Registry 中不存在工具 | `UNKNOWN_TOOL` |
| 参数不满足校验或校验器失败 | `INVALID_ARGUMENTS` |
| Tool 执行抛出异常 | `EXECUTION_FAILED` |

这些失败结果仍作为对应的 Tool Result 进入消息历史，使 Fake Model 能在下一回合观察并纠正。输出裁剪、脱敏、真实超时和取消仍是后续 Stage 的管线扩展点。

### 3.6 Session、Limits、Stop Reason 与事件保持显式

S01 使用单进程 `InMemorySessionStore`。Session 保存：

- Session ID、Run ID；
- 追加式 canonical message history；
- 单调递增且有序的生命周期事件；
- Tool Call/Result ID 不变量；
- 当前活动 Run 状态。

S01 实际执行的限额只有最大模型回合数和最大 Tool Call 数；Tool Call 数按批次原子预检。当前 Runtime 主动产生的终止原因包括：

- `COMPLETED`；
- `MODEL_ERROR`；
- `INVALID_MODEL_RESPONSE`；
- `TURN_LIMIT_REACHED`；
- `TOOL_LIMIT_REACHED`；
- `INTERNAL_ERROR`。

领域枚举中为后续 Stage 保留的取消、时间、Context、权限和 Tool 终止值，不等于 S01 已经实现这些控制路径。

离散事件覆盖 Session、Run、Model Turn、Tool、Permission 和 Run Finished 生命周期。S01 的事件用于观察和测试，不是用户可配置 Hook DSL。

### 3.7 Permission/Approval 仅建立最小接缝

S01 只接受以下最小契约：

- `PermissionGate` 对已校验调用返回 `ALLOW`、`ASK` 或 `DENY`；
- `ASK` 交给 `ApprovalHandler`，其必须返回最终 `ALLOW` 或 `DENY`；
- Pipeline 在执行 Tool 前应用上述决定；
- 测试中的 Fake Gate 与 Fake Approval 保持确定性。

该接缝不是完整权限系统，更不是 OS Sandbox。模式、规则、硬拒绝、作用域化 Session Approval、终端审批 UI 和真实隔离分别留给 S04、S05 与 S13。

### 3.8 Fake 只存在于测试源码

Scripted Fake Model、Fake Tool、Fake Permission、Fake Approval、确定性 ID 和事件收集器只放在测试源码中。生产源码只定义协议、Port 与 Runtime 实现，不内置测试替身，也不需要 API Key。

普通 S01 测试必须能够在没有真实模型 Provider 的条件下运行。测试数量与通过状态以 Maven Surefire 测试报告为准，不在 ADR 中手工记录可能过期的数字。

## 4. 结果与取舍

### 正面结果

- Agent Loop 的所有权明确留在应用代码中，可用确定性测试解释每次状态迁移。
- canonical history 不会因为批量调用或预算不足产生悬空 Tool Call。
- Tool Result ID 和错误分类稳定，后续模型适配器、CLI、MCP 与 Plugin 可以复用同一协议。
- Framework-free Domain 和同步 Core 降低了首个学习切片的依赖与调试成本。
- 后续流式、权限、持久化和安全能力都有明确扩展接缝。

### 代价

- S01 不能调用真实模型，不能提供交互式体验，也不能操作真实仓库。
- 顺序同步执行不提供流式反馈、并发、取消或超时。
- 内存 Session 在进程退出后丢失，不能恢复未完成调用。
- 最小 Permission seam 只能验证控制点存在，不能提供真实安全保证。
- S01 只达到矩阵的 L1 学习骨架，不构成 L2 可用能力或 L3 参考可比能力。

## 5. 未选择的方案

### 5.1 让模型 SDK 自动运行 Tool Loop

拒绝。自动循环会隐藏 canonical history、预算、Permission、事件和 Stop Reason 的控制点，也会妨碍后续在多个模型适配器之间保持一致语义。

### 5.2 按单个 Tool Call 追加同一 Assistant Message

拒绝。它会复制模型回合、破坏消息协议，并使 Call/Result 配对和 Context 统计失真。

### 5.3 预算不足时执行批次的一部分

拒绝。部分执行会产生悬空调用和不明确副作用，无法提供原子、可恢复的 canonical history。

### 5.4 在 S01 接入 Spring AI、CLI 或真实本地工具

拒绝。它们会把 Provider、终端和文件/进程风险混入 Runtime Kernel 的协议验证；这些能力按矩阵分别进入 S02～S04。

## 6. 明确未实现

以下内容不属于 S01 完成声明：

- 真实模型 Provider、Spring AI Adapter、Token/Usage 与重试；
- 文本或 Tool Call 流式聚合、交互式/Print CLI；
- list、search、read、write、patch、Git、Shell 等真实工具；
- 真实 Permission Policy、Approval UI、Session 授权缓存、硬拒绝与 OS Sandbox；
- 取消传播、deadline、Tool/Run 超时和进程树清理；
- Token、时间、Context、输出大小预算与结果裁剪/脱敏；
- Session 持久化、JSONL、continue/resume/fork、Checkpoint、Undo 与崩溃恢复；
- Hooks、MCP、Skills、Plugins、Sub-Agent、Worktree 与生产可观测性。

## 7. 验证证据

Windows 仓库根目录执行：

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -DskipTests javadoc:aggregate
.\mvnw.cmd -pl cc-java-core -am test
```

2026-07-28 的标准工作区验证使用 Windows 10、Eclipse Temurin 21.0.11 和 Maven
3.9.16：六个 Reactor 模块 `clean verify` 成功，聚合 Javadoc 成功，Core 23 个测试
全部通过；包含预算拒绝负例的聚焦 Demo 5 个场景全部通过。

Windows Wrapper 的原缺陷来自普通 `.m2` 目录的 `Target` 为 `$null`，脚本却直接索引
`Target[0]`。本项目将链接目标规范化后为普通目录保留
`<MAVEN_USER_HOME>/wrapper/dists` 回退。Apache Maven Wrapper 官方仓库的
[#395](https://github.com/apache/maven-wrapper/issues/395)记录了同类问题。

这些命令验证 Domain 与 Core 的离线测试路径，不需要模型 API Key。完整环境、工作区
身份、测试报告哈希和 G4/G5 边界见
[`S01 标准验证证据`](../evidence/S01-runtime-kernel-2026-07-28.md)，具体场景与观察方法见
[`S01 Agent Loop Demo`](../demos/S01-agent-loop.md)，当前差距见
[`S01 差距报告`](../gap-reports/S01.md)。上述标准链已经在 Commit
`5ef0bbbf54c75fcc3c8479c2c52bfbaa29beaabd` 的 Clean 工作区上复验；G4/G6 和 S01
Stage Exit 已通过。
