# cc-java 产品需求文档

> 文档状态：Draft v0.9
>
> 最后更新：2026-08-04
>
> 当前阶段：S01-S06 已 Accepted；S07 Context Engineering 已完成 G0-G2，并已有 Projection、Memory、内部 Usage View 与 deterministic Fake G3-G5 证据；
> Capability Level 未提升，G6 与 Stage Exit 仍 Open
>
> 产品负责人：项目维护者

## 1. 定位演进

- v0.1 将项目过度绑定在自动 FixBug 场景上；
- v0.2 修正为通用 Java Coding Agent Runtime 与 CLI；
- v0.3 进一步明确：这是一个参考驱动的学习型 Java 重实现项目。
- v0.4 引入公开行为基线和统一 Stage 证据 Gate。
- v0.5 明确跨 Stage 目标等级、前置依赖、CLI 归属和 S07 渐进式 Context 路线。
- v0.6 隔离来源、Revision、许可证和授权范围不可核验的材料，撤销其活动设计结论；
  同时固定 S02 的 23 项启动范围。
- v0.7 根据维护者授权确认恢复受控源码学习，并将 S02 调整为 Java Headless Runtime、
  内部 stdio v0 与 React/Ink TUI 的 24 项范围。

最终定位：

> `cc-java` 是一个以 Java 独立实现 Agent Runtime、用成熟终端前端技术提供 CLI 的
> 通用 Coding Agent 学习项目。

项目先把成熟 Coding Agent 拆成完整能力地图，再按子系统独立重实现：Agent 语义、控制流、
工具和安全边界由 Java 承担；交互 Surface 可以使用更适合终端 UI 的技术。每个阶段都维护
公开行为基线、本项目设计、测试和差距，形成可重复证据后再进入独立创新。

它不是“只做一个 MVP 就自由生长”，也不是逐行翻译任何参考源码。它要在授权和发布边界内完成：

```text
建立参考基线
→ 理解架构问题
→ Java Runtime 与独立 Surface 重实现
→ 行为对照
→ 补齐差距
→ 基于证据创新
```

FixBug、代码审查、测试生成和日志调查只是建立在 Runtime 之上的用例，不进入核心领域模型。

## 2. 产品愿景

用户进入任意代码仓库后，可以直接启动 `cc-java`，用自然语言委托开发任务：

```text
cd my-project
cc-java

> 阅读这个项目，给订单创建接口增加幂等保护，并运行相关测试
```

CLI 应当能够：

1. 收集项目上下文；
2. 自主选择并调用工具；
3. 在有副作用的操作前执行权限判断；
4. 展示过程、请求批准并允许用户取消；
5. 修改代码并运行验证；
6. 输出结果、证据、风险和未完成项；
7. 在后续版本中恢复会话、加载扩展和委托子 Agent。

项目同时维护 [参考架构研究](./reference-architecture.md) 和 [功能对照矩阵](./feature-parity-matrix.md)。因此任何时候都可以回答：

- 当前 Java 版本处于哪个学习阶段；
- 与参考基线相比缺少哪些能力；
- 每项差距解决什么真实问题；
- 下一项实现如何被验证；
- 哪些设计已经从“复现”进入“创新”。

## 3. 为什么选择 Java

- 为 Java/Spring 开发者提供可读、可调试、可扩展的 Agent 工程参考；
- 用 Spring AI 接入模型，但不把 Runtime 绑定到某个模型 SDK；
- 验证 Java 在流式终端、工具执行、权限控制、会话和 MCP 领域的工程能力；
- 形成区别于 TypeScript/Python Agent 项目的开源作品。

项目不是为了证明“Java 可以翻译某份 TypeScript 源码”，而是用 Java 独立实现成熟 Coding Agent 中可复用的 Harness 设计。

## 4. 产品目标

### 4.1 核心目标

- G-001：提供可交互和非交互的通用 Agent CLI。
- G-002：实现由项目自身控制的 Agent Loop，而不是把完整循环交给 Spring AI。
- G-003：提供统一 Tool Runtime，使内置工具、MCP 工具和未来插件经过同一执行管线。
- G-004：把权限、审批、限制、取消和生命周期事件作为 Runtime 基础能力。
- G-005：支持从简单内存上下文演进到可恢复会话、压缩和持久记忆。
- G-006：保持模型、终端、工具和存储适配器可替换。
- G-007：维护版本化参考基线和逐项 Capability Parity。
- G-008：每个 Stage 都包含来源记录、机制研究、设计说明、代码、测试、Demo 和差距复盘。
- G-009：所有公开参考结论区分 `Documented / Observed / Inferred / Unknown`，所有能力
  声明区分参考行为、Java 设计和已验证实现。

### 4.2 学习与开源目标

