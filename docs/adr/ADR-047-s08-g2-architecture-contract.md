# ADR-047：S08 G2 Instructions、Settings 与命令架构契约

- Status: Accepted
- Date: 2026-08-05
- Stage: S08 Instructions + Settings
- Capability IDs: `BOOT-04`、`BOOT-06`、`CLI-07`、`CLI-08`、`CLI-09`、`MODEL-08`、`PERM-06`、`PERM-12`、`CTX-03`、`CTX-04`、`CTX-12`、`CTX-13`、`CFG-03`、`CFG-04`、`CFG-05`、`CFG-06`、`CFG-08`、`CFG-09`（明确排除 `CFG-07`）
- Current → S08 Exit Target: `BOOT-04` L1→L2；`BOOT-06` L0→L2；`CLI-07`/`CLI-08`/`CLI-09` L0→L2；`MODEL-08` L0→L2；`PERM-06` L2→L2（仅 Settings 集成验证）；`PERM-12` L0→L2；`CTX-03`/`CTX-04` L0→L2；`CTX-12`/`CTX-13` L1→L2；`CFG-03`/`CFG-04`/`CFG-05`/`CFG-06`/`CFG-08`/`CFG-09` L0→L2
- Reference Behavior Baseline: `R2026.03`（`REF-02`、`REF-04`、`REF-05`）
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 本 ADR 是 cc-java 独立 `Documented` 架构契约；受控机制研究及 `Observed`/`Inferred`/`Unknown` 见 ADR-045

## 决策

本 ADR 完成 S08 G2：在不改变 ADR-046 已冻结的产品行为、来源优先级、字段、上限、合并语义或延期边界下，确定独立的 Domain/Core/Application/Adapter 类型、依赖方向、失败原子性与 G3/G4 接口。它不创建生产 Java/TypeScript、测试、Demo 或持久配置文件，不提升任何 Capability Level；S08 保持 `IN_PROGRESS`，仅 G0-G2 Passed，G3-G6 和 Stage Exit 仍 Open。

`cc-java-domain` 保存不可变的输入、投影、provenance、诊断、命令 Intent/Event 与状态枚举；`cc-java-core` 只保存无 `Path`、JSON、Git、终端、Spring AI、React/Ink 或 Node 类型的解析后 Port 和用例服务；`cc-java-cli` 负责 JSON、真实路径、Gitignore、stdio 编解码和 Composition Root；`cc-java-tui` 只把用户交互转换为 Intent 并渲染 Event。`cc-java-tools-local` 继续是 Workspace 文件 Tool 的 Adapter；S08 不把 user-root 访问伪装成 Workspace Tool，也不改变已有 `WorkspaceGuard` 的责任。

## 1. 分层类型与依赖方向

```text
React/Ink TUI ──stdio v0 Intent/Event──> Java CLI Application
Picocli/Print ─────────────────────────> Java CLI Application
                                          │
                                          ├─ InstructionApplicationService
                                          ├─ SettingsApplicationService
                                          ├─ SessionCommandDispatcher
                                          └─ DoctorReportService
                                          │
                         cc-java-core Ports / use-case services
                                          │
                         cc-java-domain immutable contracts
                                          ↑
     CLI filesystem/JSON/Git adapters ───┘
     tools-local WorkspaceGuard ─────────┘
```

- Domain 不携带原始文件正文、绝对路径、Secret、JSON node、`Path`、文件锁、线程/`Future`、终端状态或 Provider 对象。
- Core 不读取文件、不调用 Git、不解析 JSON、不修改 Session JSONL、不发起 Tool、也不拥有 React/Ink 状态；它只对已验证的来源快照、明确的用户 Intent 和既有 Session/Permission/Context Port 做纯协调。
- Application 先完整准备候选 `ResolvedInstructions`、`EffectiveSettings` 或命令执行计划，再以单次替换发布；任何解析、验证、取消、并发、S05/S06 Gate 或输出投影失败都不提交部分状态。
- Adapter 必须先将外部字节转换为有界、已验证的 Domain 输入。文件读取、严格 JSON、Git 状态、真实路径、Gitignore 和 stdio 字段白名单均留在 Adapter；Adapter 不能自行决定有效 Permission、恢复或 Context 结果。
- 仅在 G3 确有实现需要时新增包；不得为 S09-S14 创建 Hook、Managed Policy、MCP、Plugin、Sub-Agent、Worktree、Sandbox、Export、Retention 或 Migration 空模块。

