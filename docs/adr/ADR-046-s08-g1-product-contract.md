# ADR-046：S08 G1 Instructions、Settings 与命令可证伪产品契约

- Status: Accepted
- Date: 2026-08-05
- Stage: S08 Instructions + Settings
- Capability IDs: `BOOT-04`、`BOOT-06`、`CLI-07`、`CLI-08`、`CLI-09`、`MODEL-08`、`PERM-06`、`PERM-12`、`CTX-03`、`CTX-04`、`CTX-12`、`CTX-13`、`CFG-03`、`CFG-04`、`CFG-05`、`CFG-06`、`CFG-08`、`CFG-09`（明确排除 `CFG-07`）
- Current → S08 Exit Target: `BOOT-04` L1→L2；`BOOT-06` L0→L2；`CLI-07`/`CLI-08`/`CLI-09` L0→L2；`MODEL-08` L0→L2；`PERM-06` L2→L2（仅 Settings 集成验证）；`PERM-12` L0→L2；`CTX-03`/`CTX-04` L0→L2；`CTX-12`/`CTX-13` L1→L2；`CFG-03`/`CFG-04`/`CFG-05`/`CFG-06`/`CFG-08`/`CFG-09` L0→L2
- Reference Behavior Baseline: `R2026.03`（`REF-02`、`REF-04`、`REF-05`）
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 本 ADR 是 cc-java 独立 `Documented` 产品契约；授权机制采纳边界、`Observed`/`Inferred` 与 `Unknown` 见 ADR-045

## 决策

本 ADR 完成 S08 G1：冻结独立命名的行为、文件位置、输入上限、逐字段合并、Surface 最小语义和 G3/G4 可证伪标准。它不创建 Java/TypeScript、测试、Demo 或配置文件，所有 Capability Level 保持不变；S08 仍为 `IN_PROGRESS`，G2-G6 与 Stage Exit 保持 Open。

所有候选 Instructions、Settings、命令参数和仓库内容是不可信输入。它们只能生成经校验的 Context 或设置投影，不能扩大 Workspace、注册 Tool、改变可信 `ToolSource`、解除 Hard Denial、跨过 `DENY`/`PLAN`、跳过审批或绕过 S06 Recovery Gate。

## 1. Instructions

### 1.1 文件与发现顺序

本项目只采用已存在的 `AGENTS.md` 命名约定，不兼容或复制其他产品的文件布局。每个 Session 固定一次规范 Workspace realpath 后，候选按下列低到高优先级发现；同一层内按表中顺序追加：

| 顺序 | 来源 | 固定位置 | 作用域 |
| --- | --- | --- | --- |
| 1 | User | `${user.home}/.cc-java/instructions/AGENTS.md` | 当前用户全部 Workspace |
| 2 | Project | `<workspace>/AGENTS.md` | 当前 Workspace |
| 3 | Directory | `<workspace>/<ancestor>/AGENTS.md`，从 Workspace 子目录到当前目标文件父目录 | 仅该目录及其后代 |
| 4 | Local | `<workspace>/.cc-java/AGENTS.local.md` | 当前 Workspace；必须 Git 忽略 |

`${user.home}` 仅在 Adapter/Composition Root 解析，绝对路径不进入 Model Context、Session JSONL、事件或默认诊断。Project 根 `AGENTS.md` 在第 2 层只加载一次；当目标是 Workspace 根或没有明确目标文件时，不加载 Directory 层。Directory 规则的激活输入是 Tool 已通过 `WorkspaceGuard` 验证的 workspace-relative target path；纯自然语言、模型猜测、绝对路径、被拒绝或无目标的 Tool Call 都不激活 Directory 层。一个模型请求涉及多个已验证目标时，收集各目标祖先规则的并集，按从远到近的目录深度、再按稳定字节序去重；最终每个来源内容至多出现一次。

`AGENTS.local.md` 优先于同目录 Project/Directory `AGENTS.md`，只按其所在 Workspace 根作用，不在子目录重复加载。不同来源即使字节完全相同，仍保留独立 provenance；同一 canonical realpath、同一 digest 的重复候选只投影一次，并在诊断标记 `DUPLICATE_SUPPRESSED`。冲突不是解析错误：低优先级内容先出现，高优先级内容后出现，模型 Context 不将 Instructions 解释为权限规则。

