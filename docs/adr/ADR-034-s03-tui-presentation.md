# ADR-034：S03 终端语义化展示

- Status: Accepted
- Date: 2026-07-30
- Stage: S03 Read Tools 退出后体验维护
- Capability IDs: `CLI-03`、`CLI-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed`；本项目契约与实现为 `Documented`

## 背景

S03 已能流式显示模型文本和只读 Tool 的安全状态，但首版 TUI 把 Markdown 原文、
每次 Tool 调用和机器终态直接平铺到屏幕。它可以验证协议，却不能清楚表达“用户问题、
Agent 活动、最终答案、失败诊断”之间的层级，也让连续搜索淹没真正的回答。

本次只改善既有能力的终端投影，不改变 Runtime、Tool Pipeline、Permission 或 Stage
范围，不引入 S04 写入、Command 和 Approval，也不提前实现 S08 的历史、补全和
Slash Command。

## 受控参考机制

对授权快照的只读研究只提炼以下可独立表达的机制，不复制函数体、Prompt、私有命名、
布局、错误文案或常量：

1. Assistant 正文和 Tool 活动使用不同渲染职责；
2. 连续的读取、搜索和枚举操作默认折叠为紧凑活动摘要；
3. 进行中、成功、截断和失败具有不同视觉优先级，失败不能被折叠隐藏；
4. Markdown 由专门组件渲染，流式未闭合片段也必须保持可显示；
5. 默认界面保持简洁，详细 Tool Result 不直接铺满主会话。

公开成熟 CLI 的行为对照用于交叉验证上述机制，但不作为代码、Fixture 或 Golden
Output 来源。

## 决策

保留 Java Headless Runtime + React/Ink TUI 架构，增加独立展示组件：

```text
Java 有序事件
  → Reducer 只读投影
  → ToolActivityGroup（连续 Tool 语义化聚合）
  → AssistantMarkdown（Marked 解析 + Ink 组件）
  → RunSummary / PromptComposer
```

- 使用成熟的 `marked` 只做 Markdown 词法解析；颜色、间距和终端组件由项目独立设计；
- 首版覆盖标题、段落、强调、行内代码、代码块、列表、引用、链接和分隔线；
- Markdown 解析失败时退回纯文本，不让展示错误中断 Agent Session；
- Tool 活动只消费 stdio v0 已有的脱敏字段：名称、序号、状态、返回字符数、过滤数量、
  返回条目数、搜索模式、截断原因和安全错误码；不展示查询、参数、路径或 Tool Result
  正文。搜索模式只允许 `content/files/count` 三个固定枚举；
- 同类连续 Tool 合并为一行，成功保持低噪声；活动项、失败、拒绝和截断显著显示；
- `content/files/count` 分别把返回条目解释为匹配、文件和已统计文件，避免用字符数冒充
  用户关心的结果规模；不同模式不得混合累计；
- Java 终态仍是唯一权威。成功摘要只显示回合/Tool 计数，失败摘要保留
  `stopReason`，TUI 不从 Tool 状态自行推断 Run 成功；
- Session 系统指令要求模型默认只总结和引用与问题有关的匹配，不复述完整 Tool Result；
  用户明确要求穷举时仍应服从，TUI 不擅自裁掉正式 Assistant 回答；
- 输入区与历史区分离；运行时提示取消方式，连接期仍允许预输入；
- 窄窗口、中文宽字符、Paste、Resize、Ctrl+C 和进程生命周期契约保持不变。

本变更只改善 `CLI-03`、`CLI-04` 的 L2 表达质量，不提升 Capability Level。

## 可证伪验证

1. Markdown 组件测试覆盖标题、列表、行内代码、代码块和未闭合流式片段；
2. Tool 活动测试覆盖连续聚合、进行中、成功、截断、拒绝和失败；
3. AgentView 测试覆盖窄窗口、中文、终态层级和输入区；
4. Reducer 测试证明 `filteredItems` 等安全元数据被保留，而未知原始字段不进入视图；
5. TUI build/test、真实终端 Demo、Maven 回归和进度看板共同验证架构边界未漂移。