## 2. Instructions 契约与独立 user-root 安全边界

ADR-046 的 user Instructions 位于 Workspace 外，故不得调用或扩展 `WorkspaceGuard` 来证明其安全。G3 必须由 CLI Adapter 提供与 WorkspaceGuard 独立的 `UserInstructionRootGuard`：Composition Root 仅从运行环境解析一次 `user.home`，再推导固定 root `${user.home}/.cc-java/instructions`。原始 home、绝对 root 和绝对目标永不进入 Domain、Canonical Transcript、Session JSONL、stdio、TUI 或普通诊断。

`UserInstructionRootGuard` 仅接受固定的逻辑候选 `AGENTS.md`，不接受模型、Settings、CLI 或仓库文本提供的可变路径。读取前、读取后与提交刷新前均必须：确认 root 和目标的 no-follow/真实路径关系、目标是普通 UTF-8 文件、目标 realpath 位于 user instruction root、没有 Symlink/Junction/identity 替换、并满足 ADR-046 的路径/字节/行数限制。root 不存在、不可访问、链接、越界、竞态或内容无效均返回无正文 `InstructionDiagnostic`，不回退到 WorkspaceGuard，也不阻止其他有效 Instructions 或最后已验证 Settings。

Workspace 内 Project、Directory 与 Local 候选仍由已有 `WorkspaceGuard` 的真实 Workspace containment 规则验证；Directory 激活只接受已通过该 guard 的 workspace-relative Tool target。`AGENTS.local.md` 还必须由 `GitIgnorePolicy` 验证为 Git 忽略：Adapter 对固定 `<workspace>/.cc-java/AGENTS.local.md` 使用不经 Shell 的 Git 查询或等价的受限 Git 元数据验证，只有明确的 `IGNORED` 才可加载。无 Git、Git 元数据损坏、命令失败、超时、取消、未知结果或路径无法证明时均为 `LOCAL_INSTRUCTIONS_NOT_GITIGNORED`，Fail Closed 丢弃 Local 文件；不得用文件名、`.gitignore` 文本猜测或默认放行替代验证。

Domain/Core 的最小不可变契约为：

```text
InstructionSourceKind = USER | PROJECT | DIRECTORY | LOCAL
InstructionScopeKind = USER_GLOBAL | WORKSPACE | DIRECTORY_SUBTREE
InstructionActivation = STARTUP | VERIFIED_TARGET
InstructionCandidate(sourceKind, scopeKind, safeSourceId, precedence, activation)
InstructionProvenance(sourceKind, scopeKind, safeSourceId, digestPrefix, precedence, activation)
InstructionDiagnostic(sourceKind, safeSourceId, code, lengthBucket, severity)
ResolvedInstruction(provenance, boundedText)
ResolvedInstructions(items, diagnostics, revision)
InstructionDiscovery.discover(InstructionDiscoveryRequest, CancellationToken)
InstructionLoader.load(InstructionCandidate, CancellationToken)
```

`safeSourceId` 只能是用户作用域固定标识或 workspace-relative 标识，`digestPrefix` 不是完整 digest，`boundedText` 只允许在 Core→Projection 的内部数据路径存在，不能被诊断/Event 访问。发现服务以 ADR-046 的低到高优先级输出、去重 canonical realpath+digest、执行文件数/深度/总量限制，并在输入 revision 或目标集合变化时重建短生命周期投影；它不写回 Canonical Transcript，不能影响 Tool Registry、Workspace、ToolSource、Permission 或 S06 Recovery Gate。