- G-010：通过可运行代码掌握 Agent Loop、Tool Calling、Context Engineering 和 Harness Engineering。
- G-011：保留架构决策、评测和演进记录，让项目能作为 Java Agent 学习材料。
- G-012：提供一套能被其他 Java 项目嵌入的 Runtime 基础。
- G-013：只使用已登记的公开来源、授权研究输入和独立行为场景进行重实现，不复制或逐行
  翻译受保护的源码表达。
- G-014：关键模块必须由维护者能够独立解释，而不只是由 AI 生成。

## 5. 非目标

- 不追求首版与 Claude Code、Codex 或其他成熟产品功能对等；
- 不使用、改写或再发布泄露、未授权或超出授权范围的源码；
- 不兼容某个商业产品的私有配置格式、内部事件格式或隐藏 API；
- 不把 FixBug、测试或电商业务写进 Runtime Core；
- 不在第一轮重实现中同时完成生产级全功能 TUI、桌面端、IDE 插件或云端执行；S02 只完成
  支撑流式会话的最小 React/Ink TUI；
- 不跳过基础子系统直接堆叠多 Agent、插件市场或企业策略中心；
- 不承诺模型生成的修改一定正确；
- 不把 Spring AI 当作产品架构本身。

## 6. 目标用户

### 6.1 Java 后端开发者

希望在终端中委托代码解释、修改、测试、重构和排障任务，并能理解 Agent 的执行过程。

### 6.2 Agent 开发学习者

希望通过一个真实项目理解 Coding Agent 的 Runtime、Tool、Permission、Context、Session 和扩展系统。

### 6.3 Java Agent 平台开发者

希望复用 Runtime 或扩展接口，构建团队内部 Coding Agent、测试 Agent 或自动化场景。

## 7. 核心用户体验

### 7.1 交互模式

计划中的基础入口：

```text
cc-java [--workspace <path>]
```

行为：

- 默认以当前目录为 Workspace；
- 启动交互会话并显示当前模型、权限模式和 Workspace；
- 用户可以连续发送任务和补充信息；
- 模型文本和工具执行状态逐步显示；
- 有副作用的工具调用需要终端确认；
- `Ctrl+C` 取消当前模型或工具执行，但不立即退出会话；
- `/exit` 结束会话。

### 7.2 Print 模式

计划中的非交互入口：

```text
cc-java --print "解释订单创建流程"
cc-java --workspace . --model model-name --timeout 30s --print "解释订单创建流程"
```

用于脚本和 CI。首个 Print 实现不得弹出无法处理的交互审批；遇到未预授权写操作时应拒绝并返回明确退出码。

### 7.3 Agent 工作循环

用户看到的是一个连续过程，而不是固定业务流程：

```text
Gather context
→ Reason
→ Request tool
→ Permission / Approval
→ Execute
→ Observe result
→ Continue or finish
```

“调查、行动、验证”可以交替多次，Runtime 不预设任务一定是 FixBug。

## 8. 产品能力地图

| 能力域 | 职责 |
| --- | --- |
| CLI / Terminal | 参数、REPL、流式展示、审批、取消、退出码 |
| Agent Runtime | 消息循环、状态、终止、重试、预算 |
| Model | Provider 适配、流式文本、Tool Call、Usage |
| Tool Runtime | 注册、Schema、执行管线、结果裁剪、错误 |
| Permission | 模式、规则、审批、硬拒绝 |
| Context | 项目指令、消息、工具结果、Token 压力、压缩 |
| Session | Transcript、恢复、分叉、Checkpoint |
| Extensions | Hooks、Skills、MCP、Plugins |
| Advanced Agency | Sub-Agent、后台任务、Worktree、Sandbox |
| Observability | Agent Event、耗时、Token、费用、审计 |

## 9. 学习型重实现路线

| 阶段组 | Stage | 学习目标 |
| --- | --- | --- |
| 参考建模 | S00 | Harness 地图、公开行为基线、授权研究、术语、能力矩阵和来源规则 |
| 核心重实现 | S01-S04 | Agent Loop、Streaming CLI、Tools、Write/Command |
| 可靠性重实现 | S05-S08 | Permission、Session、Checkpoint、Context、Compaction、Instructions、Settings |
| 扩展重实现 | S09-S11 | Hooks、MCP、Skills、Plugins |
| 高级能力重实现 | S12-S14 | Sub-Agent、Worktree、Sandbox、Eval、SDK、发行 |
| 独立创新 | S15 | 在对照基线之上验证 Java/Spring 差异化 |

每个 Stage 的功能清单和完成定义见 [功能对照矩阵](./feature-parity-matrix.md)。

FixBug 可以在 S11 后实现为 Skill 或独立应用，也可以作为 S04 的测试任务，但不改变 Runtime 主线。

## 10. 第一轮 Java 重实现：S01-S04

第一轮不是项目终点，只是把 Agent Harness 的最小骨架变成一个可以观察和实验的运行系统。它分为四个学习增量。

### S01：Runtime Kernel

