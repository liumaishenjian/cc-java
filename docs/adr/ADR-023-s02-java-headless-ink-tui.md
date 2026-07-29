# ADR-023：S02 采用 Java Headless Runtime 与 React/Ink 终端前端

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI（架构选择；生产能力仍未实现）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Capability IDs: S02 的 24 项能力；在 ADR-021 的 23 项基础上增加 `CLI-11`
- Supersedes in Part: ADR-021 的 JLine CLI、23 项计数及“不在 S02 建立 JSONL 边界”
- Decision Scope: UI 技术路线、进程边界、内部协议 v0、取消和验证策略

## 背景

S01 已经证明框架无关 Domain、显式 `AgentRuntime`、统一 `ToolExecutionPipeline` 和内存
Session 的边界，不需要重写 Runtime Kernel。真正需要重新决策的是终端前端。

候选 S02 分支 `10c7873` 的探索表明：只做到流式输入输出和基础交互，Java CLI 已新增约
25 个生产类，其中自定义终端 Renderer 约 382 行；审批卡片、工具详情和成熟多行编辑尚未
开始。如果继续把 JLine REPL 当作最终 UI，项目会先学习终端控件实现，随后又为更成熟的
Coding Agent 体验迁移一遍。

公开主流实现显示了更稳定的共同点：Agent Core 与终端 Surface 分层；终端交互使用专门
的组件化 UI 技术；机器/进程边界可以结构化传输事件。具体产品可能同进程调用 Core，
也可能通过 stdio 或服务连接，因此本项目采纳“逻辑边界”而不照搬某个产品的完整部署形态。

### 公开研究证据（访问日期 2026-07-29）