## 3. Settings JSON v1、合并与刷新契约

JSON v1 由 CLI Adapter 的专用严格解析器处理，而不是由宽松库默认反序列化后补救。解析器必须在 materialize 前限制字节、深度、member、列表与字符串上限，拒绝重复 object key，并完整验证对象根、首字段 `schemaVersion: 1`、白名单字段、类型、注册 builtin Tool、规则格式和所有 ADR-046 约束。重复 key 无论位于顶层或嵌套对象都使**整个来源无效**；不允许“最后一个 key 获胜”、部分对象采用、自动迁移或写回修复。

每个来源先转换为完整 `SettingsSourceSnapshot`，再按 `Defaults → User → Project shared → Project local → Session → CLI` 合并。任一来源的 JSON/文件/schema/registered-tool 校验失败都只产生该来源 `ConfigurationDiagnostic`，绝不泄露原始 JSON、绝对路径、端点或 Secret；已验证低层与此前 last-known-good 继续可用。`null`、删除 tombstone、标量覆盖、Tool config 替换、compact anchor 有序追加去重和 permission rule 按 `ruleId` 替换/删除必须逐字段完全遵循 ADR-046，不以通用深合并替代。

最小不可变契约为：

```text
SettingsSourceKind = DEFAULTS | USER | PROJECT_SHARED | PROJECT_LOCAL | SESSION | CLI
SettingsSourceId(kind, safeId)
SettingsSourceSnapshot(kind, safeId, revision, declaredValues, diagnostics)
SettingPath
SettingOperation = SET | REPLACE | APPEND | REMOVE | NO_OP
SettingProvenance(sourceKind, safeSourceId, precedence, operation, validationStatus)
EffectiveSettings(model, permission, toolVisibility, toolConfiguration,
                  compactInstructions, diagnosticsVerbosity, provenance, diagnostics)
ConfigurationDiagnostic(sourceKind, safeSourceId, code, severity, fieldPath)
SettingsResolver.resolve(orderedSnapshots) -> EffectiveSettings
SettingsSnapshotStore.current() / replaceIfCurrent(expectedRevision, replacement)
```

`EffectiveSettings` 表达最终值及逐字段/对象成员/列表元素/规则/删除 provenance；`ConfigurationDiagnostic` 只有固定 code、source kind、安全标识、字段路径和严重级别。诊断和 `toString()` 不得持有 Settings 原文、完整规则 selector、API Key、端点、绝对路径、指令正文或完整 Tool 配置。

刷新采用 copy-on-write 的 last-known-good 语义：首次完整成功解析/合并才建立快照；后续刷新在独立候选快照中完成，使用来源 revision/identity 比较和 `replaceIfCurrent` CAS 发布。读者只能得到前一完整快照或新完整快照，绝不观察混合字段；刷新失败、取消、被关闭或比较失败时保留原快照并返回诊断；成功但与当前 revision 竞争时丢弃候选并由下一次显式 refresh 重新读取，不能用旧候选覆盖较新有效状态。Session/CLI override 是内存不可变 overlay，不写 JSON、JSONL 或持久 Settings；进程结束后自然失效。

## 4. Settings 到模型、权限与 Tool 的受控映射

`RuntimeSettingsApplier` 是 Application 层唯一的投影入口。它接受 `EffectiveSettings`，仅把经过 Domain 校验的 `model.name`、Permission mode/rules、Tool visibility、Tool config 和 compact anchors 转换为下一 Run 的 `RuntimeConfiguration`。映射只在无活动 Run 的会话安全边界执行；失败时保持上一 `RuntimeConfiguration`。