### 1.2 内容、范围和安全边界

每个文件必须是 WorkspaceGuard 边界内的普通 UTF-8 文件；User 文件只允许位于固定 user instruction root。所有层拒绝符号链接、Windows Junction、绝对路径输入、Traversal、`.git`、敏感文件、二进制/NUL、非法 UTF-8、读取前后 identity/realpath 变化及大小超限。读取后再次验证 canonical realpath、普通文件属性和 identity；任一失败丢弃该候选、产生无正文诊断，并继续使用最后一个已验证 Settings 快照或其余有效 Instructions。

| 限制 | 值 | 失败行为 |
| --- | --- | --- |
| 单文件 UTF-8 字节 | 32 KiB | 拒绝该文件 |
| 单文件行数 | 1,000 | 拒绝该文件 |
| 单次投影文件数 | 16 | 按发现优先级保留前 16，其余诊断为 `COUNT_LIMIT` |
| Directory 搜索深度 | 8 层（不含 Workspace 根） | 更深目录不参与 |
| 全部 Instruction 正文 | 128 KiB | 按发现优先级保留完整文件；下一个将超限的文件及后续文件不参与 |
| 文件路径长度 | 240 Unicode code point | 拒绝该候选 |

S08 首/完整范围都**不支持 imports/includes**：`AGENTS.md` 与 `AGENTS.local.md` 的任何引用样式都只是普通不可信文本，绝不触发额外文件读取。递归引用、跨文件包含、网络包含和其循环/深度语义明确延期，不能由实现自行扩展。每份已投影文件必须产生 `InstructionProvenance(sourceKind, scopeKind, workspaceRelativeOrUserScopedId, digest, precedence, activation)`；诊断只允许显示来源类别、相对标识、作用域、状态码、长度桶和 digest 前缀，绝不显示正文、用户 home、Workspace 绝对路径或 Secret。

## 2. Settings schema v1

### 2.1 来源、格式和生命周期

Settings schema v1 是 JSON object，首字段必须是整数 `schemaVersion: 1`。除现有 Git 忽略 Provider 文件外，S08 新增的通用 Settings 固定位置如下；它们不读写 API Key、Base URL 或任意凭证，S02 `config/provider.local.properties` 继续仅由既有 Provider Loader 处理。

| 优先级（低→高） | 来源 | 固定位置 | 生命周期/写入边界 |
| --- | --- | --- | --- |
| 1 | Defaults | 编译期受信默认值 | 不可写 |
| 2 | User | `${user.home}/.cc-java/settings.json` | 跨 Session；仅用户本机 |
| 3 | Project shared | `<workspace>/.cc-java/settings.json` | 可版本控制；仓库不可信 |
| 4 | Project local | `<workspace>/.cc-java/settings.local.json` | 必须 Git 忽略；跨本机 Session |
| 5 | Session | 仅当前 `AgentSession` 内存覆盖 | 进程结束失效，绝不写 JSONL |
| 6 | CLI | 当前进程显式参数 | 进程结束失效，绝不写入任何 Settings 文件 |

每个文件最大 32 KiB、最多 8 个对象层级、最多 128 个 object member、最多 64 个列表项、字符串最多 4,096 Unicode code point；解析前后都必须执行 no-follow、realpath、普通文件、边界和竞态校验。Project shared/local 只能使用 workspace-relative固定路径，不能按配置指定替代位置。未知顶级/嵌套字段、重复 JSON object key、非对象根、缺失/非整数 `schemaVersion`、`schemaVersion != 1`、非法类型或超限均使**该来源整体无效**，不部分采用；低优先级已验证快照继续有效。S08 v1 不做自动升级、迁移或写回，Schema migration 仍延期 S14。

### 2.2 允许字段

`null` 只表示“本来源未设置此字段”，不覆盖低层；除表中 `remove` 外不表示删除。未列字段一律拒绝。所有通过 Settings 得到的权限规则仍要经过 S05 类型化 Policy、可信 ToolSource/selector、Hard Denial、`DENY`、`PLAN`、审批和 Pipeline 校验。