- 不接真实模型，先使用 Scripted Fake Model；
- 实现单模型回合和多轮 Tool Calling；
- 实现 Tool Execution Pipeline；
- 实现最小 Permission Gate 和 Approval Port；
- 实现 Agent Event、模型回合/Tool Call 数量限制和 Stop Reason；
- 只保留 Cancellation 扩展缝隙，不宣称模型流或子进程取消已经可用；
- 实现内存 Session 与追加式 Context；
- 完成离线协议测试。

### S02：Model 与 Streaming CLI

- 接入维护者提供的 OpenAI 兼容模型端点，Spring AI Adapter 仍保持 Provider-neutral
  Core 边界；
- Java Headless Composition Root 提供 `--print` 和实验性 `--stdio`；
- React/Ink TUI 拉起 Java 子进程并提供 Interactive Session；
- 内部 UTF-8 NDJSON v0 只承诺 S02 本地进程通信，不是稳定公共 API；
- 支持流式文本、Tool Call Chunk 聚合和执行状态展示；
- 支持模型流取消、不完整流、输出长度 finish reason、有界停止/续接、限流和 Usage 转换；
- S02 的重试只发生在第一个可见 Delta 前，最多三次并受 Run Deadline/取消约束；
  已输出后的断流 Fail Closed，`length` 以明确停止结束，自动续写留到 S14 评测；
- Windows 验证 `Ctrl+C`、TTY/非 TTY、中文宽字符、粘贴、Resize 和无孤儿进程；
- 用显式启用的真实 Provider E2E 验证 Adapter，但普通 CI 仍只使用 Fake。

### S03：Read Tools

- 提供 `list_files`、`read_file`、`search_text`、`git_status`、`git_diff`；
- `search_text` 生产路径采用受控 ripgrep，支持字面/正则、Glob/type、大小写、多行、
  上下文、content/files/count、offset/limit，并传播 Run 取消；rg 不可用时只允许
  语义等价的字面 content 子集降级，不把 RAG 冒充精确代码检索；
- 加载项目根 `AGENTS.md`；
- 建立 Workspace Realpath、Symlink/Junction、敏感文件和大小边界；
- 为 Tool Result 建立类型化上限、明确截断和可供后续 Context 使用的元数据；
- 对真实公开仓库完成代码理解任务。

### S04：Controlled Coding

- 增加 `apply_patch`；
- 增加 `run_command`；
- 写文件和执行命令默认请求用户批准；
- 展示将执行的命令或补丁摘要；
- 设置进程超时、输出上限和取消传播；
- 修改后展示 Git Diff；
- Agent 可以运行构建或测试并形成最终总结。
- 提供固定的安全 `PLAN` 行为：允许调查，拒绝写文件和有副作用命令；S05 已补充
  可配置模式、可信 Startup Rules 和 Session Allow。

S04 完成后，项目得到第一个可运行的 Mini Coding Agent CLI；随后继续按矩阵学习可靠性和扩展能力。

## 11. 第一轮闭环功能需求

### 11.1 CLI

- FR-CLI-001：不带任务启动时进入交互 REPL。
- FR-CLI-002：支持通过参数执行一次性 Print 任务。
- FR-CLI-003：默认 Workspace 是当前目录，也可显式指定。
- FR-CLI-004：启动时显示 Workspace、模型和权限模式。
- FR-CLI-005：模型文本、工具状态、审批和最终结果有可区分的终端表现。
- FR-CLI-006：`Ctrl+C` 可以取消当前运行，`/exit` 可以结束会话。
- FR-CLI-007：不同结束原因映射到稳定退出码。

### 11.2 Agent Runtime

- FR-AGENT-001：Runtime 接收用户消息并发起模型回合。
- FR-AGENT-002：模型可以返回文本、一个或多个 Tool Call，或二者组合。
- FR-AGENT-003：Runtime 负责完整 Tool Loop，Spring AI Adapter 不得自动执行工具。
- FR-AGENT-004：同一模型回合的 Assistant Message 只能追加一次。
- FR-AGENT-005：每个 Tool Result 必须与 Tool Call ID 一一对应。
- FR-AGENT-006：S01 实现最大模型轮次和工具次数；运行时长、流式输出和进程输出限制
  分别在 S02、S04 完成。
- FR-AGENT-007：S02 将取消传播到模型流；S04 将取消传播到正在运行的工具和子进程树。
- FR-AGENT-008：运行以明确 Stop Reason 结束，不能无限循环。

### 11.3 Model

- FR-MODEL-001：S02 只要求一个可运行 Provider，第二个 Provider 用于后续验证抽象。
- FR-MODEL-002：核心不出现 Spring AI 或 Provider SDK 类型。
- FR-MODEL-003：Adapter 支持文本增量事件，并在回合结束时返回聚合后的 Tool Call。
- FR-MODEL-004：模型异常、限流和无效响应转换成 Runtime 错误。
- FR-MODEL-005：Token Usage 不可用时允许缺省，但不得伪造。