- `model.name` 只选择已由现有 Provider 装配接受的名称；本 G3-C/D 子切片只允许当前启动时配置的单一模型名，Provider discovery 和多模型注册延期。它不能提供 Base URL、凭证、能力声明、Context 容量或重试策略。
- `permission.mode` 和 `permission.rules` 进入既有 S05 `PermissionPolicy`，仍按 Hard Denial → DENY → PLAN → ASK → ALLOW → Mode/Effect default 决策；本子切片的 Slash/stdio 只允许 `PermissionMode`，rules/selector 编辑延期。设置来源、项目指令、命令或 provenance 绝不成为可信 ToolSource 或超越此顺序的权限来源。
- Tool visibility 仅从已注册 builtin 集合取交集，能缩小但不能增加 Tool、发现 Tool、变更 Effect/ToolSource、改变 Workspace 或放松参数/输出上限。
- Tool config 仅传给对应 Tool 已注册的非 Secret、非执行策略 schema；不得更改 Shell、超时硬上限、网络、Sandbox、敏感路径、WorkspaceGuard 或结果上限。
- Session/CLI 权限更新也必须经过既有 selector 提取、审批与 Lifecycle/Pipeline；不得把 `/permissions` 或磁盘 JSON 直接写入内存 Grant、跳过 `DENY`/`PLAN`、解除 Hard Denial 或放行 S06 fenced/incomplete Session。

## 5. Command Intent、Event 与原子状态机

所有 Slash 和 stdio 请求在 Surface 边界先解码为下列封闭 Intent；Java Application 进行版本、字段、状态和权限校验后才分派。S08 stdio v0 仍为内部协议，不是 S14 稳定机器 API。

```text
SessionCommandIntent = Help | Clear | Compact(anchors) | Context |
                       Doctor | ModelChange(name) | Permissions(queryOrChange) |
                       Resume(sessionId)
SessionCommandResult = Succeeded(event) | Rejected(code) | Cancelled(code) | Failed(code)
SessionCommandEvent(kind, commandId, sessionId, payload)
```

命令状态机统一为 `RECEIVED → VALIDATED → EXECUTING → {SUCCEEDED | REJECTED | CANCELLED | FAILED}`，每个 `commandId` 只能有一个终态 Event。解析/校验失败停在 `REJECTED`，取消/异常停在 `CANCELLED`/`FAILED`；只有成功执行完成全部 Gate 后才提交可变状态，任何失败均为原子无变化。Event payload 必须是由 Domain 安全投影生成的字段白名单，不能带 Prompt、Instruction/Settings 正文、Secret、绝对路径、selector 原文、Tool 参数/结果、Provider 原始错误或 JSON 原文。

| Intent | 允许状态转换 | 原子提交与不变量 |
| --- | --- | --- |
| `Help` | 只读 | 仅发布当前 Surface 支持命令与延期能力。 |
| `Clear` | Surface transient → 清空 | 仅清空输入缓冲、展示历史和未发送 steering；不取消活动 Run、不写 Canonical/JSONL。 |
| `Compact` | idle → preparing → completed | 调用既有 S07 C1-C4；anchors 仅为不可信 Context 输入。显式请求即使 C1/C2 已满足预算仍可按既有 Gate 尝试 C3/C4；候选只在来源前缀未变化时安装给下一 Run 首个模型请求并一次性消费。失败/取消/来源变化不提交 Projection 或 Canonical 修改。 |
| `Context` | 只读 | 只投影最新 `ContextUsageView` 的数值/枚举；不可用返回固定 code。 |
| `Doctor` | 只读 | 汇总已经可用的 Settings/Instructions/Context/Surface 诊断；不得刷新、写入或回显正文。 |
| `ModelChange` | idle settings overlay → replaced | 校验后仅替换当前 Session 内存 override，下一 Run 使用；运行中、无效或不支持时旧值不变。 |
| `Permissions` | query → 安全投影；idle mode overlay → replaced | query 始终投影当前 Runtime effective mode；无 Settings LKG 时以 `BASELINE/runtime-baseline` 报告零条 Settings STARTUP rules。mode 变更先经 S05 类型化 policy 校验，成功后替换内存 overlay；Hard Denial、可信来源、Recovery Gate 和既有 Grant 不可改变。 |
| `Resume` | current session → candidate open → switched | 调用既有 S06 Resume 服务；只有 Workspace、Writer、fence、incomplete-side-effect 与 Checkpoint recovery Gate 全部通过才替换当前 Session。失败/取消/竞争保留当前 Session，绝不自动重放 Tool 或副作用。 |

