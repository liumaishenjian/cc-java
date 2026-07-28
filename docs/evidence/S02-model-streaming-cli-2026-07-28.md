# S02 Model + Streaming CLI 验证证据

> Stage：S02 — Model + Streaming CLI
> Status：Implementation Candidate（G4 最后一次完整运行早于最终 JUnit BOM 修正；
> G5 等待维护者真实 TTY 正例；G6 Pending）
> Release / Commit：`0.1.0-SNAPSHOT`；当前 S02 Worktree，尚无新的稳定 Commit
> Reference Behavior Baseline：`R2026.03`
> Authorized Snapshot ID：`N/A - Not Used`
> Feature IDs：`BOOT-01/03`、`CLI-01/02/03/04/06/10`、
> `LOOP-04/08/09/10`、`MODEL-02/04/05/06`、`CTX-01`、`CFG-01/02`、
> `SESSION-02`、`OBS-02/03/05`
> Current → Exit Target：19 项到 `L2`，4 项到 `L1`
> Date：2026-07-28

本证据包不使用任何授权或未核验参考源码。`Authorized Snapshot ID` 为
`N/A - Not Used`；需求、设计、实现和测试只依赖公开官方文档、本项目 ADR 与独立编写
场景。`UNVERIFIED-SRC-2026-03-31-A` 保持隔离，未作为研究输入或测试 Oracle。

## G0：来源与授权 — Passed

版本与公开契约已在 2026-07-28 核验：