| 字段 | JSON 类型与上限 | 允许来源 | 生命周期 | 含义 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | integer，必须 `1` | 文件 | 文件 | 格式版本，不参与合并 |
| `model.name` | string，1–256 code point | User/Project/Local/Session/CLI | 覆盖 | 请求模型名；不包含端点或 Secret |
| `permission.mode` | enum `DEFAULT`/`PLAN`/`ACCEPT_EDITS` | User/Project/Local/Session/CLI | 覆盖 | 交给既有 S05 Mode 入口 |
| `permission.rules` | rule list，最多 32 | User/Project/Local/Session | 有序合并/删除 | 仅 `ALLOW`/`ASK`/`DENY` 与既有 Tool/可信 ToolSource/selector 表达；无 CLI JSON 规则 |
| `tools.enabled` | string list，最多 64，每项 1–128 | User/Project/Local/Session/CLI | 替换 | 仅已注册 builtin Tool 名称的可见性 allowlist；未知名拒绝该来源 |
| `tools.config` | object，最多 32 entry；每个 value 为对象且最多 16 scalar member | User/Project/Local/Session | 按 Tool 替换 | 已注册 builtin Tool 的非 Secret、非执行策略参数；键/字段由 Tool 的受信 schema 校验 |
| `context.compactInstructions` | string list，最多 16，每项 1–512 | User/Project/Local/Session/CLI | 有序追加去重 | `/compact` 的用户保留锚点；正文是 Context 输入而非权限 |
| `diagnostics.verbosity` | enum `OFF`/`SUMMARY`/`DETAIL` | User/Project/Local/Session/CLI | 覆盖 | 控制无正文、无 Secret 的诊断粒度 |

`tools.enabled` 只能缩小当次 Tool 可见集，不能添加或发现 Tool；`tools.config` 不能改变 Tool Effect、ToolSource、Workspace、超时上限、Shell、网络、Sandbox、敏感路径或输出安全上限。`permission.rules` 不能设 Hard Denial，不能以 `ALLOW` 覆盖高优先级 `DENY`、`PLAN` 或现有 Hard Denial；Project shared/local 规则同样是不可信候选而非控制面。

## 3. 逐字段合并、删除与 provenance

来源按 2.1 的低→高顺序处理。每个有效来源首先被完整 schema/type/schema-registered-tool 校验；之后才逐字段合并。最终每个字段、对象成员、列表元素、规则和删除都必须保留 `SettingProvenance(sourceKind, sourceId, precedence, operation, validationStatus)`，输出时只展示 source kind/id 的安全投影。

| 最终字段 | 合并算法 | `null`/删除 | 最终 provenance |
| --- | --- | --- | --- |
| `model.name` | 非 null 标量由后者整体覆盖 | `null` 无操作；不可删除 Defaults | 最后一个提供值的来源 |
| `permission.mode` | 非 null enum 由后者整体覆盖 | `null` 无操作；不可删除 Defaults | 最后一个提供值的来源 |
| `diagnostics.verbosity` | 非 null enum 由后者整体覆盖 | `null` 无操作；不可删除 Defaults | 最后一个提供值的来源 |
| `tools.enabled` | 非 null list 整体替换，不追加 | `null` 无操作；空 list 表示没有 Tool 可见；不可删除 Defaults 的 schema 约束 | 提供最终 list 的来源；逐项带该来源 |
| `tools.config` | 顶层按 Tool 名称 merge；高层出现某 Tool 时整体替换该 Tool config，不递归 merge 内部对象 | `null` 无操作；`{"tool": null}` 删除低层该 Tool config | 每个 Tool config/删除记录最后操作来源 |
| `context.compactInstructions` | 低→高有序追加；按 Unicode code point 精确相等去重，首个出现保留位置，后续来源追加新项 | `null` 无操作；空 list 无新增；不可删除低层锚点 | 每个最终项记录首个提供来源与所有重复抑制来源 |
| `permission.rules` | 按稳定 `ruleId` 合并：低→高追加；同 `ruleId` 由更高层完整替换，保持首次插入位置；不同 ID 保留全部 | `null` 无操作；`{"remove":"ruleId"}` 只删除低层同 ID，删除高/同层不存在时是来源错误 | 每个最终 rule 为最后替换来源；删除保留 tombstone provenance |