### 11.4 Tool Runtime

- FR-TOOL-001：所有工具具有唯一名称、描述、输入 Schema 和副作用等级。
- FR-TOOL-002：所有来源的工具都必须经过同一 Tool Execution Pipeline。
- FR-TOOL-003：Pipeline 至少执行参数校验、权限判断、审批、执行、结果裁剪、事件发布和错误转换。
- FR-TOOL-004：内置读工具只能访问 Workspace 允许范围。
- FR-TOOL-005：`apply_patch` 只能修改 Workspace 内允许文件。
- FR-TOOL-006：`run_command` 必须显示准确命令、Shell 类型和工作目录后再审批。
- FR-TOOL-007：命令执行支持超时、取消、退出码和 stdout/stderr 上限。
- FR-TOOL-008：模型不能通过工具参数修改 Permission Policy。

### 11.5 Permission

- FR-PERM-001：S05 提供可由 CLI/Composition Root 选择的 `DEFAULT`、安全 `PLAN` 和
  `ACCEPT_EDITS`；模式在一次 Headless Session 装配时固定。
- FR-PERM-002：`DEFAULT` 自动允许普通读取，修改和 Shell 默认询问。
- FR-PERM-003：`PLAN` 禁止修改文件和执行有副作用的命令。
- FR-PERM-004：审批支持允许一次、按可信 Tool/ToolSource/selector 当前会话允许和拒绝；
  持久规则与分层 Settings 仍属于 S08。
- FR-PERM-005：硬拒绝优先于模式、规则和用户会话允许。
- FR-PERM-006：Print 模式遇到需要交互的操作时，若无可信 Startup Allow 则拒绝；
  Startup Allow 仍不能覆盖 Hard Denial 或 Tool Adapter 安全校验。
- FR-PERM-007：规则优先级固定为 Hard Denial → Deny → PLAN → Ask → Allow → Mode/
  Effect Default，不受规则列表顺序影响。
- FR-PERM-008：相同 Session 与 selector 连续两次拒绝后，第三次及以后固定拒绝且不再弹窗；
  新 selector 仍可正常评估。

### 11.6 Context

- FR-CTX-001：S03 加载 Workspace 根目录中的 `AGENTS.md` 作为项目指令；S08 前不引入用户/目录
  分层 Instructions。
- FR-CTX-002：项目指令、记忆、摘要和 Tool/模型输出都只作为不可信 Context，不能扩大工具权限、
  Workspace 或解除 Hard Denial/Recovery Gate。
- FR-CTX-003：工具输出具有类型化大小上限、明确的截断或外置标记。
- FR-CTX-004：S07 保持 S06 Canonical Transcript 不变，每次模型请求构造短生命周期 Context
  Projection；压缩失败、取消或损坏输入不得回写规范 JSONL。
- FR-CTX-005：上下文接近模型限制时按压力条件选择 C1 大载荷缩减、C2 旧 Tool 输出清理、C3
  滚动记忆或 C4 全量摘要；C1-C4 不是固定串行四步，预算满足后立即停止。
- FR-CTX-006：任意 Projection 必须保持完整 Tool Call/Result 配对和批次顺序，协议孤儿数为零；
  活动或未完成 Tool 不进入可删除边界。
- FR-CTX-007：Provider 明确 Overflow 时同一模型回合最多恢复一次且最多新增一次模型请求；重复压力
  由绑定 Run/source revision/tier 的 Thrashing Guard 限制，每层每个来源最多一次摘要尝试，无法安全
  满足预算时以 `CONTEXT_LIMIT_REACHED` 停止。
- FR-CTX-008：摘要为空、失败、取消、返回 Tool Call/Result 协议片段、来源 revision 变化、source
  message ID 未有序精确覆盖、严格 UTF-8 或 byte/token 上限不满足、输出估算未严格降低，或关键
  protected anchor 缺失时，不提交压缩边界并保持上一 Projection 深度相等。摘要 Port 只返回数据，
  不拥有 Tool Registry/Pipeline，也不能发起 Tool Call。
- FR-CTX-009：S07 内部 Context Usage View 按 System、Instructions、Transcript、Tool、Memory、
  Reserved/Free 分类展示有界估算且不泄漏正文；完整 `/context` Slash Command UX 归 S08。
- FR-CTX-010：项目级文件记忆默认位于 `~/.cc-java/projects/<repository-id>/memory`，入口为
  `MEMORY.md`，分 M1 Storage、M2 Index、M3 Catalog、M4 Recall、M5 Projection；M2 最多 200 行
  或 25KB，M3 最多 200 topic 文件。
- FR-CTX-011：记忆类型只使用 `USER_PROFILE`、`WORKING_GUIDANCE`、`PROJECT_STATE`、
  `REFERENCE_POINTER`；记忆是可修正、可删除、可重建的 Projection 输入，不是 Session 事实。
