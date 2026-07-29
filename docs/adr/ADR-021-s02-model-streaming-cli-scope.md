# ADR-021：S02 Model + Streaming CLI 启动范围与可证伪 Spike

- Status: Accepted
- Partially Superseded By: [ADR-023](./ADR-023-s02-java-headless-ink-tui.md)
- Date: 2026-07-28
- Stage: S02 Model + Streaming CLI（仅完成启动范围，生产实现尚未开始）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`（本 ADR 当时未使用；后续 CLI 决策使用 `AUTH-SRC-2026-07-29-A`）
- Capability IDs: 原 23 项；当前 S02 以 ADR-023 调整后的 24 项为准
- Decision Scope: 固定 G1 范围、退出目标和 Spike；首个 Provider 后续由 ADR-024 选择

## 背景

> 当前解释：本 ADR 的 Provider、流式模型和 19 项 L2 / 4 项原有 L1 目标继续有效；
> JLine UI、23 项总数以及“不在 S02 建立 JSONL 边界”的决定已由 ADR-023 取代。

S01 已经用框架无关 Domain 和同步控制流证明 Agent Loop、Tool 协议与内存 Session
骨架。S02 的任务不是把 Spring AI 自动循环包进 CLI，而是验证真实模型的流式协议能够
适配到现有 `ModelGateway`，同时让 Runtime 继续拥有 Tool Loop、取消、预算和终止状态。

在选择 Spring Boot、Spring AI、Picocli、终端 UI 和 Provider 的准确版本前，需要先固定
完整 Stage 范围，并用最小 Spike 证伪关键假设。否则依赖选择会先于真实协议证据。

> 2026-07-29 更新：维护者已通过
> [ADR-024](./ADR-024-s02-openai-compatible-first-provider.md)选择 OpenAI 兼容端点作为
> 首个真实 Provider，不再使用 Ollama；准确框架版本和兼容行为仍由 Spike 决定。

## 决策

经 ADR-023 调整后，S02 追踪矩阵中 Stage 列包含 `S02` 的 24 项能力。`CTX-01` 与
`SESSION-02` 已经由 S01 达到 L1，其余 22 项为 L0；S02 退出目标如下。

### S02 目标为 L2 的 19 项

| Feature ID | L0 → Target | S02 必须证明的行为 |
| --- | --- | --- |
| `BOOT-01` | L0 → L2 | CLI 可确定进入 Interactive 或 Print |
| `CLI-01` | L0 → L2 | TUI 连接持续 Java Headless 进程并连续执行交互 Session |
| `CLI-02` | L0 → L2 | Print 模式确定性完成并返回退出码 |
| `CLI-03` | L0 → L2 | Assistant Text Delta 按序渲染 |
| `CLI-10` | L0 → L2 | TTY 有能力检测，非 TTY 无 ANSI 且可管道化 |
| `LOOP-04` | L0 → L2 | 同步 Core 消费流式事件并取得聚合 Model Turn |
| `LOOP-09` | L0 → L2 | 可重试错误经过有界策略，不形成无限循环 |
| `LOOP-10` | L0 → L2 | 识别输出长度/不完整终态并有界停止或续接 |
| `MODEL-02` | L0 → L2 | 一个显式启用的真实 Provider 可运行 |
| `MODEL-04` | L0 → L2 | Adapter 内消费文本流，不把 Reactor 类型泄漏到 Core |
| `MODEL-05` | L0 → L2 | 跨 Chunk 的一个或多个 Tool Call 无损聚合 |
| `MODEL-06` | L0 → L2 | Finish Reason 规范化；Usage 缺失时不伪造 |
| `CTX-01` | L1 → L2 | System Context 与运行元数据由项目装配 |
| `CFG-01` | L0 → L2 | CLI Override 可追踪并经过类型校验 |
| `CFG-02` | L0 → L2 | Provider/Runtime 环境变量可诊断且不泄漏 Secret |
| `SESSION-02` | L1 → L2 | Interactive 模式维持进程内连续会话 |
| `OBS-02` | L0 → L2 | Turn/Tool 耗时来自确定性事件边界 |
| `OBS-03` | L0 → L2 | Token/Cost 只在 Provider 返回可信 Usage 时记录 |
| `OBS-05` | L0 → L2 | Prompt、Completion 和 Secret 默认不导出 |

### S02 目标为 L1 的 5 项

这些能力还要在后续 Stage 完成副作用或更完整的边界，因此 S02 只建立真实契约和局部路径。

| Feature ID | L0 → Target | S02 范围 | 后续完成点 |
| --- | --- | --- | --- |
| `BOOT-03` | L0 → L1 | 组装 Model、当前 Tool Definition 与最小 Permission Port | S05 完整权限装配 |
| `CLI-04` | L0 → L1 | 渲染模型和 Runtime 事件；尚无真实文件/命令输出 | S03 真实 Tool 进度 |
| `CLI-06` | L0 → L1 | `Ctrl+C` 取消当前模型流并保持 Session 可用 | S04 Tool/进程树取消 |
| `CLI-11` | L0 → L1 | 内部 stdio v0 建立版本、序列、唯一终态和取消边界 | S14 稳定公共协议 |
| `LOOP-08` | L0 → L1 | Deadline/取消传播到模型请求 | S04 Tool/进程树传播 |

## G0：实现前必须补齐的公开来源

本 ADR 不使用任何隔离或未核验源码。正式实现前必须：

1. 记录 Spring Boot、Spring AI OpenAI Chat、Picocli、Node 与 Ink 官方文档的访问日期；
2. 确认候选版本存在于 Maven Central，记录 BOM/Starter 的准确坐标；
3. 记录 Spring AI 在该版本下关闭自动 Tool Loop 的官方方式；
4. 明确维护者提供的 OpenAI 兼容端点对 Tool Call Streaming、Usage、Finish Reason
   和 Cancellation 的实际支持；
5. 将页面版本、Provider 差异和无法确认项标记为 `Unknown`。

上述工作完成前，S02 的 G0 保持 `OPEN`，不得锁定准确依赖版本。

## 可证伪 Spike

Spike 使用仓库外密钥、最小独立模块或临时分支，不进入 Domain/Core。必须记录原始请求
类别、结构化事件和结果，不断言固定自然语言。

### 模型协议

1. 文本 Delta 按序到达，最终可聚合为一个 Model Turn；
2. 单个 Tool Call 参数跨多个 Chunk 时能够完整拼接；
3. 同一回合多个 Tool Call 的 ID、名称和顺序保持；
4. Tool Result 可进入下一模型回合；
5. Spring AI 的自动 Tool 执行确实关闭，只有 `ToolExecutionPipeline` 可以执行；
6. Provider 返回不完整 Chunk、空响应、限流和普通异常时得到结构化终态；
7. Usage 缺失时字段为空，不能用估算值冒充 Provider Usage；
8. 输出长度限制能够被识别，并且最多发生一次受控续接或明确停止。

### 取消与终端

1. `Ctrl+C` 能中断当前模型订阅且不直接退出 Session；
2. Cancel 之后不再发布属于旧 Run 的文本或终态；
3. React/Ink TUI 在 TTY 下可交互，Java Print/stdio 的非 TTY 路径不输出 ANSI 或等待输入；
4. Print 模式遇到需要交互的路径时确定性失败并返回稳定退出码。

### 失败判定

任一情况发生即否决当前技术组合：

- 关闭自动 Tool Loop 后无法取得原始 Tool Call；
- Tool Call ID 或跨 Chunk 参数不能可靠聚合；
- Reactor/Provider SDK 类型必须进入 Domain/Core 才能工作；
- 取消后仍有不可控事件写入当前 Session；
- 无密钥的普通测试或构建无法运行；
- 只能通过记录完整 Prompt、Completion 或 Secret 才能诊断。

## 架构边界

- `cc-java-model-spring-ai` 是 Spring AI 与项目协议的唯一模型适配边界；
- `cc-java-cli` 是 Java Headless Composition Root 与参数解析边界；
- `cc-java-tui` 是 React/Ink 终端渲染边界，通过实验性 stdio v0 消费事件；
- `cc-java-core` 保持同步顺序控制，使用项目事件/Observer 接收增量；
- Spring AI Adapter 不注册可自动执行的高风险 Tool；
- CLI 只消费事件，不读取或修改 Runtime 私有状态；
- API Key 只来自环境变量或外部 Secret Store。

## 非目标

- 不实现文件读写、Shell、完整 Permission Policy 或 OS Sandbox；
- 不实现持久化 Session、Context 压缩、MCP、Skill 或 Sub-Agent；
- 不提供稳定公共 JSONL 机器协议或第二 Provider；S02 只实现 ADR-023 的内部 v0；
- 不把一次 Provider E2E 结果当作 L3 参考行为对照；
- 不在 Spike 前预先锁定 Spring Boot、Spring AI、Picocli、React 或 Ink 版本。

## Gate 与后续

- 本 ADR 通过 S02 的 G1；G0、G2-G6 仍保持 `OPEN`；
- G2 在 Spike 后记录 Provider/框架/CLI 技术选择及被否决方案；
- G3-G4 必须先用 Fake Stream/Terminal 覆盖确定性路径，再显式运行真实 Provider E2E；
- G5 必须包含 TTY/Print 正例和取消或不完整流负例；
- G6 只有在 ADR-023 调整后的 24 项目标、矩阵、README、PRD、技术设计、证据和差距报告对账后通过。

本 ADR 不提升 Capability Level，也不表示 S02 生产代码已经开始。
