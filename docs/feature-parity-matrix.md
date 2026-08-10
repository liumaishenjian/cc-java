# cc-java 功能对照矩阵与学习路线

> 文档状态：Active Baseline
>
> 参考版本：R2026.03
>
> 最后更新：2026-08-10
>
> 当前代码状态：S01-S07 已 Accepted；S07 的 Canonical Transcript/Projection、条件式 C1-C4、文件记忆
> M1-M5、ready-only 零等待预取、内部 Usage View 与 latest-only Recovery Analytics 已在离线 Fake、Demo、
> Gap 和 Commit-scoped 对账中达到声明等级。S08 ADR-048 corrective implementation Commit
> `8fabd94b66881a4a8236cccabd4ae61dd39845d4` 的 Accepted 证据继续有效；[ADR-049](./adr/ADR-049-s08-explicit-file-mentions.md)
> 的显式 Workspace 文件引用已在实现 Commit `5910a8f` 上完成 Commit-scoped G0-G6，
> `CLI-13`、`CTX-19` 达到 L2，S08 恢复 Accepted。S09 已完成生产 Settings/Trust、Command、
> loopback HTTP、生命周期、Compact 与 transient Context Projection，G0-G6 Accepted；除仅限本地的
> `HOOK-10` 为 L1 外，本 Stage 条目达到 L2。S10 已完成 STDIO/Streamable HTTP、多 Server、Tool
> filter/prefix、Permission、Trust 与单次断线恢复的 Tool 主链并通过真实 Transport/Headless E2E，
> G0-G6 Accepted；`MCP-01`～`07` 为 L2，`MCP-09`～`11` 为 L1，`MCP-08` 仍为 L0；
> rules 编辑、Provider discovery/多模型注册、S13 OS Sandbox 与 S14 稳定协议/Export/Retention/Migration
> 仍未实现。S11 已在实现 Commit `71278431dd1e5c7c4e279b44f43e084755502a5d` 上完成 Commit-scoped G0-G6，量化、Demo 与能力对账均通过，Stage Exit Accepted；
> `SKILL-01..07`、`CTX-14`、`PLUGIN-01..03` 为 L2、`PLUGIN-04` 为 L1。S12 已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 Commit-scoped G0-G6 与 Stage Exit；`SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 为 L2，`SUB-06/HOOK-11` 为 L1。S13 已由 ADR-063/064 完成双源 G0-G2、Feature 目标与 ExecutionBackend 架构冻结；未写实现、Capability Level 无变化，G3-G6 与 Stage Exit 仍 Open。

## 1. 文档目的

这张矩阵是 `cc-java` 的长期学习导航和差距仪表盘。

它不要求逐行复制任何源码，而是要求：

- 知道成熟 Coding Agent 有哪些能力；
- 知道每项能力解决什么问题；
- 知道 Java 版本实现到了哪一级；
- 知道下一阶段应该补什么；
- 知道什么时候可以开始自己的创新。

## 2. 对照对象

第一轮对照固定为 **Reference Baseline R2026.03**，来源：

| 编号 | 来源 | 分类与使用边界 |
| --- | --- | --- |
| REF-01 | [Harness Engineering 架构分析](https://qingkeai.online/archives/Claude%20Code) | 二手分析，只形成 `Inferred` 研究问题 |
| REF-02 | [Claude Code 官方工作原理](https://code.claude.com/docs/en/how-claude-code-works) | 官方产品文档，候选公开行为 |
| REF-03 | [Claude Code 官方扩展能力](https://code.claude.com/docs/en/features-overview) | 官方产品文档，候选公开行为 |
| REF-04 | [Claude Code 官方权限模式](https://code.claude.com/docs/en/permission-modes) | 官方产品文档，候选公开行为 |
| REF-05 | [Claude Code 官方项目指令与记忆](https://code.claude.com/docs/en/memory) | 官方产品文档，候选公开行为 |
| REF-06 | [Claude Code 官方 Session](https://code.claude.com/docs/en/sessions) | 官方产品文档，候选公开行为 |
| REF-07 | [Claude Code 官方 Hooks](https://code.claude.com/docs/en/hooks) | 官方产品文档，候选公开行为 |
| REF-08 | [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) | 官方框架文档，只约束 Java Adapter |
| AUTH-01 | [已授权参考源码 `AUTH-SRC-2026-07-29-A`](./reference-baselines/R2026.03-authorized-source.md) | 仓库外只读机制研究；禁止复制、翻译、依赖或再发布；Revision/License 仍为 `Unknown` |
| QUARANTINE-01 | [历史隔离记录 `UNVERIFIED-SRC-2026-03-31-A`](./reference-baselines/R2026.03-unverified-source.md) | 已被 AUTH-01 与 ADR-022 取代，仅用于审计此前为何暂停 |

完整来源 Manifest 和可复现限制见
[R2026.03 公开行为基线](./reference-baselines/R2026.03-public-behavior.md)。
当前在线页面未归档、没有内容指纹；参考产品以后增加的功能不会自动进入本基线。
需要升级基线时，新建 R 版本并记录新增、删除和行为变化。

`R2026.03` 定义公开可验收行为。`AUTH-01` 只帮助解释成熟机制，不自动定义需求或证明
实现正确；项目仍必须根据公开行为、独立场景和本项目需求形成可验收设计。只有本项目
测试、Demo 或 Eval 通过后，才能标记为 `Verified in cc-java`。受控研究决定见
[ADR-022](./adr/ADR-022-reactivate-authorized-reference-study.md)。

## 3. 完成度等级

每项能力使用统一等级：

| 等级 | 名称 | 判定 |
| --- | --- | --- |
| L0 | Not Started | 没有代码或只有文档设想 |
| L1 | Learning Skeleton | 最小实现跑通，主要用于解释核心原理 |
| L2 | Usable | 可在真实小型项目使用，有基本错误和安全处理 |
| L3 | Reference Comparable | 覆盖参考基线的主要公开行为，并通过对照测试 |
| L4 | Differentiated | 在 L3 基础上有可评测的独立创新 |

“写了接口”不等于 L1；“Demo 能跑”不等于 L2；“功能名字相同”不等于 L3。

### 3.1 最终目标与阶段检查点

- 矩阵内所有未特别标注的参考能力，**最终重实现目标默认为 L3**；
- 一个 Stage 第一次通过时，其条目至少达到该 Stage 完成定义要求的 L1 或 L2，这是学习检查点，不会从矩阵删除；
- 后续迭代继续把已通过 Stage 的条目从 L1/L2 推进到 L3；
- 如果某项公开能力决定不实现，必须标记为 `Accepted Deviation`，写明原因、影响和替代方案，不能用“暂不需要”隐藏差距；
- L4 只用于本项目已经达到 L3 后、且被评测证明有效的独立设计。

因此项目既能分阶段运行，也始终保留“距离参考能力 L3 还有多少”的完整答案。

## 4. 四类差距指标

项目不使用一个含糊的“完成百分比”，而是同时看四类指标。

### 4.1 Capability Coverage

```text
能力覆盖 = Σ(能力权重 × min(当前等级, 目标等级) / 目标等级) / Σ能力权重
```

阶段版本可以先达到 L1/L2，但参考重实现阶段的默认终点是 L3；明确排除的商业服务能力列在参考架构文档中，不进入分母。

### 4.2 Behavioral Conformance

每项能力维护黑盒行为测试：

- 输入；
- 可观察事件；
- 权限交互；
- 环境副作用；
- 结束状态；
- 预期不变量。

```text
行为符合度 = 通过的对照行为测试 / 已定义的对照行为测试
```

### 4.3 Reliability & Safety

单独统计：

- 无限循环；
- 越界访问；
- 未审批副作用；
- 遗留子进程；
- Session 损坏；
- Context 协议破坏；
- 取消和超时失败；
- 敏感信息泄漏。

### 4.4 Learning Evidence

每个 Stage 至少留下：

- 一篇设计说明或 ADR；
- 一组离线测试；
- 一个可运行 Demo；
- 一次与参考行为的差距复盘。

只让 AI 生成代码但本人不能解释关键决策，不算完成学习阶段。

## 5. 学习阶段总览

| Stage | 主题 | 核心交付 |
| --- | --- | --- |
| S00 | Harness 地图 | 参考架构、公开行为基线、授权研究登记、矩阵、术语和边界 |
| S01 | Runtime Kernel（Agent Loop） | Fake Model 驱动的显式循环 |
| S02 | Model + Streaming CLI | 一个真实 Provider、Java Headless、实验性 stdio、React/Ink TUI 与事件流 |
| S03 | Read Tools | 在真实仓库自主搜索和解释代码 |
| S04 | Write + Command | 经审批修改代码、执行测试、根据结果继续 |
| S05 | Permission Pipeline | 模式、规则、审批、生命周期和拒绝恢复 |
| S06 | Session + Checkpoint | JSONL、resume/fork、崩溃检测和 undo |
| S07 | Context Engineering | Token 预算、Turn 淘汰、摘要、压缩和防抖 |
| S08 | Instructions + Settings | 用户/项目/目录指令、配置层级和 Context CLI |
| S09 | Hooks | 可配置生命周期扩展与阻断 |
| S10 | MCP | 外部 Server、Tool 过滤、权限统一 |
| S11 | Skills + Plugins | 懒加载工作流、扩展打包和信任 |
| S12 | Sub-Agent + Worktree | RuntimeScope、单 Agent、并发/后台和 Worktree 检查点 |
| S13 | Sandbox + Security | Process/File/Network 隔离和攻击测试 |
| S14 | Production Harness | Eval/Observability、SDK/Headless、Distribution/Compatibility |
| S15 | Independent Innovation | 基于对照结果实现 Java 差异化 |

Stage 是学习顺序，不是要求等到上一阶段 100% 成熟才能开始下一阶段。每次只允许少量跨阶段工作，并在矩阵中说明原因。

### 5.1 当前差距快照

| 指标 | R2026.03 当前值 |
| --- | --- |
| 纳入追踪的 Capability ID | 197 |
| 当前阶段 | S13 Sandbox + Security；G0-G2 已冻结，G3-G6 Open |
| Stage Exit | S09/S10/S11/S12 Accepted；S13 Stage Exit Open |
| 当前等级 | 122 项为 L2，38 项为 L1，37 项为 L0（G0-G2 不提升等级） |
| 默认最终目标 | 197 项达到 L3，或存在明确 `Accepted Deviation` |
| 当前能力覆盖 | 47.72%（197 项等权、目标 L3） |
| 下一步 | 按 ADR-064 Batch A-C 实现；取得 WSL2+bwrap Linux A、Docker B、native Windows B 的诚实证据前不得提升对应能力 |

每次新增、合并或排除 Capability ID 时必须同步更新这张快照。

S01 等级证据见 [Runtime Kernel ADR](./adr/ADR-017-s01-runtime-kernel.md)、
[离线 Agent Loop Demo](./demos/S01-agent-loop.md)和
[S01 标准验证证据](./evidence/S01-runtime-kernel-2026-07-28.md)、
[S01 差距报告](./gap-reports/S01.md)。本阶段统一只记为 L1，不把离线 Fake
回放宣传为真实任务可用的 L2。

S02 的 24 项完整范围、19 项 L2 / 5 项 L1 退出目标及可证伪 Spike 见
[ADR-021](./adr/ADR-021-s02-model-streaming-cli-scope.md)与
[ADR-023](./adr/ADR-023-s02-java-headless-ink-tui.md)。Java Fake stdio 与 React/Ink
Spike 已留下证据，但尚未提升 Capability Level；真实 Provider 方向见
[ADR-024](./adr/ADR-024-s02-openai-compatible-first-provider.md)，Java Print 决策和
真实演示见 [ADR-025](./adr/ADR-025-s02-picocli-java-print.md)，CLI Override 与
Runtime Deadline 见 [ADR-026](./adr/ADR-026-s02-cli-overrides-run-deadline.md)。
模型流聚合、重试、不完整流与长度终态见
[ADR-027](./adr/ADR-027-s02-model-stream-resilience.md)。Windows 两阶段中断、Paste、
Resize 与直接子进程退出边界见
[ADR-028](./adr/ADR-028-s02-windows-terminal-lifecycle.md)；本轮不改变 Capability
Level，原因见对应证据。
连续 Java Headless Session、跨 Run 规范历史和双 Run stdio 证据见
[ADR-029](./adr/ADR-029-s02-continuous-session.md)。
Run/Turn/Tool 事件边界耗时、Provider Usage 完整覆盖语义与默认最小化观测出口见
[ADR-030](./adr/ADR-030-s02-privacy-safe-run-telemetry.md)。
真实 Provider 同回合只生成第一个 Tool Call 的边界与重新验证条件见
[ADR-031](./adr/ADR-031-s02-provider-multi-tool-deviation.md)。

### 5.2 Stage 退出目标与跨阶段路径

`Stage` 列表示能力进入实现的阶段，不等于该阶段自动达到 L3。每个 Stage 必须在证据包中
提交所选 Feature 的 `Current → Exit Target`；没有目标等级和证据链接的能力不能作为
Stage 完成项。

`Required Feature` 按以下规则判定：

1. `Stage` 列只含一个 Stage 的条目，是该 Stage 的 Required Feature；
2. 多 Stage 条目在首个非终点 Stage 默认达到 L1 契约/局部能力，在最后一个 S01-S13
   Stage 达到 L2 可用行为；若首个 Stage 同时也是最后一个 S01-S13 Stage，以 L2
   终点规则优先；若包含 S14，则在 S14 达到 L3 参考可比；
3. `Java 重实现目标` 或下方跨阶段路径写出的显式 `Sx:Ly` 覆盖默认规则；
4. Stage 启动 ADR 必须列出全部匹配条目及本次目标，只有记录理由和后续 Stage 的
   `Deferred` 才能暂缓；
5. Optional 能力必须在矩阵或 Stage ADR 中显式标记，不能由实现者临时推断。

默认检查点：

| Stage | 默认退出目标 | 说明 |
| --- | --- | --- |
| S00 | 文档 Gate | 公开行为基线、授权研究边界、矩阵和术语可追踪 |
| S01 | Required Feature 达到 L1 | 只证明离线协议学习骨架 |
| S02-S13 | 单 Stage 条目或多 Stage 终点达到 L2 | 多 Stage 的非终点首阶段可按上方规则只达到 L1 |
| S14 | 核心 Harness 达到 L3，其余 Required Feature 至少 L2 | 建立参考可比、兼容和生产证据 |
| S15 | 被采纳创新达到 L4 | 相对 L3 基线有量化收益且无不可接受回归 |

多阶段能力按下列路径解释；每个箭头都需要独立证据：

| 能力族 | 目标路径 |
| --- | --- |
| Cancellation / Limits | S01 保留契约缝隙 → S02 模型流取消 L2 → S04 Tool/进程树取消与输出/时间限制 L2 → S14 跨平台 L3 |
| Permission | S01 Port/Fake L1 → S04 固定读允许、写/命令询问和安全 PLAN L2 → S05 完整规则/优先级/恢复 L2 → S10/S11/S13 外部来源与安全回归后 L3 |
| Tool Result / Context | S03-S04 单工具硬上限和外置元数据 L2 → S06 决策持久化 L1 → S07 公开来源驱动的 Context Reduction L2 → S14 Provider Cache/Context Editing 对照后 L3 |
| Session | S01 内存 Session L1 → S06 JSONL/resume/fork/checkpoint L2 → S14 Export/Retention/迁移兼容 L3 |
| Instructions | S03 根 `AGENTS.md` L2 → S08 分层/路径规则与来源诊断 L2 → S14 兼容与迁移 L3 |
| Eval / Observability | S01 确定性回放 L1 → S04/S07/S12/S13 专项 Eval L2 → S14 统一指标、回放和报告 L3 |

完整 G0-G6 字段见 [Stage 证据包模板](./templates/stage-evidence-package.md)。

## 6. Scaffolding / Bootstrap 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| BOOT-01 | CLI 启动并识别运行模式 | Picocli Headless + React/Ink Interactive | L2 | S02 | REF-02/AUTH-01 |
| BOOT-02 | 确定 Workspace 与 Git 状态 | `WorkspaceSnapshot` | L2 | S03 | REF-02 |
| BOOT-03 | 加载模型、工具和权限 | `SessionBootstrapper` | L2 | S02/S05 | REF-01 |
| BOOT-04 | 加载项目指令 | user/project/directory/local 分层发现、预算、去重与请求投影 | L2 | S03/S08 | REF-05 |
| BOOT-05 | 创建 Session 和初始 Context | `SessionStore` + `ContextAssembler` | L1 | S01 | REF-02 |
| BOOT-06 | 启动诊断 | Java Application 只读 doctor 的来源/状态安全投影；完整 Surface 配置来源报告延期 | L2 | S08/S14 | REF-02 |
| BOOT-07 | 延迟加载高成本能力 | Lazy Tool/Skill/MCP Metadata | L0 | S07/S10/S11 | REF-01/03 |

## 7. Terminal / Interface 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CLI-01 | Interactive Session | React/Ink TUI + Java Application Session | L2 | S02 | REF-02/AUTH-01 |
| CLI-02 | Print / Headless | Picocli `--print` | L2 | S02 | REF-02 |
| CLI-03 | 流式 Assistant Text | stdio Event → Markdown Ink 组件渲染 | L2 | S02/S03 | REF-02/AUTH-01 |
| CLI-04 | Tool 进度与输出 | 有序 Agent Event → 连续 Tool 语义化聚合 | L2 | S02/S03 | REF-02/AUTH-01 |
| CLI-05 | Permission Prompt | 终端 Approval UI | L2 | S04/S05 | REF-04/AUTH-01 |
| CLI-06 | Ctrl+C Cancel | 当前 Run/Tool 取消 | L1 | S02/S04 | REF-02 |
| CLI-07 | Steering | Java stdio 连接内存 FIFO 上限 100；只在当前 Run 终态后启动下一 Run，取消/clear/成功 resume/transport failure/shutdown 丢弃未发送项且不持久化 | L2 | S08 | REF-02 |
| CLI-08 | Slash Commands | 封闭 Command Catalog、可见选择与类型化安全结果 Projection；ADR-048 Commit-scoped 协议、TTY 与 review 证据通过 | L2 | S08 | REF-02 |
| CLI-09 | 多行、历史、补全 | `ComposerState` grapheme 光标、视觉多行/viewport、完整编辑键、History/Completion 优先级；8,192 仅限可见结构，约 1 MiB 无损大 Paste 经 stdio v0 原子分块提交后仍受 256K Token Context pipeline 权威治理；ADR-048 Commit-scoped 测试/TTY/review 通过 | L2 | S08 | REF-02 |
| CLI-10 | TTY / Non-TTY 降级 | 无 ANSI 输出与管道模式 | L2 | S02/S14 | REF-02 |
| CLI-11 | 机器输出协议 | S02 内部 stdio v0 L1 → S14 稳定 JSON/JSONL L3 | L1 | S02/S14 | REF-02 |
| CLI-12 | 多 Surface 共用引擎 | CLI/SDK/API 共用 Runtime | L0 | S14 | REF-02 |
| CLI-13 | 显式文件引用 | Java 权威原始相对路径候选、严格 stdio 关联、TUI 引号格式化与 Composer token 精确替换；ADR-049 Commit-scoped G6 通过 | L2 | S08 | REF-02/AUTH-01 |

## 8. Agent Loop 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| LOOP-01 | 显式循环状态 | `AgentRunState` | L1 | S01 | REF-01 |
| LOOP-02 | User → Model → Tool → Model | User-controlled Loop | L1 | S01 | REF-01/08 |
| LOOP-03 | 单回合多个 Tool Call | 保持消息和 Call ID 协议 | L1 | S01 | REF-08 |
| LOOP-04 | 流式 Model Turn | Delta + 聚合结果 | L2 | S02 | REF-02/08 |
| LOOP-05 | Tool Result 驱动下一回合 | Canonical Message History | L1 | S01 | REF-08 |
| LOOP-06 | 多 Continue 原因 | 显式 Transition/Stop Reason | L1 | S01/S07 | REF-01 |
| LOOP-07 | 最大回合和工具数 | `AgentLimits` | L1 | S01 | REF-01 |
| LOOP-08 | Deadline 与取消 | Cancellation 传播 | L1 | S02/S04 | REF-02 |
| LOOP-09 | 模型错误重试 | 有界 Retry Policy | L2 | S02/S14 | REF-01/AUTH-01 |
| LOOP-10 | Model Output Length Recovery | S02 识别截断/不完整输出并有界停止或续接 → S14 L3 恢复策略 | L2 | S02/S14 | REF-01/AUTH-01 |
| LOOP-11 | Context 溢出恢复 | typed overflow 后最多一次 C3/C4 恢复；失败或第二次 overflow 明确停止 | L2 | S07 | REF-01/02 |
| LOOP-12 | Model Fallback | Provider-aware Fallback | L0 | S14 | REF-01 |
| LOOP-13 | 用户拒绝后继续推理 | Denied Tool Result 回传模型 | L2 | S05 | REF-04 |
| LOOP-14 | 崩溃和未完成 Tool | Session Recovery Gate | L2 | S06 | REF-06/AUTH-01 |

## 9. Model Runtime 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| MODEL-01 | Model Gateway | Provider-neutral Port | L1 | S01 | REF-08 |
| MODEL-02 | 一个真实 Provider | Spring AI Adapter | L2 | S02 | REF-08 |
| MODEL-03 | Scripted Fake Model | 确定性测试 | L1 | S01 | REF-08 |
| MODEL-04 | Text Streaming | Adapter 内消费 Flux | L2 | S02 | REF-08 |
| MODEL-05 | Tool Call Streaming | Chunk 聚合 | L2 | S02 | REF-08/AUTH-01 |
| MODEL-06 | Usage / Finish Reason | 规范化 Capability | L2 | S02 | REF-08 |
| MODEL-07 | 第二 Provider | S14 验证 Provider-neutral Port | L0 | S14 | REF-02 |
| MODEL-08 | Model Switching | 有界 Session Command：仅接受启动时配置的当前单一模型名；Provider discovery/多模型注册延期 | L2 | S08 | REF-02 |
| MODEL-09 | Prompt Cache | 稳定前缀和 Provider Hint | L0 | S07/S14 | REF-01 |
| MODEL-10 | Rate Limit / Retry | Provider Error Policy | L0 | S14 | REF-01 |
| MODEL-11 | Cost Budget | Token 与价格模型 | L0 | S14 | REF-01 |
| MODEL-12 | Capability Detection | Tools/Streaming/Context/Reasoning | L0 | S14 | REF-02 |

## 10. Tool System 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| TOOL-01 | Tool Definition + Schema | Framework-free Contract | L1 | S01 | REF-08 |
| TOOL-02 | Tool Registry | Source-aware Registry | L1 | S01 | REF-01 |
| TOOL-03 | 统一执行 Pipeline | Validate → Permit → Execute → Normalize | L2 | S01/S05 | REF-01/07 |
| TOOL-04 | List / Glob | Workspace 文件枚举 | L2 | S03 | REF-02 |
| TOOL-05 | Grep / Search | 受控 ripgrep：完整参数、三模式、JSON 结果、取消与一次资源恢复；Java 字面降级 | L2 | S03 | REF-02/AUTH-01 |
| TOOL-06 | Read File | 固定窗口严格 UTF-8 范围读取、行号、扫描/单行/输出上限、权威 continuation 与同范围未变化结果 | L2 | S03 | REF-02/AUTH-01 |
| TOOL-07 | Git Status / Diff | 脏工作区和证据 | L2 | S03/S04 | REF-02 |
| TOOL-08 | Apply Patch / Edit | LF/CRLF 规范精确匹配、BOM/换行外观保留、先读覆盖证据、冲突重检、同目录原子替换与有界摘要 | L1 | S04 | REF-02/AUTH-01 |
| TOOL-09 | Write / Create | 仅创建新 UTF-8 文件、父目录 realpath 与禁止覆盖 | L1 | S04 | REF-02/AUTH-01 |
| TOOL-10 | Run Command | 固定平台 Shell/Workspace、准确审批、timeout 与 exit code | L1 | S04 | REF-02/AUTH-01 |
| TOOL-11 | Tool Output Streaming | 有界 stdout/stderr Lifecycle/stdio v0/TUI Event | L1 | S04 | REF-02/AUTH-01 |
| TOOL-12 | Result Truncation | 显式截断、超长行计数、与已返回正文一致的结构化 continuation 和摘要 | L2 | S03/S07 | REF-01/AUTH-01 |
| TOOL-13 | Structured Tool Error | 模型可纠正错误 | L2 | S01/S03 | REF-01 |
| TOOL-14 | Tool Cancellation | 文件提交前取消 L1 → Process 取消与进程树 L2 | L1 | S04 | REF-02 |
| TOOL-15 | 并行安全工具 | 白名单 READ_WORKSPACE 同批并发已接入完整 AgentRuntime batch 与唯一 Pipeline，稳定按原 Call ID/顺序归并并覆盖取消收敛和真实墙钟门槛 | L2 | S12 | REF-01 |
| TOOL-16 | Tool Search / Lazy Schema | 大工具集按需加载 | L0 | S10/S11 | REF-02/03 |
| TOOL-17 | Code Intelligence | LSP / Symbol Tool | L0 | S14/S15 | REF-02/03 |
| TOOL-18 | Web Tool | 可控网络检索 | L0 | S14 | REF-02 |

## 11. Permission 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| PERM-01 | Tool Effect 分类 | Read/Write/Process/Network/System | L2 | S04/S05 | REF-04/AUTH-01 |
| PERM-02 | Manual / Default | 副作用默认询问 | L1 | S04 | REF-04/AUTH-01 |
| PERM-03 | Plan Mode | S04 固定安全模式 → S05 可配置策略 | L2 | S04/S05 | REF-04/AUTH-01 |
| PERM-04 | Accept Edits | Workspace Write 自动批准 | L2 | S05 | REF-04 |
| PERM-05 | Auto Mode | 独立安全决策器 | L0 | S13/S15 | REF-04 |
| PERM-06 | Allow / Ask / Deny | 声明性规则 | L2 | S05/S08 | REF-04 |
| PERM-07 | Allow Once / Session | 范围化审批缓存 | L2 | S04/S05 | REF-04/AUTH-01 |
| PERM-08 | Protected Paths | 不可写路径 | L2 | S05/S13 | REF-04 |
| PERM-09 | Hard Denial | 不可被项目配置覆盖 | L2 | S05/S13 | REF-01/04 |
| PERM-10 | Permission Event | 可观察与 Hook | L2 | S05/S09 | REF-07 |
| PERM-11 | Denial Tracking | 重复拒绝降级 | L2 | S05/S14 | REF-01/04 |
| PERM-12 | Project/User/Managed Scope | user/project/local Settings 来源与 Session overlay；Managed Policy 延期 S13 | L2 | S08/S13 | REF-04 |
| PERM-13 | Print Mode Policy | 无交互时确定性处理 ASK | L2 | S05 | REF-04 |

## 12. Lifecycle / Hooks 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| HOOK-01 | 内部 Lifecycle Event | Session/Run/Model/Tool/Permission | L2 | S01/S05 | REF-07 |
| HOOK-02 | Pre Tool | 执行前观察和阻断 | L2 | S05/S09 | REF-07/AUTH-01 |
| HOOK-03 | Post Tool | 结果观察和附加 Context | L2 | S05/S09 | REF-07/AUTH-01 |
| HOOK-04 | Session Start/End | 环境和清理扩展 | L2 | S09 | REF-07/AUTH-01 |
| HOOK-05 | User Prompt / Run | 输入处理 | L2 | S09 | REF-07/AUTH-01 |
| HOOK-06 | Permission Request | 自定义审批逻辑 | L2 | S09 | REF-07/AUTH-01 |
| HOOK-07 | External Compact Hooks | 建立在 S07 内部事件之上的可配置扩展 | L2 | S09 | REF-07/AUTH-01 |
| HOOK-08 | Sub-Agent Hooks | trusted start 可阻断/附加 child untrusted Context；durable terminal 后 stop 只观察，其 additional context 一次性投影父下一回合 | L2 | S12 | REF-07 |
| HOOK-09 | Command Hook | JSON stdin/stdout + Exit Policy | L2 | S09 | REF-07/AUTH-01 |
| HOOK-10 | HTTP Hook | 受控远程回调 | L1 | S09/S13 | REF-07/AUTH-01 |
| HOOK-11 | Prompt / Agent Hook | S12 仅 host-trusted definition narrowing seam；模型决策延期 S15，工作树候选 L1 | L1 | S12/S15 | REF-07 |
| HOOK-12 | Matcher / Scope | Tool、路径、Session 条件 | L2 | S09 | REF-07/AUTH-01 |
| HOOK-13 | Timeout / Error Policy | 阻断与非阻断错误 | L2 | S09 | REF-07/AUTH-01 |

## 13. Sandbox / Security 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SEC-01 | Workspace Realpath | 路径与新文件父目录校验 | L1 | S03/S04 | REF-02 |
| SEC-02 | Symlink / Junction | 跨平台逃逸测试 | L1 | S03/S13 | REF-02 |
| SEC-03 | Sensitive Files | 默认拒绝和配置 | L1 | S03/S13 | REF-04 |
| SEC-04 | Process Tree Control | timeout/cancel、Windows taskkill 与 ProcessHandle 清理 | L1 | S04/S13 | REF-02/AUTH-01 |
| SEC-05 | Environment Filtering | 固定 allowlist，不继承 Provider Key/未知 Secret | L1 | S04/S13 | REF-01/AUTH-01 |
| SEC-06 | File Sandbox | Workspace 级 OS 隔离 | L0 | S13 | REF-01 |
| SEC-07 | Network Sandbox | Domain/Port Policy | L0 | S13 | REF-01 |
| SEC-08 | Container Backend | 隔离执行环境 | L0 | S13 | REF-01 |
| SEC-09 | Prompt Injection Defense | 不可信仓库测试 | L2 | S05/S13 | REF-02 |
| SEC-10 | Secret Redaction | Event/Log/Tool Result | L1 | S03/S14 | REF-01 |
| SEC-11 | Plugin Supply Chain | 签名、信任和隔离 | L0 | S11/S13 | REF-03 |
| SEC-12 | Security Regression Suite | 攻击性 Fixture | L0 | S13 | REF-01 |

## 14. Context Engineering 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CTX-01 | System Context Assembly | 稳定策略 + Runtime Metadata | L2 | S01/S02 | REF-02 |
| CTX-02 | Project Instructions | 根 AGENTS.md | L2 | S03 | REF-05 |
| CTX-03 | Hierarchical Instructions | User/Project/Directory | L2 | S08 | REF-05 |
| CTX-04 | Path-scoped Rules | 只在相关目标路径加载并保持固定顺序 | L2 | S08 | REF-05 |
| CTX-05 | Tool Result Limits | 类型化裁剪 | L2 | S03/S04 | REF-01 |
| CTX-06 | Token Budget | 显式容量元组的 model-aware 预算与来源 Usage View；`codej` 默认显式传递 256,000/8,192/4,096 且允许覆盖 | L2 | S07 | REF-01/02 |
| CTX-07 | Complete Turn Eviction | 完整 Tool Call/Result 协议边界、C2 占位与 Canonical 不变 | L2 | S07 | REF-01 |
| CTX-08 | Old Tool Output Clear | 按压力选择 C2 清理低价值旧 Tool 输出 | L2 | S07 | REF-02 |
| CTX-09 | Conversation Summary | C3/C4 有界候选、严格提交 Gate 与零 Tool Provider Summarizer | L2 | S07 | REF-01/02 |
| CTX-10 | Multi-level Compaction | C1/C2 后条件式 C3→C4、预算满足即停 | L2 | S07 | REF-01 |
| CTX-11 | Thrashing Guard | Run/revision/tier 冷却与单次 typed-overflow 恢复 | L2 | S07 | REF-02 |
| CTX-12 | Compact Instructions | 无参数时针对当前 Session、有界 anchors 可选的显式 compact，经既有 S07 Gate 生成一次性下一 Run 首个模型请求 Projection | L2 | S08 | REF-02 |
| CTX-13 | `/context` | 最新 `ContextUsageView` 的数值/枚举白名单命令、stdio/封闭 Slash/TUI 协议投影 | L2 | S07/S08 | REF-02 |
| CTX-14 | Skill Lazy Loading | Metadata-first catalog，正文/资源仅在调用时进入 transient Projection；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03 |
| CTX-15 | Sub-Agent Isolation | 独立 child Session/Context/Permission/Tool scope 与 Workspace identity；完整 transcript/正文不注入父 Context，仅有界 report 与 Hook context | L2 | S12 | REF-02/03 |
| CTX-16 | Prompt Cache | 稳定前缀和 Tool 顺序 | L0 | S14 | REF-01 |
| CTX-17 | Auto Memory Index | `MEMORY.md`、有界 topic Catalog、可重建索引与真实 Headless 文件装配 | L2 | S07 | REF-05/AUTH-01 |
| CTX-18 | Relevant Memory Prefetch | M4/M5 ready-only、零等待相关记忆投影与迟到结果隔离 | L2 | S07 | REF-05/AUTH-01 |
| CTX-19 | File Attachment Projection | WorkspaceGuard 后的不可变文件快照、Canonical/Session Resume/Fork 保存、Base64 不可信模型投影与保守 Usage 估算；ADR-049 Commit-scoped G6 通过 | L2 | S08 | REF-02/AUTH-01 |

## 15. Settings / Configuration 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CFG-01 | CLI Overrides | Picocli 参数 | L2 | S02 | REF-02 |
| CFG-02 | Provider Runtime Configuration | Git 忽略的 Provider 本地文件 + 环境变量覆盖 | L2 | S02 | REF-02 |
| CFG-03 | User Settings | `~/.cc-java/` 固定来源、严格解析与 provenance | L2 | S08 | REF-01 |
| CFG-04 | Project Settings | Workspace 固定版本控制来源 | L2 | S08 | REF-01 |
| CFG-05 | Local Settings | 仅 Gitignore 可证明时加载的本地覆盖 | L2 | S08 | REF-01 |
| CFG-06 | Session Overrides | 有界 Slash/stdio Session patch：仅 model 或 PermissionMode，保留其余 overlay 和 CLI precedence；rules 编辑延期 | L2 | S08 | REF-01 |
| CFG-07 | Managed Policy | 不可覆盖组织策略 | L0 | S13/S14 | REF-01 |
| CFG-08 | Merge Semantics | Scalar/Object/List/delete/rule 的确定性逐字段合并 | L2 | S08 | REF-01 |
| CFG-09 | Config Diagnostics | 来源、provenance、LKG 状态与隐私安全 doctor | L2 | S08 | REF-01 |
| CFG-10 | Migration | Schema Version 与升级 | L0 | S14 | REF-01 |
| CFG-11 | Feature Gates | 实验能力开关 | L0 | S14 | REF-01 |

## 16. Session / Checkpoint 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SESSION-01 | Session ID | Workspace-aware ID | L1 | S01 | REF-06 |
| SESSION-02 | In-memory Session | 当前对话连续性 | L2 | S01/S02 | REF-06/AUTH-01 |
| SESSION-03 | Append-only JSONL | 版本化事件存储 | L2 | S06 | REF-06/AUTH-01 |
| SESSION-04 | Continue | 最近 Session | L2 | S06 | REF-06/AUTH-01 |
| SESSION-05 | Resume | 选择 Session | L2 | S06 | REF-06/AUTH-01 |
| SESSION-06 | Fork | 新 ID 复制历史 | L2 | S06 | REF-06/AUTH-01 |
| SESSION-07 | Session Metadata | Model/Workspace/Config/Usage | L2 | S06 | REF-06/AUTH-01 |
| SESSION-08 | Concurrent Open Detection | 锁与只读恢复 | L1 | S06/S14 | REF-06/AUTH-01 |
| SESSION-09 | Incomplete Tool Recovery | 不自动重放副作用 | L2 | S06 | REF-06/AUTH-01 |
| SESSION-10 | File Checkpoint | 写入前快照 | L2 | S06 | REF-02/AUTH-01 |
| SESSION-11 | Rewind / Undo | 恢复 Agent 文件修改 | L2 | S06 | REF-02/AUTH-01 |
| SESSION-12 | Export | 稳定外部格式 | L0 | S14 | REF-06 |
| SESSION-13 | Retention / Clear | 生命周期管理 | L0 | S14 | REF-06 |
| SESSION-14 | SQLite Adapter | 大量 Session 索引 | L0 | S14 | REF-06 |

## 17. MCP 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| MCP-01 | STDIO Client | Spring AI 同步 MCP Client | L2 | S10 | REF-03/AUTH-01 |
| MCP-02 | Streamable HTTP | 远程 Server | L2 | S10 | REF-03/AUTH-01 |
| MCP-03 | Multi Server | 生命周期与隔离 | L2 | S10 | REF-03/AUTH-01 |
| MCP-04 | Tool Mapping | MCP → Tool Registry | L2 | S10 | REF-03/AUTH-01 |
| MCP-05 | Permission Integration | 同一 Pipeline | L2 | S10 | REF-03/AUTH-01 |
| MCP-06 | Tool Filtering | Allowlist / Denylist | L2 | S10 | REF-03/AUTH-01 |
| MCP-07 | Name Collision | Server 前缀 | L2 | S10 | REF-03/AUTH-01 |
| MCP-08 | Lazy Tool Loading | Tool Search | L0 | S10/S11 | REF-02/03 |
| MCP-09 | Resources / Prompts | Context Source | L1 | S10 | REF-03/AUTH-01 |
| MCP-10 | Authentication | OAuth/API Key | L1 | S10/S13 | REF-03/AUTH-01 |
| MCP-11 | Trust UX | Server 来源和风险提示 | L1 | S10/S13 | REF-03/AUTH-01 |

## 18. Skills / Plugins 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SKILL-01 | Skill Metadata | 严格有界 metadata、触发方式与 immutable catalog snapshot；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| SKILL-02 | Markdown Workflow | 按 digest 加载的不可信 Markdown workflow 与 transient Projection；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| SKILL-03 | Explicit Invocation | `/skill-name` 类型化入口，共用 SkillInvoker；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| SKILL-04 | Model Invocation | metadata catalog → 普通 Skill Tool；成功投影后激活，禁止 nested/reentrant；每个真实 Tool Call 仍逐次走 Permission/Approval/Pipeline；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| SKILL-05 | Lazy Content | 本地 Skill 启动只 materialize metadata，正文/资源调用时加载；Plugin Skill 在受信 Session composition 冻结；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| SKILL-06 | Bundled Resources | Skill-root 内普通 UTF-8 资源，有界且不执行脚本；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01 |
| SKILL-07 | Scoped Hooks | 正文成功投影后启用并持续到 Run 唯一终态；无活动 Run 的 Resume 不恢复；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/07/AUTH-01 |
| PLUGIN-01 | Plugin Manifest | 严格 v1 manifest、namespace、tree fingerprint 与 immutable snapshot；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| PLUGIN-02 | Bundle Skills/Hooks/MCP | 只打包已验证组件，不建立第二套 Runtime；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01/CODEX-0.147 |
| PLUGIN-03 | Tool Provider SPI | 宿主 factory 返回有 lease/close 所有权的 Contribution；MCP-backed 仅引用 named Server，可信 PLUGIN Tool 逐次 ASK/Pipeline；拒绝任意 JAR；S11 Commit-scoped 验收达到 L2 | L2 | S11 | REF-03/AUTH-01 |
| PLUGIN-04 | Install / Uninstall | 本地 staged install 与 quiescing uninstall 经 S11 Commit-scoped 验收达到 L1；S14 再实现可恢复/迁移 L2 | L1 | S11/S14 | REF-03/AUTH-01 |
| PLUGIN-05 | Trust / Signature | fingerprint 不等于签名；S11 保持 L0，供应链控制延期 | L0 | S13/S14 | REF-03 |
| PLUGIN-06 | Marketplace | S11 保持 L0；发现、联网安装和分发延期 | L0 | S14/S15 | REF-03 |

## 19. Sub-Agent / Worktree 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SUB-01 | Agent Definition | User/Project strict schema、Extension/S08 精确 Project Trust、冲突隔离、TOCTOU identity 重检与 immutable digest snapshot 已完成 | L2 | S12 | REF-03 |
| SUB-02 | Runtime Reuse | Headless production Composition 重新装配 child scope 并复用同一 AgentRuntime，无第二 Loop | L2 | S12 | REF-03 |
| SUB-03 | Isolated Context | 独立 child Session、Canonical/Projection、Permission state、Registry 与 Workspace bootstrap，父仅接收有界 report | L2 | S12 | REF-02/03 |
| SUB-04 | Parent/Child Task | 显式 identity/status、唯一 durable terminal、隐私 report、journal fail-closed 与 no-replay 恢复 registry | L2 | S12 | REF-03 |
| SUB-05 | Tool-restricted Agent | definition/request/host 纯交集，每个 child Tool 调用重走独立 Permission/Approval/Pipeline | L2 | S12 | REF-03 |
| SUB-06 | Model/Budget Override | 已配置模型子集、父预算原子 reservation 与 actual settlement；Provider discovery/pricing 延期，按冻结目标 L1 | L1 | S12 | REF-03 |
| SUB-07 | Concurrent Agents | 公平 active≤4、queue≤32、depth≤2，同一 Supervisor 共享调度与预算，真实并发 Eval 无超卖 | L2 | S12 | REF-03 |
| SUB-08 | Background Agent | 同进程 inspect/wait/cancel、异步 terminal observer、no-replay 恢复 registry 与有界 retention | L2 | S12 | REF-03 |
| SUB-09 | Cancellation | parent/explicit/timeout/shutdown 传播、terminal CAS、反向清理与无 orphan 安全矩阵 | L2 | S12 | REF-03 |
| SUB-10 | Git Worktree | fixed-argv create/enter/keep/remove、child root 重装配、identity/registration recovery 与保守 preserve 矩阵 | L2 | S12 | REF-02 |
| SUB-11 | Team Task Board | 共享任务和消息 | L0 | S14/S15 | REF-03 |

## 20. Observability / Eval / Distribution 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| OBS-01 | Agent Event | 可重放控制流 | L1 | S01 | REF-01 |
| OBS-02 | Turn/Tool Timing | S02 事件边界采集 L2 → S14 Metrics Backend L3 | L2 | S02/S14 | REF-01/AUTH-01 |
| OBS-03 | Token / Cost | S02 可信 Provider Usage L2 → S14 Cost 治理 L3 | L2 | S02/S14 | REF-01/AUTH-01 |
| OBS-04 | Stop / Recovery Analytics | Context latest-only 内部观察；ADR-048 冻结 OFF 默认的本地封闭 ModelDiagnostic 纠错契约，尚未实现；分布聚合、OTel 与导出延期 S14 | L1 | S07/S08/S14 | REF-01 |
| OBS-05 | Privacy Controls | S02 最小化 Telemetry L2 → S14 Export Policy L3 | L2 | S02/S14 | REF-01/AUTH-01 |
| OBS-06 | OpenTelemetry | 可选 Trace Export | L0 | S14 | REF-01 |
| EVAL-01 | Seed Tasks | S04 单个公开 Scripted Java Fixture L1 → S14 任务集与指标 L3 | L1 | S04/S14 | REF-01 |
| EVAL-02 | Behavior Replay | Fake Model 确定性回放 | L2 | S01/S06 | REF-01/AUTH-01 |
| EVAL-03 | Agent Success Metrics | 完成率、成本、工具轨迹 | L0 | S14 | REF-01 |
| EVAL-04 | Security Eval | 越权与 Prompt Injection | L0 | S13 | REF-01 |
| DIST-01 | Runnable Jar | 基础发行 | L0 | S04 | REF-02 |
| DIST-02 | Windows/Linux Launcher | 跨平台脚本/安装 | L0 | S14 | REF-02 |
| DIST-03 | Java SDK | Embeddable Runtime | L0 | S14 | REF-03 |
| DIST-04 | Headless Protocol | CI/Automation | L0 | S14 | REF-02 |
| DIST-05 | Daemon/API | 多 Surface 引擎 | L0 | S14 | REF-02 |
| DIST-06 | Version/Update | 兼容和升级策略 | L0 | S14 | REF-02 |

## 21. 各 Stage 的完成定义

### S00：Harness 地图

完成条件：

- 参考架构文档；
- 功能对照矩阵；
- 公开行为基线、授权参考快照登记与历史隔离审计；
- 来源、授权、独立重实现和禁止复制规则；
- Snapshot ID、指纹、`Documented / Observed / Inferred / Unknown`；
- 术语统一。

学习输出：

- 能用自己的话画出完整 Harness；
- 能解释 Agent Loop 为什么只是整体的一小部分。

### S01：Runtime Kernel（Agent Loop）

完成条件：

- Framework-free Domain；
- Fake Model；
- Tool Definition / Call / Result；
- Tool Pipeline 骨架；
- 内存 Session；
- Stop Reason、Limits、Event；
- 多 Tool Call 协议测试；
- 19 个 L1 Feature 的场景/测试证据索引；
- 仓库声明的 Maven Wrapper 命令可复现。

S01 只保留 Cancellation 扩展缝隙，不把模型流取消或子进程取消列为已实现。

行为 Demo：

```text
Fake User
→ Fake Model Tool Call
→ Fake Permission
→ Fake Tool
→ Tool Result
→ Fake Model Final
```

### S02：Model + Streaming CLI

完成条件：

- 一个 Spring AI Provider；
- 自动 Tool Loop 关闭；
- Picocli Java Headless `--print` / `--stdio`；
- 内部 UTF-8 NDJSON v0 的顺序、唯一终态、取消和 stdout 纯净边界；
- React/Ink TUI；
- Model Text Delta；
- Tool Call Chunk 聚合、不完整流和错误转换；
- React/Ink Interactive 与 Java Print；
- 模型流 Cancel；
- 输出达到模型长度上限时识别 finish reason，并有界停止或续接；
- Usage 可用时准确记录、不可用时不伪造；
- 非 TTY 降级。

学习输出：

- 能解释同步核心为何仍可以流式；
- 能说明 Tool Call Chunk 如何聚合。

### S03：Read Tools

完成条件：

- list/search/read/git status/diff；
- search 支持完整 Grep 参数、content/files/count、类型化分页、取消、超时和一次资源不足恢复；
- WorkspaceGuard；
- AGENTS.md；
- 类型化单结果上限、明确截断/外置元数据；
- 真实仓库解释 Demo；
- Realpath、穿越、Symlink/Junction、敏感文件和 Prompt Injection 测试。

### S04：Write + Command

完成条件：

- apply patch/write；
- command；
- Approval UI；
- 固定的读允许、写/命令询问和安全 PLAN；
- timeout/cancel/process tree；
- 脏工作区识别；
- “修改 → 测试失败 → 再修改 → 成功”Demo。

S04 启动 Gate 与首个 Approval 切片见
[ADR-035](./adr/ADR-035-s04-approval-spine.md)：当前只把固定 Effect 决策表、
可取消的单次审批协议和 React/Ink Approval UI 提升到 L1。第二切片已实现真实
`apply_patch` 与 `write_file`：精确旧内容和多匹配前置条件、新文件父目录 realpath、
敏感路径/Junction/Symlink 拒绝、同目录暂存与单次 Move、提交前冲突重检、取消和
有界 Patch 摘要均有确定性测试。第三切片已实现 `run_command` 的固定 Shell/Workspace、
准确审批、最小环境、stdout/stderr Event、输出上限、timeout/cancel 与 Windows
进程树清理，并通过真实子进程和无孤儿 Marker 测试。公开 Fixture 已按 PRD 的
`divide` 任务完成“越权拒绝 → 错误实现 → 增加自测 → 测试失败 → 再修改 → 成功 →
Git Diff”的真实 Pipeline 闭环，`EVAL-01` 达到单 Seed Task 的 L1。S14 的任务集、
真实模型成功率和成本指标仍未实现。实现 Commit `16b4767` 的 Commit-scoped G0-G6
与最终退出结论见 [S04 Stage Exit 证据](./evidence/S04-stage-exit-2026-07-30.md)。

### S05：Permission Pipeline

实现 Commit `f7b7137081e2d85417fa5965835d4c014e514dac` 的 Commit-scoped G0-G6 已通过，见
[ADR-038](./adr/ADR-038-s05-authorized-permission-study.md)、
[ADR-039](./adr/ADR-039-s05-permission-pipeline.md)、
[S05 Demo](./demos/S05-permission-pipeline.md)与
[S05 Stage Evidence](./evidence/S05-permission-pipeline-2026-08-03.md)。本轮范围
`BOOT-03`、`CLI-05`、`LOOP-13`、`TOOL-03`、`PERM-01/03/04/06/07/08/09/10/11/13`、
`HOOK-01`、`SEC-09` 已达到 L2，Stage Exit 为 Accepted。`PERM-12` 分层持久来源留到
S08/S13，真实 Hook/MCP/Plugin/Sub-Agent 与 Sandbox 仍按后续 Stage 推进；下一步只启动
S06 授权研究与 Gate，不表示持久 Session 或 Checkpoint 已实现。

完成条件：

- Effect；
- Default/Plan/Accept Edits；
- allow/ask/deny；
- session approval；
- hard denial；
- permission lifecycle；
- 拒绝后 Agent 恢复；
- Fake External Tool / Tool Provider 不能绕过 Pipeline。

### S06：Session + Checkpoint

完成条件：

- JSONL Schema；
- continue/resume/fork；
- 未完成 Tool 检测；
- File Checkpoint/Undo；
- Session 往返、崩溃点和兼容测试。

稳定外部 Export、Retention 和跨版本迁移属于 S14，不是 S06 退出条件。

### S07：Context Engineering

已 Accepted。ADR-042/043/044、离线 Fake 长会话 Eval、Demo、Gap 与 Commit-scoped G0-G6 对账已证明以下退出条件；这些 L1/L2 等级不等同于 S08 的用户可见 Context UX 或 S14 的真实模型质量：

- Model-aware Token Budget 与可解释 Context Usage View；
- Canonical Transcript 保持不变，Projection 中完整 Tool Call/Result 配对；
- C1 大载荷缩减、C2 旧 Tool 输出清理、C3 滚动记忆、C4 全量摘要按压力条件选择，
  不作为固定串行四步；
- 同次 Overflow 一次有界恢复、失败不提交与 Thrashing Guard；
- M1 Storage、M2 `MEMORY.md` Index（最多 200 行或 25KB）、M3 Catalog（最多 200 topic）、
  M4 Recall、M5 Projection；
- `CTX-18` ready-only 零等待消费：未完成/失败/取消立即按空结果继续，迟到结果不注入已发送请求；
- `/context` 所需内部 Usage 投影；完整 Slash Command UX 仍归 S08；
- 长会话事实/约束保持、任务完成度、Token 降幅与预取关键路径 Eval。

S07 采用本项目独立命名、阈值和 Java 契约；历史 ADR-019 继续 Superseded。稳定 Export、Retention、
Migration、SQLite、Provider Cache/Context Editing 留到 S14；分层 Instructions/Settings 留到 S08，
Sub-Agent/后台任务留到 S12，OS Sandbox 留到 S13。

### S08：Instructions + Settings

G0 已于 2026-08-05 通过：`ADR-045` 在 `AUTH-SRC-2026-07-29-A` 的登记只读路径上完成了
分层 Instructions、Settings 合并/来源、模型/权限/Tool 设置、Slash/诊断与失败安全边界的机制研究。ADR-046/047 的历史 G1/G2 契约继续约束 Instructions、Settings 与安全所有权；[ADR-048](./adr/ADR-048-s08-corrective-composer-model-diagnostics.md) 的 corrective implementation Commit `8fabd94b66881a4a8236cccabd4ae61dd39845d4` 已完成 G0-G6 并保持既有能力 Accepted。[ADR-049](./adr/ADR-049-s08-explicit-file-mentions.md) 的 `CLI-13`/`CTX-19` 补充切片也已在实现 Commit `5910a8f` 上完成授权研究、范围、独立架构、实现、离线验证、独立 review 与 Commit-scoped G6；S08 恢复 Accepted。

完成条件：

- user/project/local；
- directory rules；
- merge semantics；
- model/permission/tool config；
- `/help`、`/clear`、`/compact`、`/context`、`/model`、`/permissions`、`/resume`；
- `/doctor` 来源诊断；
- 基础配置 Schema 与版本字段；
- 分层 Instructions 接入后的 S07 重注入和 Usage 对账回归；
- ADR-048 的 grapheme-safe Composer、视觉导航/viewport、无损大 Paste 与 OFF/SAFE/VERBOSE 本地 ModelDiagnostic；
- ADR-049 的 Java 权威 `@path` 补全、Workspace 安全附件、Canonical/Session 快照与不可信 Context Projection；
- 新实现 Commit 上的完整 G3-G6、真实 TTY Demo、隐私 sentinel 与 commit-scoped 对账。

跨版本配置迁移兼容、OTel 与诊断导出属于 S14。

### S09：Hooks

完成条件：

- 在内部 Lifecycle Event 之上建立独立 Hook 协议；
- pre/post tool；
- session/run/compact；
- matcher；
- timeout；
- blocking/non-blocking；
- command/HTTP Hook 安全测试。

S07 的 Compaction Event 只是观察事件；可配置、可阻断的 Compact Hook 从 S09 开始。

ADR-051～055 已完成授权研究、独立协议、固定 argv Command、严格 Settings/Trust、生产装配、
Pre/Post Compact、下一回合 Context Projection 和 loopback HTTP。G0-G6 Accepted；远程 HTTP、
Prompt/Agent/Sub-Agent Hook、稳定 stdio/TUI 活动协议与 OS Sandbox 保持后续差距。

### S10：MCP

完成条件：

- STDIO 与一个远程 Transport；
- multiple server；
- tool filter/name prefix；
- Permission Pipeline；
- auth/trust；
- MCP 故障恢复。

ADR-056/057 已完成授权/公开协议研究与独立 Java Adapter。STDIO、Streamable HTTP、多 Server、
Tool filter/prefix、统一 Permission/Approval/Pipeline、project Trust 和单次断线恢复已通过真实 Transport
及 Headless E2E，S10 G0-G6 Accepted。Resource/Prompt 仅元数据、Bearer 仅环境认证，均为 L1；
Lazy Tool Loading 保持 L0，OAuth 与 OS 网络隔离分别留到 S13，稳定协议留到 S14。

### S11：Skills + Plugins

ADR-058～060 继续约束双源研究、范围与独立架构；实现 Commit
`71278431dd1e5c7c4e279b44f43e084755502a5d` 上的 G0-G6、量化、Demo、Review 修正与能力对账均已验证，
Stage Exit Accepted。当前结果与边界：

- `SKILL-01..07`、`CTX-14` 达到 L2：metadata-first、markdown/lazy load、explicit/model invocation、resource、`runtimeVisibleTools ∩ skillAllowedTools` 纯收窄且每次调用重新执行 Permission/Approval、禁止 nested/reentrant、每 Run 每 Skill 至多一次、Hook/Scope 持续到 Run 唯一终态、无活动 Run 的 Resume 不恢复 Scope、Session digest/recovery；
- `PLUGIN-01..03` 达到 L2：strict manifest/namespace、immutable snapshot、返回 Contribution 的 host-side Tool Provider SPI、named MCP Server 引用、可信 PLUGIN Network Tool 的受控 ASK 入口与关闭所有权；
- `PLUGIN-04` 在 S11 只达到 L1：本地 staged install 与 quiescing uninstall；S14 再达到 L2；
- `PLUGIN-05/06`、`SEC-11`、`MCP-08`、`TOOL-16` 保持 L0；fingerprint/catalog 不冒充签名、市场、Sandbox 或 Lazy Tool；
- 明确拒绝任意 JAR/Class/ServiceLoader/native/script Tool Provider；
- 实际 Plugin Adapter 经过 Permission/Approval/Hook/Pipeline，旁路执行次数为 0；
- G4 证明 metadata 启动读取降低至少 90%、权限/协议/租约/隐私违规为 0，并覆盖恶意资源、恢复错配、安装故障点和活动引用卸载；
- G5 在实现 Commit 上记录 67/67 Demo，G6 已对账 Evidence/Demo/Gap/看板；Maven 813 tests/21 skips、TUI 129/129、launcher 59/59 与 Dashboard 均通过。
- S12 已在实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 上完成 Commit-scoped G0-G6 与 Stage Exit，达到冻结的 L2/L1 目标。

### S12：Sub-Agent + Worktree

ADR-061/062 在双源边界内冻结范围与架构；实现 Commit `cfbe0282b37a93e38256c3d2d6f22ed2207975a5` 已完成 Batch A-C、确定性测试、真实 Git Worktree Demo、六 seed Eval 与 commit-scoped G0-G6 对账，Stage Exit Accepted。标准 clean verify 838 tests/21 skips、TUI 133/133、launcher 59 assertions 与 Dashboard 均通过；首轮 Settings Git `CreateProcess error=5` 由同一 Commit 上 focused 1/1 及后续连续完整 clean verify 全绿取代，不计入通过证据。

完成条件：

- `SUB-01..05/07..10`、`CTX-15`、`HOOK-08`、`TOOL-15` 达到 L2；`SUB-06`、`HOOK-11` 达到 L1；
- Runtime Scope 复用同一 `AgentRuntime`，独立 Context/Tool/Permission/Budget/Session mutable state；
- 父子任务只返回有界摘要，前台/后台共享唯一状态与恢复 Gate；
- Session 级公平有界并发、父预算原子 reservation、cancel/shutdown 无 orphan；
- TOOL-15 只并行宿主白名单只读 Tool，结果按原批次顺序/Call ID 归并；
- Git Worktree create/keep/remove、独立 root 重装配和 dirty/new commit 保守 preserve；
- 至少 6 个 Seed Task 的单/多 Agent Eval，完成率不降，安全违规为 0，墙钟或 Token 中位数改善 ≥20%。

实施冻结为三个完整 Batch：A `Scope + single delegate` → B `bounded concurrency + background + TOOL-15` → C `Git Worktree + integrated Eval`。`SUB-11` Team Board、远程/跨重启 worker、稳定 task protocol、模型 Prompt/Agent Hook 与 S13 OS Sandbox 明确延期。Worktree ancestor reparse、Git fault/timeout、Windows remove/branch-lock cancellation recovery 缺少可移植自动故障注入，继续作为明确 gap，不影响已验证范围的 S12 Accepted。

### S13：Sandbox + Security

ADR-063/064 已完成双源 G0-G2，并冻结以下 Current→Exit Target；当前未写生产/测试实现，全部 Capability Level 保持原值，G3-G6 与 Stage Exit Open：

- `SEC-02/03/04/05`：L1→L2；`SEC-06/07/12`、`EVAL-04`：L0→L2；
- `SEC-08`、`PERM-05`、`CFG-07`：L0→L1；`HOOK-10` 保持 L1；
- `PERM-08`、`PERM-09`、`PERM-12`、`SEC-09` 各自保持 L2 并做组合回归；`SEC-11` 保持 L0。

完成条件：

- `ExecutionBackend`、Local、Windows-hosted WSL2 Linux bwrap 与可选 Docker Container；
- 实际 capability probe：WSL version+bwrap self-test、Docker daemon+pinned image、native platform 维度；CLI/OS 名不算强制成功；
- file/process/network/environment/secret policy 与 Managed deny-only baseline；进程 backend 明确不能约束 JVM 内 HTTP，`HOOK-10` 因此保持 L1；
- fail-closed 选择和执行前、当前 Call ID 的显式单次 Local fallback；
- Command、Sub-Agent、Plugin/MCP stdio、Command Hook 进入一致 backend seam，同时保留唯一 Pipeline；
- Windows fixed-drive 到 Linux path 双向 identity 与显式 `LINUX_SH`，禁止隐式转换 PowerShell/cmd；
- attack fixture、安全矩阵与 A/B/C/U 证据；最低为 WSL2+bwrap Linux A、Docker Container B、native Windows B（file/network C/U）、macOS C/U。

实施最多三个完整 Batch：A `Contracts + Local refactor + truthful probe` → B `WSL2 Ubuntu + bwrap Linux A/path identity/LINUX_SH` → C `Docker B + attack matrix + native Windows/macOS诚实分级 + G4-G6`。所有新增/修改核心公共契约必须提供准确中文 Javadoc。Permission、Checkpoint、Worktree、Job cleanup、最小环境和 Local backend 均不等于 Sandbox。

### S14：Production Harness

完成条件：

- `Model Runtime`：第二 Provider、Capability Detection、限流/重试、Fallback、
  Cache Hint 与原生 Context Editing 对照；
- `Eval/Observability`：统一早期 Stage 专项 Eval、OTel、隐私和恢复指标；
- `SDK/Headless`：stable JSON/JSONL、Java SDK、Headless/Daemon 和 model fallback；
- `Distribution/Compatibility`：Export/Retention、Schema migration、release/install/update
  和 compatibility policy。

### S15：Independent Innovation

前置条件：

- S01-S14 均已完成并留下差距报告；
- 矩阵内所有未豁免能力至少达到 L2；
- Core、Loop、Tool、Permission、Context、Session 的关键能力达到 L3；
- 其余尚未达到 L3 的项目都有明确 `Accepted Deviation` 或补齐计划；
- 有可重复 Eval 基线。

创新候选：

- Java/Spring 结构化代码智能；
- Maven/Gradle Test Selection；
- JVM Sandbox；
- 强类型 Tool Schema；
- 可重放 Runtime；
- Agent 执行可视化；
- 企业内部 Tool Permission Governance。

## 22. 每次迭代如何选择下一项

每次开发前按以下顺序：

1. G0：确认公开行为基线、来源权利边界、版本/指纹、结论置信度和 Unknown；
2. G1：找到当前 Stage，选择 Feature，写出 `Current → Exit Target` 和可证伪行为；
3. G2：研究职责、状态机、不变量和失败恢复，通过 ADR 定义独立 Java 边界；
4. G3：先写 Fake/Fixture，再完成当前 Stage 的最小实现；
5. G4：运行正常、边界、失败、恢复、安全和量化验证；
6. G5：运行具有实际结果和负例的可复现 Demo；
7. G6：更新等级、证据链接、能力声明和差距报告；
8. 记录下一项阻塞能力。

禁止仅因为“看起来酷”跳去做后期能力，而不记录跨阶段原因。
完整未通过条件见 [Stage 证据包模板](./templates/stage-evidence-package.md)。

## 23. 每个版本的差距报告模板

```text
Release:
Reference Baseline:
Authorized Snapshot ID: <ID | N/A - Not Used>
Current Learning Stage:
Stage Status:
Commit / Environment:

Capabilities advanced:
- FEATURE-ID: Lx → Ly
  - Reference behavior:
  - Documented / Observed / Inferred / Unknown:
  - Test / Eval evidence:

Behavior tests:
- command
- passed / total
- failure / recovery / security cases

Metrics:
- before / after
- threshold / result

Reliability:
- loop failures
- permission violations
- cancellation leaks
- session corruptions

What the reference still does better:
1.
2.
3.

What cc-java now does differently:
1.

Accepted Deviations:
1.

Next three capabilities:
1.
2.
3.

Exit blockers:
1.
```

这个报告随每个版本提交，确保项目始终知道“现在在哪、差什么、为什么做下一步”。