- FR-CTX-012：相关记忆可以并行预取，但消费必须零等待：只使用消费时已完成且通过校验的结果；
  `consumeReady` 不得调用阻塞式 `get/join/wait/sleep` 或等待 monitor，使用无锁单次消费；未完成、
  失败、取消按空结果继续，重复消费者得到独立原因码，当前请求忽略的迟到结果不得再次注入。
  M4 选择计划最多携带 20 个候选，不能由调用者绕过查询上限。
- FR-CTX-013：文件记忆拒绝绝对路径、Traversal、Symlink/Junction、非法 UTF-8、超限和 Secret
  候选；repository-id 不得泄漏 Workspace 绝对路径。M1 单 topic 最多 64KB/2,000 行，frontmatter
  前 16 行内闭合；kebab-case slug 最多 64 字符，单行 description 最多 512 Code Point。上述常量为
  cc-java 独立保守上限，不来自参考实现。
- FR-CTX-014：M1 创建仅允许目标不存在，更新和删除必须匹配读取时 SHA-256；同目录随机暂存只用
  `ATOMIC_MOVE` 提交且不回退非原子写入。M1 成功后 M2 重建失败不得回滚 topic，而应返回不回显
  内容的结构化诊断。

### 11.7 Session 与事件

- FR-SESSION-001：每次启动生成 Session ID。
- FR-SESSION-002：S01-S04 在内存中保存当前会话消息和事件。
- FR-SESSION-003：S06 使用项目自有、版本化、append-only semantic JSONL 保存聚合规范历史，不逐 token 持久化，也不解析商业产品内部 JSONL。
- FR-SESSION-004：Java CLI、Print、stdio 与 TUI 支持 Workspace-bound Create、Continue、Resume、Fork 与 Inspect；Fork 使用新 ID 和 parent lineage，Resume 复用指定 ID。
- FR-SESSION-005：同一 Session 同时只有一个本机 Writer；并发 Writer 明确拒绝，Inspect 只读。S06 不承诺 heartbeat、stale reclaim、网络文件系统或多主机一致性。
- FR-SESSION-006：Assistant Tool Calls、Tool resolved/started/completed 与 Run 唯一终态按 durable 顺序提交；恢复发现未完成 Tool 或潜在副作用时阻止可写 Run，绝不自动重放有副作用操作。
- FR-SESSION-007：写 Tool 执行前创建独立于 Git 的普通文件 Checkpoint；Tool 完成后记录类型化 digest 或 known `ABSENT` post-state。
- FR-SESSION-008：用户可以显式 list/diff/undo 单个 Checkpoint；Undo 必须持有 Writer、Session 非 fenced、没有活动 Run、收到针对具体 Checkpoint 的独立确认，并在最终 Move/Delete 前重检 NOFOLLOW、realpath 与 post digest。
- FR-SESSION-009：Checkpoint 仅恢复受支持的普通文件，不恢复 Symlink/Junction、Shell、进程、网络、远端或权限副作用；Permission、lease 与 Checkpoint 都不是 OS Sandbox。
- FR-EVENT-001：Runtime 发布 Session、Turn、Model、Tool、Permission 和 Stop 事件。
- FR-EVENT-002：终端只消费事件，不直接读取 Runtime 内部状态。
- FR-EVENT-003：默认事件不包含 API Key 或未经裁剪的敏感内容。

## 12. 第一轮对照验收任务

在一个包含自动测试的公开样例仓库中执行：

```text
给 Calculator 增加 divide 方法：
1. 除数为 0 时抛出明确异常；
2. 增加测试；
3. 运行相关测试。
```

S04 完成必须满足：

1. CLI 启动交互会话；
2. Agent 自主搜索和读取相关代码；
3. 修改前展示审批；
4. 拒绝审批时仓库不变化；
5. 批准后通过受控 Patch 修改代码；
6. 执行测试前展示准确命令并再次审批；
7. 测试输出、退出码和 Diff 可见；
8. 最终回答说明修改、验证结果和风险；
9. `PLAN` 模式下同一任务不会产生修改；
10. 中途取消不会留下仍在运行的子进程；
11. Fake Model 离线测试覆盖主要 Agent Loop 路径；
12. 普通 CI 不需要 API Key。

## 13. S05-S08：可靠性能力重实现

这一组阶段不是泛化地“优化首版”，而是逐项学习成熟 Harness 如何在真实环境中保持可控和可恢复。

### S05：Permission Pipeline

- `DEFAULT / PLAN / ACCEPT_EDITS` 三种模式；Accept Edits 只自动批准已经通过安全校验的
  Workspace Write，不把不透明 Shell 当作编辑；
- `ALLOW / ASK / DENY` 声明性规则、Tool/规范化参数 selector、进程内 Startup/Session
  来源和非交互策略；User/Project/Managed 持久来源留到 S08/S13；
