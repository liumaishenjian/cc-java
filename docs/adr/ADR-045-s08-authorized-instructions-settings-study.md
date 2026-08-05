# ADR-045：S08 授权 Instructions 与 Settings 机制研究边界

- Status: Accepted
- Date: 2026-08-05
- Stage: S08 Instructions + Settings
- Capability IDs: `BOOT-04`、`BOOT-06`、`CLI-07`、`CLI-08`、`CLI-09`、`MODEL-08`、`PERM-06`、`PERM-12`、`CTX-03`、`CTX-04`、`CTX-12`、`CTX-13`、`CFG-03`、`CFG-04`、`CFG-05`、`CFG-06`、`CFG-08`、`CFG-09`（明确排除 `CFG-07`）
- Current → S08 Exit Target: `BOOT-04` L1→L2；`BOOT-06` L0→L2；`CLI-07`/`CLI-08`/`CLI-09` L0→L2；`MODEL-08` L0→L2；`PERM-06` L2→L2（仅 Settings 集成验证）；`PERM-12` L0→L2；`CTX-03`/`CTX-04` L0→L2；`CTX-12`/`CTX-13` L1→L2；`CFG-03`/`CFG-04`/`CFG-05`/`CFG-06`/`CFG-08`/`CFG-09` L0→L2。本 ADR 仅完成 G0，不提升任何 Capability Level
- Reference Behavior Baseline: `R2026.03`（`REF-02`、`REF-04`、`REF-05`）
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Documented`（公开资料）/ `Observed` 与 `Inferred`（受控机制研究）/ `Unknown`

## 决策

S08 以独立的 Java 契约实现分层 Instructions、Settings 与交互命令；本 ADR 只冻结研究结论、采纳边界、风险和可证伪计划。S08 当前为 `IN_PROGRESS`，仅 G0 Passed；G1-G6 仍 Open，尚未实现配置 Schema、持久来源、命令 UX、测试、Demo 或 Capability Level 提升。

受控研究仅在登记的只读本地路径 `G:\AI Cloud\claude-code-main` 进行。研究材料只用于抽象职责、状态转换、安全边界和验证方法；不复制函数、Prompt、注释、私有名称、文件布局、常量、内部格式或错误文本，也不作为测试 Oracle。

## 机制研究结论与采纳边界

| 分类 | 可独立表达的结论 | S08 采纳边界 |
| --- | --- | --- |
| Observed | 指令输入可按受管、用户、项目、局部和工作目录邻近性分层收集；越接近当前工作位置的项目规则可拥有更高指导优先级。 | G1 定义项目自有 `InstructionSource`、`InstructionScope` 与有序 `InstructionProjection`；只加载经 WorkspaceGuard 验证的普通文本文件。 |
| Observed | 指令可包含受限的外部文本引用；递归引用需要去重、循环/深度控制、文本类型与大小限制，并记录父子来源。 | 引用是否进入首个切片由 G1 决定；若采纳，必须默认拒绝越界路径、Symlink/Junction 逃逸、二进制、循环、超限和无法归因的内容。 |
| Observed | 路径范围规则应在相关目标被明确时才参与 Context，且规则的内容、适用范围和来源是不同信息。 | G1 不得把路径规则全量常驻注入；须先定义匹配输入、规范化路径、范围空值语义和可诊断但不泄露正文的来源说明。 |
| Observed | 配置加载将每个来源的原始值、解析/校验错误与最终有效值分开；按显式优先级合并，并为诊断保留来源。 | G1 固定 `Defaults → User → Project shared → Project local → Session → CLI` 的项目自有优先级。未知字段、损坏格式、版本不支持和非法值不得静默成为有效配置。 |
| Observed | 标量、对象和列表不应依赖隐式通用合并；规则类字段还需要定义删除、去重和冲突语义。 | G1 必须逐字段声明：标量后者覆盖、对象递归合并或整体替换、列表替换/有序追加/去重的精确策略；禁止以库默认值代替契约。 |
| Inferred | 最终配置、来源链、遮蔽决策和校验警告需要独立于具体 UI，供诊断和命令 Surface 共用。 | G2 设计 Domain/Core 的不可变 `EffectiveSettings`、`SettingProvenance` 与无 Secret 的 `ConfigurationDiagnostic`；文件、JSON 与 UI 留在 Adapter。 |
| Observed | 模型选择、权限规则和 Tool 相关设置都属于配置面，但配置文本本身不应成为绕过安全执行面的凭据。 | S08 只能把已验证设置交给既有 Runtime/Pipeline；Hard Denial、显式 DENY、PLAN 限制、可信 ToolSource、S06 Recovery Gate、WorkspaceGuard 与审批顺序保持不变。 |
| Observed | 交互命令是会话状态转换入口：帮助展示可用能力；清理重置可丢弃交互状态；压缩需保留受控摘要；上下文展示读投影；模型/权限更新要经过可校验的会话覆盖；恢复必须重用 S06 Gate。 | G2 为 `/help`、`/clear`、`/compact`、`/context`、`/model`、`/permissions`、`/resume` 分别定义 Command Intent、前置条件、事件、失败和取消语义，禁止命令直接修改 Canonical Transcript 或绕过现有服务。 |
| Observed | 诊断应显示有效配置、来源顺序与无效输入，但 Secret、完整指令正文、敏感路径和原始凭证不应回显。 | G2 的 `/doctor` 与配置报告只输出字段名、来源类别、状态码、受限摘要和脱敏值；无权限或读取失败以可恢复诊断呈现。 |
| Observed | 读取/解析失败、文件变化、取消和压缩失败都需保持最后一个已验证状态，或安全降级，不得把部分结果提交为有效配置。 | G3 前的所有实现候选必须以快照式加载、版本/Schema 校验、取消传播和失败不提交为前提；需要写本地配置时复用安全文件写入边界。 |

## 状态与安全不变量

```text
发现候选来源
→ Workspace/真实路径与类型验证
→ 有界读取
→ Schema/version 解析与逐源诊断
→ 低到高优先级的字段化合并
→ 产生 EffectiveSettings + Provenance
→ 仅将已验证投影交给 Runtime、Permission Pipeline 和 UI
```

1. Instructions、memory、项目配置、命令输出和模型文本都属于不可信输入；它们只能提供指导或候选设置，不能注册 Tool、扩大 Workspace、改变可信 ToolSource、解除 Hard Denial 或批准副作用。
2. `Canonical Transcript` 仍是 S06 的规范事实。分层 Instructions、配置重载、`/context` 与 `/compact` 只生成投影或受控状态转换，不能重写历史、伪造 Tool Result 或自动重放未完成副作用。
3. 指令与设置路径都必须在每次读取、刷新或写入前解析真实路径并验证边界；拒绝绝对路径滥用、路径穿越、Symlink/Junction 逃逸、非普通文件、超限输入和竞态替换。
4. 诊断、事件和错误不得泄露 API Key、Token、完整 Prompt、完整指令正文、完整源码、未经处理的 Tool 输出或未脱敏绝对路径。
5. S08 不实现 `CFG-07` Managed Policy（仍明确延期至 S13/S14）、外部 Hook、MCP/Plugin 配置、Sub-Agent/后台任务/Worktree、OS Sandbox 或稳定跨版本迁移；它们仍分别属于后续 Stage。

## G1-G2 设计与验证交接

| 工作项 | G1 范围冻结 | G2 独立设计 | 可证伪验证 |
| --- | --- | --- | --- |
| Instructions | 来源集合、发现顺序、路径规则和引用是否纳入首切片 | Framework-free source/projection/diagnostic 契约与 Adapter 边界 | 同名近远目录优先级、重复/循环引用、路径范围命中/未命中、Symlink/Junction/超限拒绝、来源可追溯且不泄露正文。 |
| Settings | 字段清单、Schema version、每字段 merge/delete 语义、Session/CLI 生命周期 | 不可变有效配置、逐字段 provenance、校验错误和热重载/快照策略 | 标量覆盖、对象冲突、列表策略、未知/损坏/旧版本、局部文件 Gitignore、刷新竞态和失败保留旧快照。 |
| Model/Permission/Tool | 哪些现有入口可接受设置投影，哪些必须延期 | Command Intent 到 Runtime/Pipeline 的受控映射 | 配置/指令中的越权文本不能改变 Hard Denial、DENY、PLAN、可信来源绑定、审批或 S06 Recovery Gate。 |
| Slash 与诊断 | 七个命令的最小可交付交互与 stdio/TUI 差异 | 命令状态机、事件协议、取消和隐私安全报告 | `/clear` 不篡改持久事实；`/compact` 失败不提交；`/context` 与 S07 Usage 对账；`/resume` 仍拦截未完成副作用；诊断不回显 Secret。 |

层级 Instructions 接入后，必须重跑 S07 Summary 重注入、Context Usage、Canonical/Tool batch 保序和 ready-only Memory 回归；任何差异均不得以“设置变化”为理由跳过既有安全或预算 Gate。

## 被否决方案与延期

- **把所有设置一律深合并**：字段语义不透明，否决；必须逐字段声明。
- **由项目指令或配置决定权限最终结果**：会把不可信仓库内容提升为控制面，否决。
- **将来源诊断与完整值/正文直接输出**：会泄露 Secret 与本机信息，否决。
- **为快速交互直接修改 Session JSONL 或重放 Tool**：违反 S06 事实与恢复边界，否决。
- **在 G0 提前创建 Schema、文件格式或 Slash 实现**：超出机制研究范围，延期 G1-G3。
- **在 S08 承诺配置迁移、组织策略或 OS 隔离**：分别延期 S14、真实需求阶段和 S13。

## Unknown

授权快照的准确 Revision、许可证、权利人、跨版本配置兼容、受管组织策略、所有交互 Surface 的稳定协议及完整跨平台语义仍为 `Unknown`。这些未知项不阻止本项目以公开 `REF-02`、`REF-04`、`REF-05` 和独立测试定义 S08，但不能被描述为已与参考产品兼容。

## Gate 状态

本 ADR 和来源登记满足 S08 G0 的受控机制研究材料：授权范围、只读位置、抽象观察、独立采纳边界、未知项和 G1-G2 可证伪交接均已记录。G1-G6、Stage Exit、实现证据、Demo 与所有 Capability Level 保持 Open/不变；S08 不是 Accepted。