Rule `ruleId` 必须为小写 kebab-case、1–64 ASCII 字符；rule 的 `effect`、`tool`、可信 `toolSource`、规范 selector、decision 与 `ruleId` 是整体对象，禁止部分 merge。冲突后的规则集合仍由既有固定优先级求值，而不是按 Settings 列表顺序决定：Hard Denial → DENY → PLAN → ASK → ALLOW → Mode/Effect default。CLI 只接受现有明确参数的标量/可见性/compact/diagnostic 覆盖，不能输入规则对象，也不能写持久配置。

## 4. Slash 与 doctor 最小 Surface 契约

每个命令先转换为 Command Intent，由 Java Application/Core 解释；TUI、Headless 和 stdio 只发送命令/消费事件，不能自己改 Runtime、Session 或持久文件。任何运行中的 Tool/Model 操作由既有取消协议处理；命令取消、解析失败或执行失败不得提交部分 transient、Canonical 或持久状态。

| 命令 | 最小成功语义 | 可变状态 | 失败/取消与隐私输出 |
| --- | --- | --- | --- |
| `/help` | 显示当前 Surface 支持的七个命令、参数与延期能力 | 无 | 未知命令返回固定 code；不输出配置/指令正文 |
| `/clear` | 清除当前 TUI 输入缓冲、展示历史和未发送 steering；不删除 Session | 仅 Surface transient | 活动 Run 不自动取消；失败无状态变化 |
| `/compact [anchor...]` | 请求现有 S07 C1-C4 路径，并把有效 `compactInstructions` 作为 protected anchors；显式请求即使 C1/C2 已满足预算仍可尝试 C3/C4 | 仅安装给下一 Run 首个模型请求的一次性短生命周期 Projection；Canonical 不变 | 取消、来源变化或摘要 Gate 失败不提交；显示固定原因码 |
| `/context` | 读取最新 `ContextUsageView` 的数值/枚举安全投影与已应用 reduction | 无 | 没有 View 显示 `UNAVAILABLE`；不输出 Prompt、正文、路径、Tool 参数或结果 |
| `/model <name>` | 校验并写入当前内存 Session override；本 G3-C/D 子切片仅接受启动时已配置的同一模型名，下一 Run 使用它 | Session transient only | 运行中拒绝变更；无效/不支持不替换旧值；不写 JSONL/文件；Provider discovery、多模型注册延期 |
| `/permissions [mode]` | 无参数显示当前 Runtime effective mode、Settings 派生 STARTUP rules provenance 与固定安全状态；`mode` 仅创建受限内存 override | Session transient only | 运行中拒绝变更；rules/selector 编辑延期；不能修改 Hard Denial/可信来源/Recovery Gate；无 selector 原文 |
| `/resume <session-id>` | 调用既有 S06 Resume/Inspect Gate，成功后切换到其规范 Session | S06 已允许的打开状态 | 未完成副作用、fenced、锁、Workspace mismatch 或取消保持当前 Session；不自动重放 |
| `/doctor` | 读取有效 Settings/Instruction/Context/Surface 诊断，按来源和状态码报告 | 无；绝不刷新或写入 | 文件缺失/非法/竞态产生可恢复诊断；无 Secret、正文、绝对路径、端点或原始异常 |

CLI-09 的 L2 需要 React/Ink 同时支持：受控多行编辑（显式提交）、每 Session 有界历史导航、命令名/参数补全，以及运行中只允许队列化 steering、不得抢占模型/Tool 协议。最低上限是编辑缓冲 8,192 Unicode code point、历史 100 条、候选 32 个；秘密/完整 Prompt 不写历史持久化。非 TTY/Print 不提供交互 Slash；其显式 CLI 覆盖仍是一次进程生命周期。stdio v0 在 S08 可以增加对应 Intent/Event，但仍非 S14 稳定公共协议。

## 5. G3 实现切片与 G4 可证伪证据