`/compact`、`/context`、`/doctor` 可以作为无活动 Run 的只读/投影操作；`/model`、`/permissions` 与 `/resume` 必须拒绝活动 Run，避免与模型/Tool durable 顺序竞争。活动 Run 中仅 `steering` 可排队：它是 Surface 受限输入，不是 Slash 的旁路；队列消息只在当前 Run 终态后的下一 Run 边界消费，取消/clear/shutdown 丢弃未发送项，不写 Canonical Transcript 或 Session JSONL。

## 6. stdio v0 与 React/Ink 边界

S08 的 stdio v0 新命令统一使用 `type: "session.command"`、`protocolVersion: 0`、`commandId`、`intent` 和受限 `arguments`；结果事件使用 `type: "session.command.result"`、同一 `commandId`、`status`、固定 `code` 和按 Intent 定义的白名单安全 payload。实现时可保留已有 `initialize`/`run.start`/`run.cancel`/`shutdown`，但任何新增字段必须拒绝未知/重复/超限输入，且 stdout 仍只输出 NDJSON Event、stderr 只输出脱敏诊断。

React/Ink 维护纯 Reducer 的 ephemeral `InputState`、`HistoryState`、`CompletionState`、`SteeringQueueState` 和命令请求状态，绝不读取 Session 文件或直接改 Runtime：

- 多行编辑以显式提交键触发；缓冲最多 8,192 Unicode code point，超限拒绝输入且不截断后提交。
- 历史只保留当前进程最多 100 条已提交输入，不持久化、不写 JSONL，且不保存 Secret/完整 Prompt 的新持久副本。
- 补全只针对已声明命令及其已知参数产生最多 32 个候选；它不枚举文件、Settings 正文、Tool、路径或权限 selector。
- 运行中普通提交只能形成有界 steering queue，不能插入当前模型 Turn、Tool batch、审批、Command 或 S06 durable 记录；Java Runtime 是唯一决定安全消费时点的权威。
- TUI 接收到 command result/event 只更新 Reducer；未知、乱序、重复或终态后的事件 Fail Closed 为安全 transport/protocol 诊断，不推测 Runtime 状态。

## 7. G3 A-F 实现切片与 G4 测试矩阵

| 切片 | G3 最小范围 | 关键类型/Adapter | G4 必须证伪的行为 |
| --- | --- | --- | --- |
| A | 分层 Instructions 发现、加载与请求投影 | `ResolvedInstructions`、`InstructionDiscovery`、`UserInstructionRootGuard`、Workspace Adapter | User root 与 Workspace guard 独立；user/project/directory/local 顺序、根不重复、目标命中/未命中、去重与全部 16/8/32KiB/128KiB 限制；UTF-8/普通文件/realpath/Symlink/Junction/TOCTOU 拒绝；恶意指令不提权。 |
| B | Settings v1 严格读取、last-known-good 与 doctor 投影 | duplicate-key parser、`SettingsSourceSnapshot`、`SettingsResolver`、`SettingsSnapshotStore`、GitIgnorePolicy | 任意嵌套重复 key、unknown/version/type/超限/损坏使整源无效；每字段覆盖/替换/追加/去重/rule replace/remove 正确；`AGENTS.local.md` 无 Git/损坏 Git/非 ignored Fail Closed；刷新竞态和取消不混合字段、不覆盖新快照；诊断零 Secret/正文/绝对路径。 |
| C | Settings/override 到既有 Runtime 受控映射 | `RuntimeSettingsApplier`、S05/S06 adapters | model/permission/tool visibility 只在安全边界应用；Tool allowlist 只能缩小；项目文本/Settings/Slash 无法改变 Hard Denial、DENY、PLAN、ToolSource-bound selector、审批、WorkspaceGuard、Tool 安全校验或 Recovery Gate；Provider 凭证无法设置/回显。 |
| D | Command dispatcher、安全 View 与 stdio Contract | `SessionCommandDispatcher`、Command codec、Context/Doctor adapters | 每个 commandId 唯一终态；`/clear` 不改 JSONL；compact 失败/取消不提交；`/context` 与 S07 Usage 对账；doctor 零正文/Secret；S07 Summary 重注入、Tool batch 配对及 ready-only memory 迟到不注入回归。 |
| E | Ink 输入、历史、补全、steering queue | TUI pure reducers、stdio client | 8,192/100/32 上限；多行显式提交；steering 只在 Run 后边界消费，取消/clear/shutdown 无遗留；TTY/stdio Reducer 不把 Surface 状态写入 Canonical。 |
| F | `/resume` 与既有恢复服务 | Resume Intent adapter | locked/fenced/workspace mismatch/incomplete side effect 均拒绝；没有 Tool 自动重放；Resume/Fork canonical history、Call ID 配对、唯一 Run 终态保持。 |