- Hard Denial、显式 Deny 和 PLAN 限制优先于 Ask/Allow、Session Grant 与人工批准；
- `ALLOW_ONCE / ALLOW_SESSION / DENY` 使用有界 scope，命令 Session Allow 不能含糊地
  变成允许所有 Shell；
- 权限评估、审批、执行、裁剪、脱敏和内部生命周期事件全部经过统一 Pipeline；
- 拒绝结果回传模型，相同 scope 的重复请求有确定性去循环策略；
- 用 Fake MCP/Plugin/Sub-Agent Tool 证明任何来源都不能绕过权限；S10-S12 再验证真实
  Adapter。完整契约见 ADR-039。

### S06：Session 与 Checkpoint

- 项目自有 major 1 append-only semantic JSONL，保存聚合规范消息、Tool durable 状态、Run 终态、Workspace-aware metadata 与 lineage；
- Java CLI/Print/stdio/TUI 共用 Create、`--continue`、`--resume`、`--fork` 与只读 Inspect 组合根；
- 本机 OS `FileLock` 单 Writer、并发打开检测和只读恢复；
- 崩溃后识别未完成 Tool、损坏尾部与潜在副作用，阻止可写恢复并绝不自动重放；
- `apply_patch`/`write_file` 执行前 ordinary-file Checkpoint、类型化 post-state、有界 Diff 和 compare-before-restore Undo；
- React/Ink list/diff/逐项 Undo 确认只经受控 stdio，不直接读取 Session 或 Workspace 文件；
- Scripted Fake Model 验证 Resume/Fork canonical history、Call ID 配对与停止语义的 Behavior Replay。

S06 不兼容商业产品内部 JSONL，也不承诺稳定外部 Export、Retention、SQLite、跨版本迁移、
heartbeat/stale reclaim 或 OS Sandbox；这些兼容性和隔离能力属于 S13/S14。完整契约见 ADR-040/041。

### S07：Context Engineering

G0-G2 已由 ADR-042/043/044 完成研究与设计冻结；D4 已具备生产 Projection/Memory seam 与 deterministic Fake G3-G5 证据，但等级未提升、G6 仍 Open。G3-G6 验收范围：

- Canonical Transcript/Context Projection 分离、Model-aware 容量预算和可解释 Usage View；
- C1 大载荷缩减、C2 旧 Tool 输出清理、C3 滚动记忆、C4 全量摘要按条件选择，保持完整 Tool
  协议且满足预算后停止；
- 摘要提交 Gate、同一次 Overflow 一次恢复、失败不污染规范历史和 Thrashing Guard；
- `MEMORY.md` 入口及 M1 Storage、M2 Index、M3 Catalog、M4 Recall、M5 Projection；
- `CTX-17` Auto Memory Index 与 `CTX-18` Relevant Memory Prefetch，后者采用 ready-only 零等待消费；
- 长会话回放比较事实/约束保持、任务完成度和 Token 降幅，并证明慢预取不增加模型请求关键路径。

S07 文件记忆只保存普通本地 Markdown 投影，不保存 Secret、完整 Prompt/源码或未经裁剪 Tool 输出。
S08 负责分层 Instructions、Settings 和完整 Slash Command UX；S12 负责 Sub-Agent/后台任务；S14
负责稳定 Export/Retention/Migration、SQLite 与 Provider-native Context/Cache 对照；S13 负责 OS
Sandbox。历史 ADR-019 继续 Superseded。

### S08：Instructions、Settings 与 CLI 交互

- 用户级、项目级和目录级项目指令；
- 配置分层、优先级和规则持久化；
- 模型切换和 Provider 配置；
- Slash Command：`/help`、`/clear`、`/compact`、`/context`、`/model`、
  `/permissions`、`/resume`；
- 更完整的 React/Ink 历史、多行输入、补全和运行中 steering。

S08 建立配置 Schema 和版本字段，但跨版本迁移兼容留在 S14。层级 Instructions 接入后，
必须重新运行 S07 的摘要重注入和 Context Usage 对账回归测试。

## 14. S09-S11：扩展系统重实现

### S09：Hooks

- Pre/Post Tool、Session、Turn、Compact 等生命周期；
- Hooks 的结构化输入、超时、阻断语义和失败隔离；
- Hook 不能绕过 Permission Pipeline，也不能直接污染核心状态。

### S10：MCP

- Spring AI MCP Client 或独立 MCP Adapter；
- MCP Tool 到统一 Tool Registry 的映射；
- MCP Tool 仍经过本项目的 Permission Gate、输出裁剪和审计；
- 覆盖连接失败、Schema 变化和不可信返回值。

### S11：Skills 与 Plugins

- Skill 的发现、描述懒加载、显式调用和上下文注入；
- Plugin 描述符、自定义 Tool Provider SPI 和兼容性约束；
- FixBug、Review、Test Generation 作为示例 Skill，而不是 Runtime 分支。

## 15. S12-S13：高级 Agent 与安全重实现

### S12：Subagent 与任务系统

