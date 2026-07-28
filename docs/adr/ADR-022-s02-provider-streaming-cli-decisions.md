# ADR-022：S02 Provider、流式模型与 CLI 技术决策

- Status: Accepted
- Date: 2026-07-28
- Stage: S02 — Model + Streaming CLI
- Feature IDs: `BOOT-01/03`、`CLI-01/02/03/04/06/10`、
  `LOOP-04/08/09/10`、`MODEL-02/04/05/06`、`CTX-01`、`CFG-01/02`、
  `SESSION-02`、`OBS-02/03/05`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Supersedes: ADR-009 中 Boot/Spring AI 的 Deferred 部分、ADR-010、ADR-011
- Decision Scope: S02 的依赖版本、首个 Provider、流式 Tool Call 边界、重试所有权和
  CLI 技术组合

## 1. 背景

ADR-021 固定了 S02 的 23 项能力和可证伪实验，但有意没有预先锁定 Spring Boot、
Spring AI、Provider、Picocli 或 JLine。现在官方版本核验、独立协议 Spike、离线
Fixture 和显式启用的真实 Provider E2E 已经给出选择这些依赖所需的实际用途证据。

本决策没有使用任何授权或未核验参考源码。Java 契约、命名、测试输入和预期结果均可由
本项目 PRD、ADR-021、公开官方文档和独立场景解释。隔离材料
`UNVERIFIED-SRC-2026-03-31-A` 仍为 `QUARANTINED`，没有被读取、搜索、分析或用作
实现、Fixture、Prompt、Golden Output 与行为 Oracle。

## 2. 公开来源

下列页面均在 2026-07-28 访问：