- [Spring Boot 4.1.0 发布公告](https://spring.io/blog/2026/06/10/spring-boot-4/)与
  [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)；
- [Spring AI 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)、
  [Chat Model API](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)、
  [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)与
  [Ollama Chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)；
- [Picocli](https://picocli.info/)、[JLine Line Reader](https://jline.org/docs/line-reader/)、
  [Ollama Tool Calling](https://docs.ollama.com/capabilities/tool-calling)与
  [Ollama Streaming](https://docs.ollama.com/api/streaming)。

公开 API 和版本为 `Documented`；本项目 Spike、测试与命令结果为 `Observed`；
跨 Provider、真实终端、服务端取消和真实限流保持 `Unknown`。准确版本和隔离边界见
[ADR-022](../adr/ADR-022-s02-provider-streaming-cli-decisions.md)。

## G1：范围与可证伪目标 — Passed

ADR-021 固定的 23 项范围没有扩张：

| 目标 | Feature IDs |
| --- | --- |
| 到 L2（19 项） | `BOOT-01`、`CLI-01/02/03/10`、`LOOP-04/09/10`、`MODEL-02/04/05/06`、`CTX-01`、`CFG-01/02`、`SESSION-02`、`OBS-02/03/05` |
| 到 L1（4 项） | `BOOT-03`、`CLI-04/06`、`LOOP-08` |

最小可证伪实验覆盖文本 Delta、跨 Chunk Tool Call 聚合、同回合两个 Call 的 ID/顺序、
Result 下一回合、自动执行哨兵、Usage/Finish Reason、输出长度边界、取消、不完整流、
Print/non-TTY 和 Boot Composition Root。

S02 没有提前实现文件 Tool、Shell、完整 Permission、持久 Session、Context 压缩、
MCP、Skills、Subagent、稳定机器协议、第二 Provider 或发行包。

## G2：研究与 ADR — Passed

[ADR-022](../adr/ADR-022-s02-provider-streaming-cli-decisions.md)已固定：

- Spring Boot 4.1.0、Spring AI 2.0.0、Picocli 4.7.7、JLine 3.30.16；
- Ollama 0.32.4 为首个已验证 Provider 基线；
- 直接使用 `StreamingChatModel`，不使用 `ChatClient` 或自动 Tool Loop Advisor；
- Schema-only Callback 只提供模型 Schema，执行入口是失败哨兵；
- Spring AI 内部重试为 0，Core 拥有 `0..3` 有界重试、Deadline、取消和 Stop Reason；
- Reactor/Spring AI/Ollama 类型不进入 Domain/Core；
- Print、Interactive 与 non-TTY 的确定性选择和流分离。

## G3：独立 Java 实现 — Passed

本阶段只扩展已有五模块：

- `cc-java-domain`：项目自有 Delta、Usage、Finish Reason 与终态；
- `cc-java-core`：Observer、Cancellation、Deadline、有界重试和流式 Runtime；
- `cc-java-model-spring-ai`：消息映射、Chunk 聚合、错误映射和 Ollama Gateway；
- `cc-java-cli`：Boot Composition Root、Picocli、JLine、Interactive/Print、渲染与退出码；
- `cc-java-tools-local`：没有新增真实 Tool，避免提前进入 S03/S04。

公共契约和关键实现使用中文 Javadoc。Adapter 不执行 Tool；模型产生的操作意图仍只经
`ToolExecutionPipeline` 处理。

## G4：验证 — Candidate Evidence；最终候选待维护者复跑

### 4.1 标准离线验证

| 字段 | 记录 |
| --- | --- |
| Date | 2026-07-28 |
| Environment | Windows 10 amd64；Java 21；Maven Wrapper → Maven 3.9.16 |
| Command | `.\mvnw.cmd clean verify` |
| Result | 95 项运行：94 通过、0 失败、0 错误；1 个真实 Provider E2E 默认跳过 |
| Core | 37 个确定性测试 |
| Spring AI Adapter | 27 个确定性测试；另 1 个 opt-in E2E 默认跳过 |
| CLI / Boot | 30 个确定性测试，包含 Boot Composition Root |
| Network / API Key | 不需要 |

该 `clean verify` 是最后一次已完成的完整运行。随后只修正了 JUnit BOM 构建输入；用户
明确要求停止继续验证、先完成代码并提交，因此没有在该修正后的最终候选上复跑。本节
保留这次真实结果，但不把它冒充最终 Commit-scoped G4 证据。

离线测试覆盖：

- Text Delta 顺序、聚合结果、单次 Assistant Message 与唯一终态；
- 多 Tool Call Chunk、不同 ID、顺序、增量 JSON、重复快照与不完整流；
- Usage/Finish Reason 的可选映射，缺失值不伪造；
- 取消、Deadline、`LENGTH`、模型错误分类和只在无可见 Delta 前发生的有界重试；
- Boot Bean 装配、配置优先级与校验、Secret 不复制；
- Interactive 多轮、`/exit`、Ctrl+C 状态迁移、Print、non-TTY、ANSI、stdout/stderr 和退出码。
- 确定性时钟场景记录 275 ms Model Turn 耗时，并以 `BeforeTool/AfterTool` 时间戳界定
  Tool 耗时；S02 不提前引入 S14 的 Micrometer/OTel Export。
- Renderer 对模型 Delta/Final、Tool 名称、错误和配置执行终端控制序列清洗；Delta
  状态按模型回合跟踪，早期 Tool 回合出现 Delta 不会吞掉最终回合的聚合文本回退。
- Subscriber 使用容量 2 的队列和逐项 `request(1)`；单回合聚合限制为 8 MiB UTF-8 /
  128 个不同 Tool Call，超限在当前 Delta 发布前取消并映射
  `MODEL_OUTPUT_LIMIT_REACHED`；
- 同一 Chunk 重复 Call ID、冲突 Finish Reason、无调用的 `tool_calls` 和无效 Usage
  分别进入结构化 `INVALID_RESPONSE` / `INCOMPLETE_RESPONSE`，不依赖解析错误文案。

### 4.2 独立 Provider/Streaming Spike

环境：Java 21、Ollama 0.32.4、本机显式模型、Windows 10。

| Metric | Before | After |
| --- | ---: | ---: |
| 可观察文本 Delta | 0 | 18 |
| 同回合结构化 Tool Call | 0 | 2 |
| 不同 Provider Call ID | 0 | 2 |
| Schema-only Callback 执行 | N/A | 0 |
| Usage | 不可用 | input 443 / output 64 |
| 客户端取消完成 | 未验证 | 约 1.25 秒，0 late signal |

Tool 名称顺序为 `sum_numbers`、`repeat_text`；两个 Result 进入下一轮后得到无 Tool Call
的最终文本。输出预算负例得到 `LENGTH`。Ollama 原生协议对照观察到相同的两个不同 ID、
相同顺序和 Usage。完整 Prompt 和自然语言输出不作为证据或 Golden Output。

### 4.3 Opt-in 真实 Provider E2E

本次运行指纹只用于复现实验证据，不是代码默认值：

| 字段 | 值 |
| --- | --- |
| Ollama | `0.32.4` |
| Model Tag | `qwythos:9b-claude-mythos-5-1m-mtp-q6_k` |
| Model Digest | `3ab864d8e36f3e41088418952f30b7618bbac07c69ab256d9ce53244bb161616` |

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am test `
  '-Dtest=OllamaProviderE2ETest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dcc.java.ollama.e2e.model=<LOCAL_MODEL>'
```

实际结果：该 opt-in 场景独立运行两次均为 1 通过、0 失败、0 错误、0 跳过；首次约
97 秒，复验为 110.051 秒。测试验证真实文本流、两个 Tool Call 及顺序、Result 下一
回合、Usage、`LENGTH` 和客户端取消；不断言固定自然语言。普通 CI 未提供系统属性时，
该测试跳过。

### 4.4 真实 Main 与进程边界

先安装本轮 Reactor：

```powershell
$env:JAVA_HOME='C:\tmp\cc-java-jdk21\jdk-21.0.12+8'
.\mvnw.cmd -q -DskipTests install
```

通过 Maven Exec 启动真实 Main：

```powershell
.\mvnw.cmd -q -f .\cc-java-cli\pom.xml `
  org.codehaus.mojo:exec-maven-plugin:3.6.3:java `
  '-Dexec.mainClass=io.github.liumaishenjian.ccjava.cli.CcJavaMain' `
  '-Dexec.args=--model=<LOCAL_MODEL> --timeout-seconds=180 --max-output-tokens=64 --print=只回复S02正常'
```

另使用 `dependency:build-classpath` 生成绝对运行期 Classpath，再由
`ProcessStartInfo` 启动 `java ... CcJavaMain` 并分别捕获 stdout、stderr 和进程退出码。
实际观察：

| 场景 | Exit | stdout | stderr / ANSI |
| --- | ---: | --- | --- |
| Print，64 output tokens | 0 | 单行 Assistant 文本（本次 8 字符） | 含 `[model]` 状态；两路均无 ESC |
| Print，32 output tokens | 5 | 有界部分输出 | `LENGTH` 状态；两路均无 ESC |
| non-TTY 且无 `--print` | 3 | 无交互等待 | 提示使用 `--print` |
| PTY 自动化工具 | 3 | 无伪交互 | JLine 正确判定为 non-TTY |

自动化 PTY 结果只证明 non-TTY 降级；真实 Windows Terminal 的人工交互仍为
`Unknown`，没有虚构为通过。

本轮样本 stdout/stderr 没有 ESC；离线 Renderer 回归另验证模型内嵌 ESC/CSI/OSC/DCS
与 C0/C1 被清洗，Assistant 的 LF/Tab 语义得到保留，状态字段保持单行。流边界回归另
验证逐项背压、8 MiB/128 calls 本地上限、超限取消，以及同一 Chunk 重复 Call ID 的
结构化拒绝。Provider SDK 在 Adapter 收到对象前已经分配的单个巨大 `ChatResponse`
仍是边界外事实。

## G5：可复现 Demo — Open，待维护者验证

[S02 Model + Streaming CLI Demo](../demos/S02-model-streaming-cli.md)记录了离线、
opt-in Provider、真实 Main、长度负例和 non-TTY 负例的前置条件、命令、实际结果及事实
边界。这些证据已经覆盖 Print 正例、取消/不完整流负例和 non-TTY 降级，但 ADR-021
还明确要求 TTY 正例。当前自动化 PTY 被 JLine 正确识别为 non-TTY，不能替代真实
Windows Terminal 的 Interactive 多轮/编辑证据。用户已要求停止继续验证并由其后续
验证，因此 G5 保持 `OPEN`。

## G6：退出对账 — Pending

当前已完成：

- README、PRD、技术设计、ADR、证据、Demo 与 Gap Report 的 S02 内容对账；
- G0-G3 与最后一次 G4 候选运行已持久化，并明确保留服务端取消、真实限流和第二
  Provider 的 `Unknown`；
- 测试、Demo、行为对照与差距报告互链。

仍需由最终集成变更完成：

- 在最终 JUnit BOM 修正后的候选上复跑标准构建；
- 在真实 Windows Terminal 完成 Interactive TTY 正例并关闭 G5；
- 把 23 项实际等级和证据链接写回功能对照矩阵；
- 更新 `progress-state.properties` 的时间、变更、Gate 与代码/矩阵 Digest；
- 重新生成 `progress.html`，并通过生成、`--check` 和 `--self-test`；
- 在上述对账实际通过后，才把本证据包与 S02 Stage Exit 改为 `Accepted`。

## Feature → 证据索引

| Feature | 主要证据 |
| --- | --- |
| `BOOT-01/03` | Boot Composition Root 测试、真实 Main 与惰性 Provider 装配 |
| `CLI-01/02/03/04/06/10` | CLI 30 项离线测试、真实 Print、Ctrl+C Fixture、non-TTY/控制序列清洗/流分离 |
| `LOOP-04/08/09/10` | Core 37 项测试中的 Delta、取消、Deadline、Retry、`LENGTH`、本地响应上限与不完整流 |
| `MODEL-02/04/05/06` | Adapter 27 项离线测试、独立 Spike、opt-in Ollama E2E |
| `CTX-01` | Boot/Runtime 稳定 System Context 与 Provider-neutral Request 测试 |
| `CFG-01/02` | CLI → Environment → Defaults、URI/范围/Secret 负例测试 |
| `SESSION-02` | Interactive 同进程多轮和取消后继续 Fixture |
| `OBS-02/03/05` | Turn/Tool 确定性事件时间戳、可选 Usage、stdout/stderr 隐私和无 Prompt 默认日志 |

## Stage Exit 决定

G0-G3 与本轮专项证据已经形成；最后一次完整 G4 运行早于最终 JUnit BOM 修正，维护者
仍须复跑。G5 缺真实 TTY 正例，G6 仍等待功能矩阵、进度状态和生成看板的实际校验。
因此不能宣称 Stage Exit Accepted。流背压、本地 retained cap 和畸形响应分类已经具有
确定性回归；Provider SDK 在 Adapter 收到单个巨大对象前的分配，以及服务端取消仍不由
本证据保证。