| 来源 | 分类 | 本 ADR 使用的最小结论 |
| --- | --- | --- |
| [Gemini CLI 架构](https://github.com/google-gemini/gemini-cli/blob/main/GEMINI.md) | 官方开源仓库文档，`Documented` | CLI 使用 React/Ink，Core 与 CLI 有清晰包边界 |
| [Gemini CLI ACP 模式](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/acp-mode.md) | 官方开源仓库文档，`Documented` | stdio 上的结构化协议可以承载独立 Client，但本项目不复制 ACP |
| [OpenCode CLI 文档](https://github.com/anomalyco/opencode/blob/dev/packages/web/src/content/docs/cli.mdx) | 官方开源仓库文档，`Documented` | 本地 TUI 与可连接后端是可行分层；S02 不照搬其完整 Server |
| [OpenTUI](https://github.com/anomalyco/opentui) | 官方开源仓库，`Documented` | 其 Zig/Bun 原生栈会扩大当前学习范围 |
| [Codex Rust 架构](https://github.com/openai/codex/blob/main/codex-rs/README.md) | 官方开源仓库文档，`Documented` | Core、exec、TUI、CLI 分层是成熟方案 |
| [Codex app-server](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md) | 官方开源仓库文档，`Documented` | JSONL/JSON-RPC 可作为结构化 Client 边界；稳定 Server 不进入 S02 |
| [Ink](https://github.com/vadimdemedes/ink) | 官方框架仓库，`Documented` | React 组件与 Yoga/Flexbox 模型适合交互式终端；准确版本由 Spike 决定 |

这些页面没有在本仓库归档或建立内容指纹，因此只能支持本次技术选择，不能据此宣称
参考产品行为达到 L3。

## 方案比较

| 方案 | 优点 | 主要成本 | 结论 |
| --- | --- | --- | --- |
| Java + JLine/Picocli 完整交互 | 单 JVM、Java 调试直接 | Agent UI、重绘、多行输入和审批展示需要大量定制；已有 Spike 显示替换成本 | 否决为最终 UI |
| Java + Spring Shell | 成熟命令解析和命令式 Shell | 更适合命令 Shell，不等于流式 Coding Agent TUI；仍需自定义展示 | 否决 |
| Java + Lanterna | Java 原生全屏终端 | 引入另一套 UI 模型，成熟 Agent 参考较少，且不能减少学习偏航 | 否决 |
| Java Core + HTTP/SSE TUI | 可远程、多客户端 | 端口、鉴权、重连、孤儿进程和服务生命周期过早进入 S02 | 延后 |
| Java Core + OpenTUI | 高性能组件化终端 | Bun/Zig/原生绑定扩大工具链和发行复杂度 | 当前否决 |
| Java Core + Rust/Ratatui | 成熟终端生态 | 第三种语言，不服务于 Java Runtime 学习目标 | 否决 |
| Java Core + React/Ink + stdio | 复用成熟 React 终端模型；Java 继续拥有 Agent 语义；边界可测试 | 双运行时、跨进程协议和生命周期 | 采纳 |

## 决策

### 1. 运行时和 Surface 边界

```text
TypeScript React/Ink TUI
    │  commands: UTF-8 NDJSON
    │  events:   UTF-8 NDJSON
    ▼
Java Headless Composition Root
    → AgentRuntime
    → ModelGateway
    → ToolExecutionPipeline
```

- `cc-java-domain` 和 `cc-java-core` 继续是 Java、框架无关且权威的 Agent 语义；
- `cc-java-cli` 保留为 Java Headless Composition Root，Picocli 只解析 `--print`、
  `--stdio`、Workspace、Model 和运行配置；
- 新增非 Maven 包 `cc-java-tui`，负责 React/Ink 交互、终端输入、展示和拉起 Java 子进程；
- TUI 只能发命令、消费事件，不能执行 Tool、修改 Session 私有状态或猜测 Runtime 终态；
- 不把 Spring AI、Reactor、Ink、Node 或 JSON SDK 类型泄漏进 Domain/Core。

### 2. S02 内部 stdio 协议 v0

S02 将 `CLI-11` 从纯 S14 能力调整为 `S02/S14`：S02 只达到 L1 的实验性本地协议，
S14 才提供 L3 的稳定公共机器协议、兼容承诺和外部客户端能力。

S02 的最小消息：

- Command：`initialize`、`run.start`、`run.cancel`、`shutdown`；
- Event：`initialized`、`run.started`、`model.text.delta`、
  `run.completed`、`run.failed`、`run.cancelled`、`protocol.error`。

每行是一个完整 UTF-8 JSON 对象，Envelope 至少包含：

```text
version, type, requestId, sessionId?, runId?, sequence, payload
```

协议不变量：

1. Java stdin 只接收命令，stdout 只输出协议事件，脱敏诊断只写 stderr；
2. S02 每个 Session 同时最多一个活动 Run；
3. `sequence` 在连接内单调递增，每个 Run 恰好一个 Terminal Event；
4. 未知主版本、未知必需类型、畸形 JSON、超限行和非法状态迁移均确定性失败；
5. 未知可选字段可忽略，为后续演进保留余地；
6. Java 是取消、失败和完成状态的唯一权威；
7. stdin reader 与 Run executor 分离；事件由单一串行 Writer 写出；
8. 队列、单行和累计输出必须有界；慢消费者下允许合并 Text Delta，但不能重排终态；
9. S02 v0 不承诺跨版本兼容，不对外宣称 SDK、Daemon 或稳定 Headless API。

精确 Schema、错误码和大小上限由协议 Spike 证伪后在后续 ADR 固定。

### 3. 取消与 Windows 子进程生命周期

- Run 活动时第一次 `Ctrl+C` 发送 `run.cancel`，等待 Java 返回 `run.cancelled`；
- 空闲时 `Ctrl+C` 退出当前 TUI；
- 取消超时或再次中断时，TUI 终止 Java 子进程并返回非零退出码；
- 不依赖 Windows 将终端 SIGINT 自动转发给 Java；
- TUI 必须持续消费 Java stdout 与 stderr，避免任一管道阻塞；
- S04 引入 Tool 进程后，由 Java 执行层负责工具子进程树取消，TUI 不能越过 Pipeline；
- 正常退出、EOF、TUI 崩溃和 Java 崩溃都必须验证不遗留孤儿进程。

### 4. 发行边界

S02 开发态明确要求 Java 21、Node.js 22 和 `npm.cmd`/npm。React、Ink、Spring AI、
Picocli 和 Provider 的精确版本只能在各自 Spike 验证后锁定。首个 Spike 优先官方 Ink，
不复制参考项目或 Gemini 的私有/分叉 Renderer。

S14 再决定 npm 包、zip、jlink/jpackage 或其他跨平台发行组合；S02 不追求单可执行文件，
也不提前建设 HTTP Server、Daemon、SDK 或自动更新。

## 可证伪 Spike 与验收

### Spike A：Java Fake stdio

- Fake Runtime 接收 `initialize`、`run.start`、`run.cancel`；
- 验证顺序、唯一终态、畸形/超限输入、EOF、慢消费者和 stderr 洪水；
- Windows 上强制退出后无孤儿 Java 进程；
- stdout 出现任意非协议文本即失败。

### Spike B：最小 React/Ink

- 使用 Node 22 和候选官方 Ink 版本渲染流式文本；
- 验证中文/宽字符、退格、粘贴、窗口缩放、TTY/非 TTY 和 `Ctrl+C`；
- UI 状态由纯 Reducer 驱动，组件测试不依赖真实模型；
- 若 Ink 版本、Windows 输入或跨进程取消无法稳定通过，则返回 G2 重新选择技术组合。

### 分层测试

1. Java：Codec、状态机、序列、上限、取消、EOF 和结构化错误；
2. TypeScript：Reducer、输入状态、组件和非 TTY 降级；
3. 跨进程 Fake E2E：命令/事件、stdout 纯净、stderr 排空和进程清理；
4. 真实 Java 进程 E2E：`--print`、`--stdio` 和退出码；
5. 显式启用的真实 Provider E2E：不作为普通 CI 前提；
6. Windows 原生 G5：中文、粘贴、Resize、取消、失败负例和无孤儿进程。

## 对候选 S02 分支的处理

不得整包合并 `10c7873`。后续以独立聚焦变更选择性重做或移植：

- 保留方向：Domain/Core 的流式事件、Usage/FinishReason、取消、Deadline、Retry；
- 仅保留可独立解释的 Provider-neutral Chunk 聚合与 opt-in E2E 思路；不移植
  Ollama Adapter，首个 Provider 以 ADR-024 的 OpenAI 兼容方向重做；
- 调整：`CoreCliRuntime` 重命名为 UI 无关的 Application Session；
- 淘汰：`JLineCliTerminal`、`InteractiveSession`、`TerminalRenderer` 及对应 UI 测试/依赖；
- 改造：`CcJavaCommand` 只承载 Java `--print` / `--stdio` Headless 入口。

复用候选分支代码前仍要逐文件检查来源、架构边界、中文 Javadoc、测试和当前文档契约。

## Gate 结论

- 本 ADR 完成重新决策后的 G1，并给出 G2 的明确 Spike；G0、G2-G6 仍保持 `OPEN`；
- S02 从 23 项调整为 24 项，目标变为 19 项 L2、5 项 L1；
- `CLI-11` 只新增 S02 L1 目标；其他 Capability Level 均未变化；
- `CLI-12` 与 `DIST-04` 仍在 S14，本 ADR不把内部传输包装成稳定多 Surface 平台；
- S01 架构和代码无需重写；
- 本 ADR 不表示 Provider、stdio 协议或 React/Ink TUI 已经实现。

## Spike A 结果（2026-07-29）

Java Fake stdio Spike 已在 CLI 适配层实现并通过 35/35 离线测试，其中 CLI 新增
12 个测试。Spike 证明：

- 单行 UTF-8 NDJSON、严格 Envelope、有界读取和有界单 Writer 队列可以独立成立；
- stdin Reader 与异步 Fake Run 分离后，活动 Run 期间仍能接收 `run.cancel`；
- Event Emitter 可以确定性守卫 `run.started`、唯一终态和终态后禁止继续输出；
- Windows 上测试进程可以经 `shutdown` 正常退出，并验证没有存活的已捕获后代进程。

Spike 采用 `tools.jackson.core:jackson-databind:3.1.0`。3.1.0 是本次验证使用的 LTS
候选版本，并已通过 Maven Central 解析和依赖收敛检查；它仍须与 Ink、Provider 和许可证
核验一起进入 G2 的最终依赖结论。

完整证据见 [S02 Java Fake stdio Spike 证据](../evidence/S02-stdio-spike-2026-07-29.md)。
该结果没有提升 `CLI-11`、`CLI-06` 或 `LOOP-08` 的 Capability Level；React/Ink、
真实 Runtime/Provider、stderr 洪水和异常退出清理仍未证明，因此 G2-G6 保持 `OPEN`。

## Spike B 结果（2026-07-29）

`cc-java-tui` 已使用 Node.js 22.15.0、React 19.2.8 与 Ink 7.1.1 完成最小 Spike，
并提交 npm lockfile。TypeScript 严格编译和 12/12 离线测试通过，Windows 上实际完成：

- React/Ink TTY 接收中文输入，并按 `ready → running → ready` 渲染 Java 事件；
- Java Fake 的两个 Delta 按序显示为 `alpha beta`；
- 空闲 `Ctrl+C` 发送 `shutdown` 后退出，Node 与 Java 退出码均为 0；
- 非 TTY 跨语言 Demo 输出纯文本 `alpha beta`，不含 ANSI；
- 乱序事件、Backspace Unicode 删除、窄窗口展示与活动 Run 取消命令有确定性测试。

TUI 代码只发送命令并消费事件；`reduceTuiState` 不推断终态，`StdioClient` 不经 Shell
启动 Java，也不展示 stderr 原文。进度生成器已扩展为把 TypeScript、`package.json` 和
lockfile 纳入代码摘要，并通过漂移自测。

完整证据见 [S02 React/Ink TUI Spike 证据](../evidence/S02-tui-spike-2026-07-29.md)。
由于真实 Runtime/Provider、可重放 Resize/Bracketed Paste、异常退出和 Linux 行为尚未
证明，相关 Capability 继续保持 L0，G2-G6 仍保持 `OPEN`。

## Java Print 结果（2026-07-29）

后续 [ADR-025](./ADR-025-s02-picocli-java-print.md) 已固定 Picocli 4.7.7，并证明
`--print` 与 `--stdio` 可以共用同一 Java `HeadlessRuntimeSession`。从
`C:\Windows\System32` 使用仓库脚本绝对路径完成真实模型调用，stdout 只有流式文本，
退出码为 0；离线测试覆盖模式互斥、帮助、文本不重复和 Runtime 装配。

该结果把 `BOOT-01` 与 `CLI-02` 提升至 L2；`CFG-01`、真实运行中取消和异常负例仍未
关闭，因此不改变 S02 Stage Exit。