| 来源 | 本 ADR 使用的结论 | 分类 |
| --- | --- | --- |
| [Spring Boot 4.1.0 发布公告](https://spring.io/blog/2026/06/10/spring-boot-4/) | 4.1.0 是已发布的 Boot 版本 | `Documented` |
| [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) | Boot 4.1.0 的 Java/Maven 基线与本项目 Java 21、Maven 3.9.16 兼容 | `Documented` |
| [Spring AI 2.0.0 GA 公告](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) | 2.0.0 是 GA 版本 | `Documented` |
| [Spring AI Chat Model API](https://docs.spring.io/spring-ai/reference/api/chatmodel.html) | `StreamingChatModel` 提供流式模型边界 | `Documented` |
| [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) | Tool 定义、Tool Call 与用户控制执行的公开契约 | `Documented` |
| [Spring AI Ollama Chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) | `OllamaChatModel` 同时支持 Chat 与 StreamingChat，且真实工具执行可由调用者控制 | `Documented` |
| [Picocli](https://picocli.info/) | 参数解析、帮助和退出码能力 | `Documented` |
| [JLine Line Reader](https://jline.org/docs/line-reader/) | 行编辑、中断和 Terminal 能力检测 | `Documented` |
| [Ollama Tool Calling](https://docs.ollama.com/capabilities/tool-calling) | Ollama 的 Tool Call 与多 Tool Call 公开行为 | `Documented` |
| [Ollama Streaming](https://docs.ollama.com/api/streaming) | Ollama HTTP API 的流式响应形状 | `Documented` |

准确依赖坐标由 Maven Central 中的正式 POM/BOM 复核；项目不依赖网页示例中的未固定
快照。官方文档能证明公开 API 与版本存在，但不能代替本项目对 Chunk 聚合、取消、
Call ID 和终态的验证。

## 3. 独立 Spike 结果

在 Windows 10、Java 21、Ollama 0.32.4 与本机显式指定模型上，独立 Spike 记录了：

| 场景 | 实际观察 |
| --- | --- |
| 文本流 | 收到 18 个非空文本 Delta |
| 输出长度 | 小输出预算返回规范化 `LENGTH` |
| 多 Tool Call | 同一回合得到 2 个不同 ID，顺序为 `sum_numbers`、`repeat_text` |
| Tool Result 回传 | 两个结果进入下一回合后，模型返回无 Tool Call 的最终文本 |
| 自动执行哨兵 | Schema-only Tool Callback 的执行计数为 0 |
| Usage | 输入 443、输出 64；未伪造货币成本 |
| 取消 | 约 1.25 秒完成取消；取消后没有 late signal |
| 原生协议对照 | Ollama 原生调用同样观察到 2 个不同 ID、相同顺序和 Usage |

以上数字是本次固定环境的 `Observed` 证据，不是对其他模型、硬件或未来版本的性能
承诺。真实模型测试只断言结构、ID、顺序、终态和有界时间，不断言固定自然语言。

## 4. 决策

### 4.1 依赖版本

S02 锁定：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| Spring Boot | `4.1.0` | CLI Composition Root 与 Bean 装配 |
| Spring AI | `2.0.0` | Provider-neutral 消息边界和 Ollama Adapter |
| Picocli | `4.7.7` | 参数、帮助、模式选择和退出码 |
| JLine | `3.30.16` | Interactive Terminal、行编辑、信号与能力检测 |
| 首个 Provider | Ollama `0.32.4` 已验证 | 本地真实模型流与 Tool Calling |

Boot 只存在于 `cc-java-cli` 的装配边缘；Spring AI 和 Reactor 类型只存在于
`cc-java-model-spring-ai`。Domain/Core 不依赖这些框架类型。

JLine 4 是一个新的主要版本；S02 没有可证实的能力需要承担这次迁移风险，因此选择
已满足当前 REPL/Terminal 用途、并通过离线终端测试的 3.30.16。该选择不承诺永久停留在
3.x，后续升级必须以真实用途和回归证据重新决定。

### 4.2 直接使用 `StreamingChatModel`

Adapter 直接调用 `OllamaChatModel` 的 `StreamingChatModel` 边界，不使用
`ChatClient`，也不安装会自动继续 Tool Loop 的 Advisor。Spring AI 的流只在 Adapter
内部消费；Core 看到的是：

1. 调用期间按序发布的项目自有 `ModelTextDelta`；
2. 回合结束时一个聚合后的项目自有 `ModelTurn`；
3. 项目自有 `ModelFinishReason`、可选 `ModelUsage` 和结构化模型错误。

Tool Definition 通过只提供 Schema 的 Callback 传给模型。该 Callback 的执行入口是
失败哨兵，生产 Agent Tool 不会注册给 Spring AI。真实 Spike 中哨兵执行次数为 0；
只有 `AgentRuntime → ToolExecutionPipeline` 可以执行 Tool。

### 4.3 Tool Call Chunk 与消息协议

Adapter 必须先按 Provider 给出的 ID/Index 聚合跨 Chunk 的名称和 JSON 参数，完成整个
模型回合后才把 Tool Call 交给 Runtime。聚合不得：

- 为每个 Chunk 创建一条 Assistant Message；
- 改写 Provider 的非空 Call ID；
- 按工具名称重新排序；
- 在 JSON 尚未完整时执行 Tool；
- 把 Tool Result 越过 Runtime/Pipeline 直接送回 Provider。

同一回合多个 Tool Call 继续遵守 ADR-017/021 的批次协议：Assistant Message 只追加
一次，Result 与 Call ID 一一对应，整批到达明确状态后才能开始下一模型回合。

同一 Chunk 出现重复 Call ID 时按 `INVALID_RESPONSE` 拒绝，跨 Chunk 的相同 ID 才允许
作为同一调用继续合并。相互冲突的 Finish Reason 也按 `INVALID_RESPONSE` 拒绝，防止
`LENGTH`/`CONTENT_FILTER` 被后续 `STOP` 降级；`tool_calls/tool_use` 却没有有效调用时
按 `INCOMPLETE_RESPONSE` 处理。

### 4.4 流背压与本地响应上限

Adapter 的 Reactor Subscriber 使用容量 2 的有界队列，最多容纳一个 `Next` 和一个
终止信号；初始及每次成功消费后只 `request(1)`，不使用 `requestUnbounded`。单个模型
回合还具有两层本地聚合上限：

- 文本、非空 Finish Reason、不同 Tool Call 的 ID/名称/参数共享 8 MiB UTF-8 上限；
- 不同 Tool Call ID 最多 128 个。

超限发生在当前 Delta 发布前：Adapter 取消活动订阅，产生不可重试的
`RESPONSE_LIMIT_EXCEEDED`，Runtime 映射为 `MODEL_OUTPUT_LIMIT_REACHED`；此前已经发布
的 Delta 保持可见，但整个失败不重试。该边界限制 Adapter 保留与排队的内容；Provider
SDK 在交付 `ChatResponse` 前已经分配的单个巨大对象，无法由本地 retained cap 事先
阻止。

### 4.5 重试、取消与长度边界

Spring AI/Ollama Adapter 的内部重试配置固定为 `0`。重试所有权属于 Core：

- 默认最多重试 1 次，CLI 允许配置 `0..3`；
- 只重试项目分类为可重试、且尚未产生可见 Delta 的模型失败；
- 一旦发布 Delta、收到取消、达到 Deadline、返回不完整流或 `LENGTH`，不自动重放；
- `LENGTH` 在 S02 采用有界停止并映射稳定退出码，不猜测或无限续写；
- 取消令牌传播到模型订阅并停止接受后续信号。

该边界避免 Adapter 与 Runtime 叠加重试、重复展示文本或重复请求 Tool。

### 4.6 CLI 与配置

Picocli 提供 Interactive 和 `--print` 两种入口。JLine 只在 Interactive 路径创建；
Print 不等待终端输入、stdout 只承载 Assistant 文本，状态与错误进入 stderr，并关闭
Renderer 自有 ANSI 样式。stdin/stdout 不是交互终端且未显式使用 `--print` 时，CLI
失败并返回配置退出码，不猜测交互能力。Terminal Renderer 把模型文本、Tool 名称、
错误和配置视为不可信输入：剥离 ESC/CSI/OSC/DCS/C0/C1；Assistant 文本只保留规范化
LF 与 Tab，状态字段把换行与 Tab 折叠为单个空格。Print 与 Interactive 使用相同契约，
模型内容不能绕过 `--no-color` 重新注入终端控制序列。

S02 配置优先级固定为：

```text
CLI → Environment → Code Defaults
```

模型名必须通过 `--model` 或 `CC_JAVA_MODEL` 显式指定；项目不假定或自动下载本机
Ollama 模型。Base URL 只接受不含凭证、Query 与 Fragment 的 HTTP(S) 根地址。Ollama
当前不要求 API Key，但 Secret 契约仍只允许环境变量或外部 Secret Store，不能通过
普通 CLI 参数或提交文件传入。

### 4.7 首个 Provider 支持边界

S02 的真实支持基线是 Ollama 0.32.4。真实 E2E 的模型名称与 Digest 属于运行者环境，
必须显式固定，不写入仓库默认值。更老 Ollama、未来不兼容版本、其他模型模板与第二
Provider 均不由本 ADR 宣称兼容。

## 5. 被否决方案

### 5.1 使用 `ChatClient` 自动 Tool Calling

拒绝。即使方便，它会把 Tool Loop 所有权移到框架 Advisor，绕过项目自己的预算、
取消、Permission、Lifecycle、Result 截断和 Call ID 不变量。

### 5.2 同时保留 Spring AI 与 Runtime 两层自动重试

拒绝。双层重试无法可靠限制总次数，并可能在已有文本可见后重复输出或重复模型请求。

### 5.3 把 Reactor、Spring AI 消息或 Ollama SDK 类型放进 Core

拒绝。这会破坏 Framework-free Domain/Core，也让第二 Provider 验证失去意义。

### 5.4 因本机无云端 Key 而伪造云 Provider 验证

拒绝。S02 使用本机可审计、无需云 Secret 的 Ollama 完成真实 Provider 证据；没有执行
过的云端能力保持 `Unknown`。

### 5.5 把 PTY 自动化环境当作真实 Windows Terminal

拒绝。当前 PTY 自动化工具被 JLine 正确识别为 non-TTY 并返回配置退出码；这证明降级
路径，不证明真实 Windows Terminal 的人工交互体验。

## 6. 已知 Unknown 与后续工作

- `[Unknown]` 真实 Windows Terminal 中的 Ctrl+C 信号时序、粘贴和历史体验尚未人工
  复验；离线 Fake Terminal/JLine 测试已覆盖项目状态迁移。
- `[Unknown]` 取消后 Provider 服务端是否立即停止全部计算；当前只能证明客户端约
  1.25 秒返回、订阅被取消且没有 late signal。
- `[Unknown]` Ollama 真实限流响应在不同反向代理下的错误形状；S02 只用确定性 Fixture
  验证结构化映射和有界重试。
- `[Unknown]` 第二 Provider、跨 Provider Tool Chunk 差异、JLine 的 Linux/macOS
  原生终端行为与未来依赖版本兼容性。
- `[Residual boundary]` Provider SDK 在 Adapter 收到 `ChatResponse` 前已经分配的单个
  巨大对象不受本地 retained cap 保护；Adapter 会在检查后拒绝，不再保留或发布当前
  Delta。
- Ollama 没有可验证的货币成本时，`cost` 保持缺省；缺失 Usage 也保持缺省，不用 0
  冒充 Provider 证据。
- 读写文件、Shell、完整 Permission、持久 Session、Context 压缩、稳定 JSONL、
  遥测 Export 和分发仍按 S03-S14 进入，不属于 S02。

## 7. 后果

正面后果：

- 真实 Provider 流、Tool Call 与取消被保留在可替换 Adapter 边缘；
- Runtime 继续唯一拥有 Agent Loop、重试、预算、取消和终态；
- CLI 可以在 Interactive、Print 和 non-TTY 环境中做确定性选择；
- 普通测试不依赖模型、网络、API Key 或固定自然语言。

代价：

- Adapter 必须独立维护消息、Usage、Finish Reason、异常和 Chunk 聚合映射；
- S02 只支持一个已验证 Provider/版本基线；
- 自动 Tool Loop 的便利被有意放弃；
- 真实终端和跨平台兼容仍需要后续人工/原生环境证据。

实现与复现证据见
[S02 标准验证证据](../evidence/S02-model-streaming-cli-2026-07-28.md)、
[S02 Demo](../demos/S02-model-streaming-cli.md)和
[S02 差距报告](../gap-reports/S02.md)。