- Subagent 复用同一 Agent Runtime，不建立第二套 Loop；
- 每个 Subagent 具有独立 Context、工具集、权限、模型和预算；
- 并发限制、父子任务、结果摘要和长任务恢复；
- Background Tool、取消传播和孤儿进程清理；
- Git Worktree 隔离并发编码任务。

S12 按 `RuntimeScope → 单 Subagent → 有界并发/后台 → Worktree` 四个内部检查点推进，
不能在 Scope 隔离尚未验证时直接实现并发写任务。

### S13：Sandbox

- 区分应用层 Permission 与 OS 级隔离；
- 提供可替换的 OS Sandbox 或 Container Backend；
- 定义文件系统、网络、进程和环境变量边界；
- 用逃逸测试验证边界，而不是只依赖 Prompt 和命令黑名单。

## 16. S14-S15：生产化与独立创新

### S14：Production Harness

- 稳定 JSON/JSONL 机器协议；
- 可嵌入的 Java SDK、Daemon 或本地 API；
- Headless/CI 安全配置；
- OpenTelemetry、Token、费用和延迟统计；
- 第二个 Provider Adapter，用于验证 Provider-neutral Port；
- 多模型路由、Fallback、限流/重试、Capability Detection、Eval/Benchmark 和跨平台安装；
- Provider Cache Hint 与原生 Context Editing 只作为 Adapter 优化，并与通用 S07 路径对照；
- 桌面端或 IDE 作为独立 Client，共享同一 Runtime。

S14 按 `Eval/Observability → SDK/Headless → Distribution/Compatibility` 三个内部检查点
推进。Eval 从 S01 起持续积累，S14 负责产品化、兼容和统一报告，而不是第一次引入评测。

### S15：Independent Innovation

只有 S01-S14 完成、矩阵内能力达到规定等级、关键能力达到可对照的 `L3`，并且已有回放评测后，才将创新列入主线。候选方向包括：

- Java/Spring 项目的语义工具与构建诊断；
- 企业内网部署、审计和可解释审批；
- 可嵌入 Spring 应用的 Agent Runtime；
- 面向测试、FixBug 和代码评审的高质量 Skill；
- 基于评测数据而不是直觉的模型与工具路由。

## 17. 非功能需求

### 17.1 来源控制、独立重实现与可维护性

- NFR-001：实现不得复制、逐行翻译或再发布泄露、未授权或超出授权范围的源码表达。
- NFR-002：需求来自本项目文档、公开来源、受控授权机制研究和独立场景；测试来自独立验收任务，不以
  参考源码文本作为断言。
- NFR-006：参考研究必须记录来源、版本/Revision、权利边界和
  `Documented / Observed / Inferred / Unknown`；无法核验的材料必须隔离。
- NFR-003：核心 Runtime 不依赖 Spring AI、React、Ink、Node、终端、文件系统或数据库类型。
- NFR-004：内置、MCP 和插件工具不得拥有绕过 Pipeline 的执行入口。
- NFR-005：不为尚未进入里程碑的能力创建复杂 DSL 或空模块。

### 17.2 安全

- NFR-010：仓库内容、工具输出和模型文本全部视为不可信输入。
- NFR-011：权限由代码执行，不依赖 Prompt。
- NFR-012：路径工具阻止绝对路径、穿越、符号链接和 Windows Junction 越界。
- NFR-013：API Key、密码、Token、端点和其他 Secret 不进入仓库、Transcript、文件记忆、摘要或普通日志；疑似 Secret 记忆候选 Fail Closed。
- NFR-014：Permission、Memory、Context Reduction、FileLock 与 Checkpoint 都不是 OS Sandbox，文档和 UI 必须明确这一点。
- NFR-015：未经用户明确要求和批准，不执行远端推送、发布、部署或数据写入。
- NFR-016：记忆与索引文件每次访问都必须在独立 memory root 内做真实路径、普通文件、大小、数量、Symlink/Junction 与竞态校验。
- NFR-017：相关记忆预取失败、取消或迟到不能阻断主模型请求，也不能在请求发送后异步改变该次 Context。

### 17.3 质量

- NFR-020：Runtime 主要路径可以完全离线测试。
- NFR-021：真实模型测试显式启用，不作为普通 CI 前提。
- NFR-022：支持 Windows 11 和主流 Linux。
- NFR-023：模型流、工具进程和取消均有超时。
- NFR-024：错误必须保留结构化分类和用户可读信息。

### 17.4 可观测性与隐私

- NFR-030：统计模型轮次、工具次数、耗时、Token 和 Stop Reason。
- NFR-031：Prompt、Completion、文件内容和命令输出默认不导出到遥测系统。
- NFR-032：Agent Event 可以重建控制流，但不要求保存完整敏感内容。

## 18. 成功指标

### 18.1 第一轮工程指标

