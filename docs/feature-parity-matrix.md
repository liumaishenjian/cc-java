# cc-java 功能对照矩阵与学习路线

> 文档状态：Active Baseline
>
> 参考版本：R2026.03
>
> 最后更新：2026-07-27
>
> 当前代码状态：尚未开始实现

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

| 编号 | 来源 |
| --- | --- |
| REF-01 | [Harness Engineering 架构分析](https://qingkeai.online/archives/Claude%20Code) |
| REF-02 | [Claude Code 官方工作原理](https://code.claude.com/docs/en/how-claude-code-works) |
| REF-03 | [Claude Code 官方扩展能力](https://code.claude.com/docs/en/features-overview) |
| REF-04 | [Claude Code 官方权限模式](https://code.claude.com/docs/en/permission-modes) |
| REF-05 | [Claude Code 官方项目指令与记忆](https://code.claude.com/docs/en/memory) |
| REF-06 | [Claude Code 官方 Session](https://code.claude.com/docs/en/sessions) |
| REF-07 | [Claude Code 官方 Hooks](https://code.claude.com/docs/en/hooks) |
| REF-08 | [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) |

参考产品以后增加的功能不会自动进入本基线。需要升级基线时，新建 R 版本并记录新增、删除和行为变化。

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
| S00 | Harness 地图 | 参考架构、矩阵、术语、clean-room 规则 |
| S01 | Agent Loop | Fake Model 驱动的显式循环 |
| S02 | Model + Streaming CLI | 一个真实 Provider、REPL、事件流 |
| S03 | Read Tools | 在真实仓库自主搜索和解释代码 |
| S04 | Write + Command | 经审批修改代码、执行测试、根据结果继续 |
| S05 | Permission Pipeline | 模式、规则、审批、生命周期和拒绝恢复 |
| S06 | Session + Checkpoint | JSONL、resume/fork、崩溃检测、undo |
| S07 | Context Engineering | Token 预算、淘汰、摘要、压缩和防抖 |
| S08 | Instructions + Settings | 用户/项目/目录指令和配置层级 |
| S09 | Hooks | 可配置生命周期扩展与阻断 |
| S10 | MCP | 外部 Server、Tool 过滤、权限统一 |
| S11 | Skills + Plugins | 懒加载工作流、扩展打包和信任 |
| S12 | Sub-Agent + Worktree | 上下文隔离、委托、并发和代码隔离 |
| S13 | Sandbox + Security | Process/File/Network 隔离和攻击测试 |
| S14 | Production Harness | Eval、Observability、SDK、Headless、发行 |
| S15 | Independent Innovation | 基于对照结果实现 Java 差异化 |

Stage 是学习顺序，不是要求等到上一阶段 100% 成熟才能开始下一阶段。每次只允许少量跨阶段工作，并在矩阵中说明原因。

### 5.1 当前差距快照

| 指标 | R2026.03 当前值 |
| --- | --- |
| 纳入追踪的 Capability ID | 193 |
| 当前阶段 | S01 Runtime Kernel（L1 学习骨架） |
| 当前等级 | 19 项为 L1，174 项为 L0 |
| 默认最终目标 | 193 项达到 L3，或存在明确 `Accepted Deviation` |
| 当前能力覆盖 | 3.28%（193 项等权、目标 L3） |
| 下一阶段 | S02 Model + Streaming CLI |

每次新增、合并或排除 Capability ID 时必须同步更新这张快照。

S01 等级证据见 [Runtime Kernel ADR](./adr/ADR-017-s01-runtime-kernel.md)、
[离线 Agent Loop Demo](./demos/S01-agent-loop.md)和
[S01 差距报告](./gap-reports/S01.md)。本阶段统一只记为 L1，不把离线 Fake
回放宣传为真实任务可用的 L2。

## 6. Scaffolding / Bootstrap 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| BOOT-01 | CLI 启动并识别运行模式 | Picocli Interactive / Print | L0 | S02 | REF-02 |
| BOOT-02 | 确定 Workspace 与 Git 状态 | `WorkspaceSnapshot` | L0 | S03 | REF-02 |
| BOOT-03 | 加载模型、工具和权限 | `SessionBootstrapper` | L0 | S02/S05 | REF-01 |
| BOOT-04 | 加载项目指令 | `InstructionLoader` | L0 | S03/S08 | REF-05 |
| BOOT-05 | 创建 Session 和初始 Context | `SessionStore` + `ContextAssembler` | L1 | S01 | REF-02 |
| BOOT-06 | 启动诊断 | `/doctor` 与配置来源报告 | L0 | S08/S14 | REF-02 |
| BOOT-07 | 延迟加载高成本能力 | Lazy Tool/Skill/MCP Metadata | L0 | S07/S10/S11 | REF-01/03 |

## 7. Terminal / Interface 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CLI-01 | Interactive Session | JLine REPL | L0 | S02 | REF-02 |
| CLI-02 | Print / Headless | Picocli `--print` | L0 | S02 | REF-02 |
| CLI-03 | 流式 Assistant Text | `ModelTextDelta` 渲染 | L0 | S02 | REF-02 |
| CLI-04 | Tool 进度与输出 | 有序 Agent Event 渲染 | L0 | S02/S03 | REF-02 |
| CLI-05 | Permission Prompt | 终端 Approval UI | L0 | S04/S05 | REF-04 |
| CLI-06 | Ctrl+C Cancel | 当前 Run/Tool 取消 | L0 | S02/S04 | REF-02 |
| CLI-07 | Steering | 运行中排队用户补充消息 | L0 | S06 | REF-02 |
| CLI-08 | Slash Commands | help/new/clear/context/model/permissions | L0 | S06/S08 | REF-02 |
| CLI-09 | 多行、历史、补全 | JLine 能力 | L0 | S06 | REF-02 |
| CLI-10 | TTY / Non-TTY 降级 | 无 ANSI 输出与管道模式 | L0 | S02/S14 | REF-02 |
| CLI-11 | 机器输出协议 | 版本化 JSON/JSONL | L0 | S14 | REF-02 |
| CLI-12 | 多 Surface 共用引擎 | CLI/SDK/API 共用 Runtime | L0 | S14 | REF-02 |

## 8. Agent Loop 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| LOOP-01 | 显式循环状态 | `AgentRunState` | L1 | S01 | REF-01 |
| LOOP-02 | User → Model → Tool → Model | User-controlled Loop | L1 | S01 | REF-01/08 |
| LOOP-03 | 单回合多个 Tool Call | 保持消息和 Call ID 协议 | L1 | S01 | REF-08 |
| LOOP-04 | 流式 Model Turn | Delta + 聚合结果 | L0 | S02 | REF-02/08 |
| LOOP-05 | Tool Result 驱动下一回合 | Canonical Message History | L1 | S01 | REF-08 |
| LOOP-06 | 多 Continue 原因 | 显式 Transition/Stop Reason | L1 | S01/S07 | REF-01 |
| LOOP-07 | 最大回合和工具数 | `AgentLimits` | L1 | S01 | REF-01 |
| LOOP-08 | Deadline 与取消 | Cancellation 传播 | L0 | S02/S04 | REF-02 |
| LOOP-09 | 模型错误重试 | 有界 Retry Policy | L0 | S02/S14 | REF-01 |
| LOOP-10 | 输出长度恢复 | 明确停止或降级 | L0 | S07/S14 | REF-01 |
| LOOP-11 | Context 溢出恢复 | Compact / Stop / Retry | L0 | S07 | REF-01/02 |
| LOOP-12 | Model Fallback | Provider-aware Fallback | L0 | S14 | REF-01 |
| LOOP-13 | 用户拒绝后继续推理 | Denied Tool Result 回传模型 | L0 | S05 | REF-04 |
| LOOP-14 | 崩溃和未完成 Tool | Session Recovery Gate | L0 | S06 | REF-06 |

## 9. Model Runtime 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| MODEL-01 | Model Gateway | Provider-neutral Port | L1 | S01 | REF-08 |
| MODEL-02 | 一个真实 Provider | Spring AI Adapter | L0 | S02 | REF-08 |
| MODEL-03 | Scripted Fake Model | 确定性测试 | L1 | S01 | REF-08 |
| MODEL-04 | Text Streaming | Adapter 内消费 Flux | L0 | S02 | REF-08 |
| MODEL-05 | Tool Call Streaming | Chunk 聚合 | L0 | S02 | REF-08 |
| MODEL-06 | Usage / Finish Reason | 规范化 Capability | L0 | S02 | REF-08 |
| MODEL-07 | 第二 Provider | 验证抽象边界 | L0 | S08/S14 | REF-02 |
| MODEL-08 | Model Switching | Session Command | L0 | S08 | REF-02 |
| MODEL-09 | Prompt Cache | 稳定前缀和 Provider Hint | L0 | S07/S14 | REF-01 |
| MODEL-10 | Rate Limit / Retry | Provider Error Policy | L0 | S14 | REF-01 |
| MODEL-11 | Cost Budget | Token 与价格模型 | L0 | S14 | REF-01 |
| MODEL-12 | Capability Detection | Tools/Streaming/Context/Reasoning | L0 | S14 | REF-02 |

## 10. Tool System 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| TOOL-01 | Tool Definition + Schema | Framework-free Contract | L1 | S01 | REF-08 |
| TOOL-02 | Tool Registry | Source-aware Registry | L1 | S01 | REF-01 |
| TOOL-03 | 统一执行 Pipeline | Validate → Permit → Execute → Normalize | L1 | S01/S05 | REF-01/07 |
| TOOL-04 | List / Glob | Workspace 文件枚举 | L0 | S03 | REF-02 |
| TOOL-05 | Grep / Search | 文本和行号搜索 | L0 | S03 | REF-02 |
| TOOL-06 | Read File | 分段、行号、大小限制 | L0 | S03 | REF-02 |
| TOOL-07 | Git Status / Diff | 脏工作区和证据 | L0 | S03/S04 | REF-02 |
| TOOL-08 | Apply Patch / Edit | 原子文本修改 | L0 | S04 | REF-02 |
| TOOL-09 | Write / Create | 新文件写入 | L0 | S04 | REF-02 |
| TOOL-10 | Run Command | Shell、timeout、exit code | L0 | S04 | REF-02 |
| TOOL-11 | Tool Output Streaming | stdout/stderr Event | L0 | S04 | REF-02 |
| TOOL-12 | Result Truncation | 显式截断和摘要 | L0 | S03/S07 | REF-01 |
| TOOL-13 | Structured Tool Error | 模型可纠正错误 | L1 | S01/S03 | REF-01 |
| TOOL-14 | Tool Cancellation | Model/Process/File 取消 | L0 | S04 | REF-02 |
| TOOL-15 | 并行安全工具 | Read-only 并行执行 | L0 | S12 | REF-01 |
| TOOL-16 | Tool Search / Lazy Schema | 大工具集按需加载 | L0 | S10/S11 | REF-02/03 |
| TOOL-17 | Code Intelligence | LSP / Symbol Tool | L0 | S14/S15 | REF-02/03 |
| TOOL-18 | Web Tool | 可控网络检索 | L0 | S14 | REF-02 |

## 11. Permission 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| PERM-01 | Tool Effect 分类 | Read/Write/Process/Network/System | L0 | S04/S05 | REF-04 |
| PERM-02 | Manual / Default | 副作用默认询问 | L0 | S04 | REF-04 |
| PERM-03 | Plan Mode | 禁止修改的工具集 | L0 | S05 | REF-04 |
| PERM-04 | Accept Edits | Workspace Write 自动批准 | L0 | S05 | REF-04 |
| PERM-05 | Auto Mode | 独立安全决策器 | L0 | S13/S15 | REF-04 |
| PERM-06 | Allow / Ask / Deny | 声明性规则 | L0 | S05/S08 | REF-04 |
| PERM-07 | Allow Once / Session | 范围化审批缓存 | L0 | S04/S05 | REF-04 |
| PERM-08 | Protected Paths | 不可写路径 | L0 | S05/S13 | REF-04 |
| PERM-09 | Hard Denial | 不可被项目配置覆盖 | L0 | S05/S13 | REF-01/04 |
| PERM-10 | Permission Event | 可观察与 Hook | L0 | S05/S09 | REF-07 |
| PERM-11 | Denial Tracking | 重复拒绝降级 | L0 | S05/S14 | REF-01/04 |
| PERM-12 | Project/User/Managed Scope | 分层策略 | L0 | S08/S13 | REF-04 |
| PERM-13 | Print Mode Policy | 无交互时确定性处理 ASK | L0 | S05 | REF-04 |

## 12. Lifecycle / Hooks 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| HOOK-01 | 内部 Lifecycle Event | Session/Run/Model/Tool/Permission | L1 | S01/S05 | REF-07 |
| HOOK-02 | Pre Tool | 执行前观察和阻断 | L0 | S05/S09 | REF-07 |
| HOOK-03 | Post Tool | 结果观察和附加 Context | L0 | S05/S09 | REF-07 |
| HOOK-04 | Session Start/End | 环境和清理扩展 | L0 | S09 | REF-07 |
| HOOK-05 | User Prompt / Run | 输入处理 | L0 | S09 | REF-07 |
| HOOK-06 | Permission Request | 自定义审批逻辑 | L0 | S09 | REF-07 |
| HOOK-07 | Compact Hooks | 压缩前后扩展 | L0 | S09 | REF-07 |
| HOOK-08 | Sub-Agent Hooks | 子任务生命周期 | L0 | S12 | REF-07 |
| HOOK-09 | Command Hook | JSON stdin/stdout + Exit Policy | L0 | S09 | REF-07 |
| HOOK-10 | HTTP Hook | 受控远程回调 | L0 | S09/S13 | REF-07 |
| HOOK-11 | Prompt / Agent Hook | 模型参与决策 | L0 | S12/S15 | REF-07 |
| HOOK-12 | Matcher / Scope | Tool、路径、Session 条件 | L0 | S09 | REF-07 |
| HOOK-13 | Timeout / Error Policy | 阻断与非阻断错误 | L0 | S09 | REF-07 |

## 13. Sandbox / Security 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SEC-01 | Workspace Realpath | 路径与新文件父目录校验 | L0 | S03/S04 | REF-02 |
| SEC-02 | Symlink / Junction | 跨平台逃逸测试 | L0 | S03/S13 | REF-02 |
| SEC-03 | Sensitive Files | 默认拒绝和配置 | L0 | S03/S13 | REF-04 |
| SEC-04 | Process Tree Control | timeout/cancel/cleanup | L0 | S04/S13 | REF-02 |
| SEC-05 | Environment Filtering | 最小环境变量 | L0 | S04/S13 | REF-01 |
| SEC-06 | File Sandbox | Workspace 级 OS 隔离 | L0 | S13 | REF-01 |
| SEC-07 | Network Sandbox | Domain/Port Policy | L0 | S13 | REF-01 |
| SEC-08 | Container Backend | 隔离执行环境 | L0 | S13 | REF-01 |
| SEC-09 | Prompt Injection Defense | 不可信仓库测试 | L0 | S05/S13 | REF-02 |
| SEC-10 | Secret Redaction | Event/Log/Tool Result | L0 | S03/S14 | REF-01 |
| SEC-11 | Plugin Supply Chain | 签名、信任和隔离 | L0 | S11/S13 | REF-03 |
| SEC-12 | Security Regression Suite | 攻击性 Fixture | L0 | S13 | REF-01 |

## 14. Context Engineering 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CTX-01 | System Context Assembly | 稳定策略 + Runtime Metadata | L1 | S01/S02 | REF-02 |
| CTX-02 | Project Instructions | 根 AGENTS.md | L0 | S03 | REF-05 |
| CTX-03 | Hierarchical Instructions | User/Project/Directory | L0 | S08 | REF-05 |
| CTX-04 | Path-scoped Rules | 只在相关文件加载 | L0 | S08 | REF-05 |
| CTX-05 | Tool Result Limits | 类型化裁剪 | L0 | S03/S04 | REF-01 |
| CTX-06 | Token Budget | Model-aware 预算 | L0 | S07 | REF-01/02 |
| CTX-07 | Complete Turn Eviction | 保持 Tool 协议成对 | L0 | S07 | REF-01 |
| CTX-08 | Old Tool Output Clear | 优先释放低价值输出 | L0 | S07 | REF-02 |
| CTX-09 | Conversation Summary | LLM 压缩 | L0 | S07 | REF-01/02 |
| CTX-10 | Multi-level Compaction | 渐进压缩 | L0 | S07 | REF-01 |
| CTX-11 | Thrashing Guard | 压缩失败防循环 | L0 | S07 | REF-02 |
| CTX-12 | Compact Instructions | 用户控制保留内容 | L0 | S07/S08 | REF-02 |
| CTX-13 | `/context` | Context 构成可视化 | L0 | S07 | REF-02 |
| CTX-14 | Skill Lazy Loading | Metadata 先加载 | L0 | S11 | REF-03 |
| CTX-15 | Sub-Agent Isolation | 独立窗口与摘要返回 | L0 | S12 | REF-02/03 |
| CTX-16 | Prompt Cache | 稳定前缀和 Tool 顺序 | L0 | S14 | REF-01 |

## 15. Settings / Configuration 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| CFG-01 | CLI Overrides | Picocli 参数 | L0 | S02 | REF-02 |
| CFG-02 | Environment | Provider/Runtime 环境变量 | L0 | S02 | REF-02 |
| CFG-03 | User Settings | `~/.cc-java/` | L0 | S08 | REF-01 |
| CFG-04 | Project Settings | 版本控制配置 | L0 | S08 | REF-01 |
| CFG-05 | Local Settings | Gitignored 本地覆盖 | L0 | S08 | REF-01 |
| CFG-06 | Session Overrides | Slash Command 临时设置 | L0 | S08 | REF-01 |
| CFG-07 | Managed Policy | 不可覆盖组织策略 | L0 | S13/S14 | REF-01 |
| CFG-08 | Merge Semantics | Scalar/Object/List 明确规则 | L0 | S08 | REF-01 |
| CFG-09 | Config Diagnostics | 来源与最终值 | L0 | S08 | REF-01 |
| CFG-10 | Migration | Schema Version 与升级 | L0 | S14 | REF-01 |
| CFG-11 | Feature Gates | 实验能力开关 | L0 | S14 | REF-01 |

## 16. Session / Checkpoint 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SESSION-01 | Session ID | Workspace-aware ID | L1 | S01 | REF-06 |
| SESSION-02 | In-memory Session | 当前对话连续性 | L1 | S01/S02 | REF-06 |
| SESSION-03 | Append-only JSONL | 版本化事件存储 | L0 | S06 | REF-06 |
| SESSION-04 | Continue | 最近 Session | L0 | S06 | REF-06 |
| SESSION-05 | Resume | 选择 Session | L0 | S06 | REF-06 |
| SESSION-06 | Fork | 新 ID 复制历史 | L0 | S06 | REF-06 |
| SESSION-07 | Session Metadata | Model/Workspace/Config/Usage | L0 | S06 | REF-06 |
| SESSION-08 | Concurrent Open Detection | 锁与只读恢复 | L0 | S06/S14 | REF-06 |
| SESSION-09 | Incomplete Tool Recovery | 不自动重放副作用 | L0 | S06 | REF-06 |
| SESSION-10 | File Checkpoint | 写入前快照 | L0 | S06 | REF-02 |
| SESSION-11 | Rewind / Undo | 恢复 Agent 文件修改 | L0 | S06 | REF-02 |
| SESSION-12 | Export | 稳定外部格式 | L0 | S14 | REF-06 |
| SESSION-13 | Retention / Clear | 生命周期管理 | L0 | S14 | REF-06 |
| SESSION-14 | SQLite Adapter | 大量 Session 索引 | L0 | S14 | REF-06 |

## 17. MCP 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| MCP-01 | STDIO Client | Spring AI 同步 MCP Client | L0 | S10 | REF-03 |
| MCP-02 | Streamable HTTP | 远程 Server | L0 | S10 | REF-03 |
| MCP-03 | Multi Server | 生命周期与隔离 | L0 | S10 | REF-03 |
| MCP-04 | Tool Mapping | MCP → Tool Registry | L0 | S10 | REF-03 |
| MCP-05 | Permission Integration | 同一 Pipeline | L0 | S10 | REF-03 |
| MCP-06 | Tool Filtering | Allowlist / Denylist | L0 | S10 | REF-03 |
| MCP-07 | Name Collision | Server 前缀 | L0 | S10 | REF-03 |
| MCP-08 | Lazy Tool Loading | Tool Search | L0 | S10/S11 | REF-02/03 |
| MCP-09 | Resources / Prompts | Context Source | L0 | S10 | REF-03 |
| MCP-10 | Authentication | OAuth/API Key | L0 | S10/S13 | REF-03 |
| MCP-11 | Trust UX | Server 来源和风险提示 | L0 | S10/S13 | REF-03 |

## 18. Skills / Plugins 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SKILL-01 | Skill Metadata | 名称、描述、触发方式 | L0 | S11 | REF-03 |
| SKILL-02 | Markdown Workflow | 可重用步骤与知识 | L0 | S11 | REF-03 |
| SKILL-03 | Explicit Invocation | `/skill-name` | L0 | S11 | REF-03 |
| SKILL-04 | Model Invocation | 描述匹配和安全控制 | L0 | S11 | REF-03 |
| SKILL-05 | Lazy Content | 描述先加载、正文按需 | L0 | S11 | REF-03 |
| SKILL-06 | Bundled Resources | 参考文档、模板、脚本 | L0 | S11 | REF-03 |
| SKILL-07 | Scoped Hooks | Skill 生命周期 | L0 | S11 | REF-03/07 |
| PLUGIN-01 | Plugin Manifest | 版本、命名空间、组件 | L0 | S11 | REF-03 |
| PLUGIN-02 | Bundle Skills/Hooks/MCP | 扩展打包 | L0 | S11 | REF-03 |
| PLUGIN-03 | Tool Provider SPI | Java 扩展接口 | L0 | S11 | REF-03 |
| PLUGIN-04 | Install / Uninstall | 本地插件管理 | L0 | S11/S14 | REF-03 |
| PLUGIN-05 | Trust / Signature | 供应链控制 | L0 | S13/S14 | REF-03 |
| PLUGIN-06 | Marketplace | 发现和分发 | L0 | S14/S15 | REF-03 |

## 19. Sub-Agent / Worktree 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| SUB-01 | Agent Definition | Prompt/Model/Tools/Permission | L0 | S12 | REF-03 |
| SUB-02 | Runtime Reuse | 相同 AgentRuntime + Scope | L0 | S12 | REF-03 |
| SUB-03 | Isolated Context | 独立 Session 分支 | L0 | S12 | REF-02/03 |
| SUB-04 | Parent/Child Task | 委托与结果摘要 | L0 | S12 | REF-03 |
| SUB-05 | Tool-restricted Agent | Research/Plan 专用 Agent | L0 | S12 | REF-03 |
| SUB-06 | Model/Budget Override | 子 Agent 独立预算 | L0 | S12 | REF-03 |
| SUB-07 | Concurrent Agents | 有界并行 | L0 | S12 | REF-03 |
| SUB-08 | Background Agent | 任务状态与唤醒 | L0 | S12 | REF-03 |
| SUB-09 | Cancellation | 父子传播 | L0 | S12 | REF-03 |
| SUB-10 | Git Worktree | 写任务目录隔离 | L0 | S12 | REF-02 |
| SUB-11 | Team Task Board | 共享任务和消息 | L0 | S14/S15 | REF-03 |

## 20. Observability / Eval / Distribution 对照

| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |
| --- | --- | --- | --- | --- | --- |
| OBS-01 | Agent Event | 可重放控制流 | L1 | S01 | REF-01 |
| OBS-02 | Turn/Tool Timing | Micrometer Metrics | L0 | S02/S14 | REF-01 |
| OBS-03 | Token / Cost | Provider Usage 统计 | L0 | S02/S14 | REF-01 |
| OBS-04 | Stop / Recovery Analytics | 失败原因分布 | L0 | S07/S14 | REF-01 |
| OBS-05 | Privacy Controls | Prompt/Content 默认关闭 | L0 | S02/S14 | REF-01 |
| OBS-06 | OpenTelemetry | 可选 Trace Export | L0 | S14 | REF-01 |
| EVAL-01 | Seed Tasks | Java Fixture 任务集 | L0 | S04/S14 | REF-01 |
| EVAL-02 | Behavior Replay | Fake Model 确定性回放 | L1 | S01/S06 | REF-01 |
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
- clean-room 规则；
- 参考基线版本；
- 术语统一。

学习输出：

- 能用自己的话画出完整 Harness；
- 能解释 Agent Loop 为什么只是整体的一小部分。

### S01：Agent Loop

完成条件：

- Framework-free Domain；
- Fake Model；
- Tool Definition / Call / Result；
- Tool Pipeline 骨架；
- 内存 Session；
- Stop Reason、Limits、Event；
- 多 Tool Call 协议测试。

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
- Picocli + JLine；
- Model Text Delta；
- Interactive 与 Print；
- Cancel；
- 非 TTY 降级。

学习输出：

- 能解释同步核心为何仍可以流式；
- 能说明 Tool Call Chunk 如何聚合。

### S03：Read Tools

完成条件：

- list/search/read/git status/diff；
- WorkspaceGuard；
- AGENTS.md；
- 结果裁剪；
- 真实仓库解释 Demo；
- 越界与 Prompt Injection 测试。

### S04：Write + Command

完成条件：

- apply patch/write；
- command；
- Approval UI；
- timeout/cancel/process tree；
- 脏工作区识别；
- “修改 → 测试失败 → 再修改 → 成功”Demo。

### S05：Permission Pipeline

完成条件：

- Effect；
- Default/Plan/Accept Edits；
- allow/ask/deny；
- session approval；
- hard denial；
- permission lifecycle；
- 拒绝后 Agent 恢复。

### S06：Session + Checkpoint

完成条件：

- JSONL Schema；
- resume/fork/export；
- 未完成 Tool 检测；
- File Checkpoint/Undo；
- Session 兼容测试。

### S07：Context Engineering

完成条件：

- Token Budget；
- 完整 Turn 淘汰；
- 旧 Tool Output 清理；
- 摘要与压缩；
- Thrashing Guard；
- `/context`；
- 长会话 Eval。

### S08：Instructions + Settings

完成条件：

- user/project/local；
- directory rules；
- merge semantics；
- model/permission/tool config；
- `/doctor` 来源诊断；
- schema migration。

### S09：Hooks

完成条件：

- 生命周期事件公开协议；
- pre/post tool；
- session/run/compact；
- matcher；
- timeout；
- blocking/non-blocking；
- command/HTTP Hook 安全测试。

### S10：MCP

完成条件：

- STDIO 与一个远程 Transport；
- multiple server；
- tool filter/name prefix；
- Permission Pipeline；
- auth/trust；
- MCP 故障恢复。

### S11：Skills + Plugins

完成条件：

- Skill metadata/markdown/lazy load；
- explicit/model invocation；
- resource 和 scoped hook；
- plugin manifest/namespace；
- Tool Provider SPI；
- trust 和卸载。

### S12：Sub-Agent + Worktree

完成条件：

- Runtime Scope；
- 独立 Context/Tool/Permission/Budget；
- 父子任务与摘要；
- 有界并行；
- cancel；
- Worktree；
- 多 Agent Eval。

### S13：Sandbox + Security

完成条件：

- ExecutionBackend；
- Local 与 Sandbox；
- file/process/network policy；
- secret handling；
- attack fixture；
- security regression。

### S14：Production Harness

完成条件：

- Eval Dashboard；
- OTel；
- stable JSON/JSONL；
- Java SDK；
- Headless/Daemon；
- model fallback；
- release/install/update；
- compatibility policy。

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

1. 找到当前 Stage；
2. 选择该 Stage 中仍为 L0/L1 的一项；
3. 写出参考行为和本项目验收行为；
4. 先写 Fake/Fixture 测试；
5. 完成最小实现；
6. 对比参考差距；
7. 更新等级和证据链接；
8. 记录下一项阻塞能力。

禁止仅因为“看起来酷”跳去做后期能力，而不记录跨阶段原因。

## 23. 每个版本的差距报告模板

```text
Release:
Reference Baseline:
Current Learning Stage:

Capabilities advanced:
- FEATURE-ID: Lx → Ly

Behavior tests:
- passed / total

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

Next three capabilities:
1.
2.
3.
```

这个报告随每个版本提交，确保项目始终知道“现在在哪、差什么、为什么做下一步”。