| 切片 | Target Feature | G3 最小实现 | G4 必须证伪的测试/度量 |
| --- | --- | --- | --- |
| A | `BOOT-04`、`CTX-03`、`CTX-04` | Framework-free resolved instruction/provenance/diagnostic 值对象与 local loader；将只读投影接入请求准备 | User/project/directory/local 顺序、根不重复、路径命中/未命中、16/8/32KiB/128KiB 上限、重复抑制、UTF-8/普通文件/realpath/Symlink/Junction/TOCTOU 拒绝；指令不能提权 |
| B | `CFG-03/04/05/06/08/09`、`BOOT-06` | v1 parser、快照 loader、field merger、provenance/doctor adapter | 每字段覆盖/替换/追加/去重/rule 替换与 remove；未知/重复 key/版本/类型/超限令整源失效；local Gitignore fixture；刷新竞态保持最后有效快照；诊断不含 Secret/正文/绝对路径 |
| C | `MODEL-08`、`PERM-06`、`PERM-12` | Session/CLI override intent 到现有 model/policy/tool visibility 的受控映射；本子切片只接受启动模型、仅允许 PermissionMode | settings/project instruction 不可覆盖 Hard Denial、DENY、PLAN、ToolSource-bound selector、审批或 Recovery Gate；Tool allowlist 只能缩小；Provider credential 不可配置/回显；无效/取消/CAS/Scope 失败不提交 |
| D | `CTX-12`、`CTX-13`、`CLI-08` | Command dispatcher、`/compact`/`/context`/`/doctor` safety projections 和 stdio/TUI events；`/permissions` 查询投影实际 Runtime mode 和仅 Settings 派生的 STARTUP rules | `/clear` 不改 JSONL；compact failure/cancel 不提交；Usage 与 S07 view 对账；命令输出零正文/零 Secret/零 selector；S07 summary reinjection、Tool Call/Result pairing、ready-only Memory 迟到不注入回归均通过 |
| E | `CLI-07`、`CLI-09` | Ink 多行/有界历史/补全与 steering queue | steering 只在安全边界进入下一 Run；取消不遗留 queued message；8,192/100/32 上限；TTY/stdio reducer 不把 Surface 状态写进 Canonical Session |
| F | `CLI-08`、`SESSION-04/05/09` 回归 | `/resume` Intent 连接既有 S06 service | locked/fenced/workspace mismatch/incomplete side-effect 均拒绝；没有 Tool 自动重放；Resume/Fork canonical history 与唯一终态保持 |

整体 G4 指标：全部新离线测试通过；Instructions/Settings 导入后的 S07 Eval 仍为 Tool protocol orphan `0`、事实与硬约束保持率 `100%`、任务完成率不低于未压缩对照、进入 reduction 的实际中位数 token 降幅至少 `30%`，且慢记忆预取不增加关键模型请求等待。指标不满足时不得提升 Capability Level 或标记 G4 Passed。

## 6. 延期与 G2 唯一未决项

`CFG-07` Managed Policy、组织/受管控制面、自动 migration、跨版本兼容、配置同步/导出/Retention、Provider endpoint/credential 设置、外部 Hook、MCP/Plugin、Skill、Sub-Agent、后台任务、Worktree、OS Sandbox 与稳定公共机器协议均不在 S08。

G2 只需在不改变本 ADR 行为契约的前提下确定：(1) Domain/Core/Application/Adapter 的具体类型和模块归属；(2) JSON parser 与无重复 key 的具体实现方式；(3) `AGENTS.local.md` Gitignore 检测在无 Git 或损坏 Git 元数据时的 fail-closed 诊断路径；(4) stdio v0 的具体 Intent/Event 字段名。上述选择不得改动路径、优先级、字段、上限、merge、权限、Session、Context 或 Surface 语义；若需要改动，必须新 ADR 并重新审查 G1。

## Gate 状态

G1 Passed。ADR-045 保持 G0 研究交接；本 ADR 冻结 S08 独立范围、目标等级、输入/输出、失败语义、延期边界与测试指标。G2-G6、Stage Exit、实现、Demo 和 Capability Level 仍 Open/不变，S08 不是 Accepted。