整体 G4 仍必须满足 ADR-046：所有新增离线测试通过；S07 的 Tool protocol orphan 为 `0`、事实与硬约束保持率 `100%`、任务完成率不低于未压缩对照、进入 reduction 的实际中位数 token 降幅至少 `30%`，慢记忆预取不增加关键模型请求等待。未取得真实测试、Demo 与 Commit-scoped 对账前，不得提升 Capability Level、标记 G3+ 或宣称 S08 Accepted。

## 被否决方案与延期

- **用 WorkspaceGuard 验证 user Instructions**：user root 不属于 Workspace，边界错误；否决，改用独立 user-root guard。
- **宽松 JSON 或重复 key 后取最后值**：来源可被歧义覆盖；否决，整源 Fail Closed。
- **本地 `AGENTS.local.md` 在 Git 不可用时默认加载**：会把本应本地忽略的仓库内容当作有效输入；否决，Gitignore 验证不可证明时拒绝。
- **刷新时逐字段原地更新**：读者可见混合配置；否决，采用 copy-on-write last-known-good/CAS。
- **Slash/TUI 直接改 Runtime、JSONL 或 Tool**：破坏 S05/S06/S07 所有权与 durable 顺序；否决。
- **让 `model`、`permissions` 或 Tool config 声称 Sandbox/跳过安全 Gate**：S08 不提供 OS Sandbox、Managed Policy、外部扩展或凭证配置；否决并延期 S09-S14。

## Gate 状态

G2 Passed。ADR-045 的授权研究边界、ADR-046 的产品契约与本 ADR 的独立架构/安全/测试契约共同形成 G0-G2。当前 G3 A-F 已有独立实现切片，G3-E 形成下述架构证据；完整 G3 对账、G4-G6、Demo/Gap 与 Stage Exit 仍 Open。

## G3-E 架构落点（2026-08-06）

G3-E 没有把队列放进 Core、Canonical 或持久 Session。React/Ink 只维护编辑缓冲、每 Session
历史、补全与尚待关联的短生命周期 prompt；`StdioClient` 以 request/session 和
`awaiting_queued → queued → {started | discarded}` 状态机校验事件。Java
`RuntimeStdioCommandHandler` 是 steering FIFO、100 条预算、安全消费时点和丢弃语义的权威，
单线程 Run executor 保证下一条只在前一条唯一终态投影后启动。

事件出口失败会先关闭 Adapter、清空未发送队列并取消活动 Run；close 即使丢弃事件写出失败仍会
释放 approval、executor 与 Application。第 101 条以关联 `protocol.error` 拒绝时，Client 和 TUI
只忘记该 request 的瞬态正文，不改变活动 Run 或权威 queue depth。上述契约已有 FIFO、100/101、
延迟首个 `run.started`、重复/乱序 lifecycle、取消/clear/resume/shutdown/transport failure 与零
JSONL 泄漏回归。G2 决策不变；完整 G3/G4-G6 与 Stage Exit 仍需后续对账。