- 验收任务端到端完成；
- 越界文件访问成功次数为 0；
- 未审批修改和命令执行次数为 0；
- 无限循环和遗留子进程次数为 0；
- Agent Loop 离线协议测试通过率 100%；
- Windows 与 Linux 基础测试通过；
- 普通 CI 不需要模型密钥；
- S01-S04 对应矩阵项至少达到目标等级；
- 发布时生成一份能力差距报告，明确下一阶段而不是凭感觉加功能。

### 18.2 项目价值指标

- 新用户可以根据 README 在 10 分钟内理解并启动 CLI；
- 核心架构可以只根据本项目 PRD、技术设计和 ADR 被解释和调试；
- 至少提供一个可复现公开 Demo；
- 每个 Stage 有设计说明或 ADR、测试、演示和差距记录；
- FixBug 等上层用例无需修改 Runtime Core 即可实现。

## 19. 主要风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 再次被某个业务场景绑架 | Runtime 失去通用性 | Core 只出现 Agent、Tool、Context、Session、Permission 概念 |
| 只追求“能跑的 MVP” | 学不到成熟系统为何复杂，也不知道下一步做什么 | 用完整能力矩阵持续推进，不把 S04 当终点 |
| 试图一次完成全部参考能力 | 长期没有可验证的学习反馈 | 每个 Stage 都保留可执行 Demo、测试和差距报告 |
| Spring AI 自动执行工具 | 绕过本项目权限与事件 | Adapter 只返回原始 Tool Call |
| 通用 Shell 带来副作用 | 数据或环境受损 | 精确展示、默认询问、超时、取消；S13 再做 OS Sandbox |
| 上下文持续膨胀 | 成本和稳定性下降 | S03-S04 先限制并停止，S07 系统学习压缩 |
| 参考材料授权范围或身份出现疑问 | 可能越过学习与发布边界 | 立即停止对应研究；保留指纹和 Unknown，等待维护者重新确认 |
| 双运行时和跨进程协议失控 | 调试、取消和发行成本上升 | S02 只做内部 v0、Fake 跨进程测试和原生 Windows 进程清理；稳定性留到 S14 |
| “开源不商用”含义不清 | License 与目标冲突 | S00 明确是维护者不商业化，还是许可证禁止商业使用 |

## 20. 已确认与待确认决策

S01 已确认：

1. 项目名和仓库名使用 `cc-java`；
2. Java 21 作为基线，Maven Wrapper 固定 Maven 3.9.16；Windows 普通 `.m2` 目录启动
   缺陷已修复，并在稳定 Commit 上通过 G4/G6 标准复验；
3. Maven GroupId 使用 `io.github.liumaishenjian`，Java 根包使用
   `io.github.liumaishenjian.ccjava`；
4. S01 不接真实 Provider，Fake Model 只存在于测试源。
5. 采用 `R2026.03` 公开行为基线；维护者确认 `AUTH-SRC-2026-07-29-A` 只读学习授权，
   精确 Revision、License 和再发布权继续保持 `Unknown`。
6. S02 的 UI 路线采用 Java Headless + 内部 stdio v0 + React/Ink；`CLI-11` 在 S02
   只达到 L1，稳定公共 JSON/JSONL 仍属于 S14。
7. S02 首个真实 Provider 采用维护者提供的 OpenAI 兼容 Base URL、API Key 和模型，
   不使用 Ollama；每台电脑默认填写 Git 忽略的 `config/provider.local.properties`，
   环境变量可以覆盖，具体兼容能力由真实 Spike 验证。

后续 Stage 仍需确认：

1. OpenAI 兼容中转端点的 Tool Call Streaming、Usage、Finish Reason 与 Cancellation
   实际兼容程度；
2. 最小协议 Schema/大小上限；Spring AI 2.0.0、Spring Boot BOM 4.1.0 与
   Picocli 4.7.7 已由真实 Spike 确认；
3. `run_command` 在 Windows 和 Linux 的默认 Shell；
4. S04 是否允许“当前会话始终允许”Shell，或只允许单次批准；
5. “开源不商用”的准确含义：
   - 维护者自己不计划商业化，但采用 Apache-2.0/MIT；或
   - 许可证禁止商业使用，此时属于 source-available，而非 OSI Open Source。

## 21. 术语

- **Agent Harness**：围绕模型提供上下文、工具、权限、执行环境、会话和反馈循环的系统。
- **Agent Runtime**：驱动模型回合和工具回合的核心运行时。
- **Tool Execution Pipeline**：工具从请求到校验、权限、审批、执行、裁剪和事件的统一路径。
- **Interactive 模式**：用户在同一终端会话中持续对话和审批。
- **Print 模式**：一次性、适合脚本的非交互运行。
- **可审计参考研究**：使用来源、版本和权利边界可核验的材料研究行为或机制；未核验材料不进入活动设计。
- **独立重实现**：Java 契约、命名、实现和测试均能够由本项目需求与 ADR 独立解释。
- **FixBug**：Runtime 的一个可能用例，不是核心架构。
