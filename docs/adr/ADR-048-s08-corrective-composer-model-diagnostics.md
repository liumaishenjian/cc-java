# ADR-048：S08 Corrective Composer 与模型诊断契约

- Status: Accepted（Corrective Contract）
- Date: 2026-08-06
- Stage: S08 Instructions + Settings（Corrective / Reopened）
- Capability IDs: `CLI-08`、`CLI-09`、`BOOT-06`、`CFG-09`、`OBS-04`；回归 `CLI-07`、`CTX-12`、`CTX-13`、`MODEL-08`、`PERM-12`、`SESSION-04`、`SESSION-05`、`SESSION-09`
- Current → Corrective Exit Target: `CLI-08` L1→L2、`CLI-09` L1→L2；`BOOT-06`、`CFG-09` 保持 L2 并重新证明 Surface 诊断；`OBS-04` 保持 L1；其余所列能力只做不降级回归
- Reference Behavior Baseline: `R2026.03`（现有公开范围；本纠错契约不新增公开行为 Oracle）
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`（只复用 ADR-045 已登记的抽象研究边界，不复制参考表达）
- Classification: 本 ADR 是 `cc-java` 独立 `Documented` 纠错契约；Corrective G3 已由当前工作树实现，G4 已取得聚焦测试证据但仍待完整 Reactor 与真实 TTY 验收
- Amends: [ADR-046](./ADR-046-s08-g1-product-contract.md)、[ADR-047](./ADR-047-s08-g2-architecture-contract.md)

## 决策

S08 从 Accepted 重开为 **Corrective / Reopened**。ADR-045 的来源与授权边界继续有效，ADR-046/047 中不与本 ADR 冲突的 Instructions、Settings、Permission、Context、Session 和命令所有权契约继续有效；本 ADR 取代其中过窄的编辑器、粘贴、命令交互和模型故障诊断验收标准。

当前工作树已完成 G3 独立实现，并取得 Composer/TUI、stdio 原子传输、ModelDiagnostic 与 launcher 的聚焦 G4 证据；但完整 Maven Reactor、真实 TTY G5 与固定 Commit 独立 G6 对账仍未完成。因此不得再次宣称 S08 Accepted，`CLI-08`、`CLI-09` 继续暂按 L1 记录；dirty worktree 与聚焦测试数量不单独恢复 L2。

## 1. 为什么此前 CLI-08 / CLI-09 的验收不足

此前证据验证了受限 Slash Intent、安全结果白名单、8,192 输入上限、进程内历史和封闭补全，却把“有命令入口”和“字符串缓冲可提交”等同于完整可用交互。实际契约没有冻结或充分证伪以下行为：

1. 输入状态缺少 grapheme 边界光标，删除和移动可能拆开组合字符、Emoji 序列或其他用户感知字符；
2. 多行/自动换行没有稳定的视觉上下移动、期望列、Home/End 与跟随光标的 viewport；
3. Completion、History 与普通编辑对方向键的优先级不完整，弹层可能吞掉编辑，历史也可能过早抢占多行导航；
4. 缺少 Delete、逻辑词移动、任意光标位置插入/删除等基本 Composer 行为；
5. Paste 与普通按键共用截断式字符串处理，8,192 的“code point”声明错误地把可见 Composer 结构预算施加到展开后的用户内容，又与 Java 提交边界的 UTF-16 长度和 stdio 64 KiB 单行预算不一致；静默截断会把未提交内容错误地伪装成完整输入；
6. Slash 结果曾以通用完成提示代替可辨识的类型化结果，现有命令可见性和结果投影证据不足以覆盖真实交互路径。

因此旧 Commit 的测试不能证明纠错后的产品契约，旧 G3-G6 证据仍保留为历史记录，但不能证明本 ADR。

## 2. `ComposerState` 与纯 Reducer

`cc-java-tui` 使用项目自有 `ComposerState` 作为唯一编辑事实源，`app.tsx` 只把 Ink 输入/Resize 映射为 Action、渲染 Projection，并在 Reducer 产出可提交候选后调用 stdio。Runtime、Session、Permission、Context 与 Tool 的权威仍在 Java；Composer 状态不得进入 Canonical Transcript、Session JSONL 或 Checkpoint。

```text
ComposerState(
  text,
  cursorGrapheme,
  preferredVisualColumn,
  viewportTop,
  viewportHeight,
  historyEntries,
  historyIndex,
  historyDraft,
  completionCandidates,
  completionIndex,
  pastePayloads,
  nextPasteOrdinal,
  validationCode
)

ComposerAction = InsertText | MoveLeft | MoveRight | MoveUp | MoveDown |
                 MoveHome | MoveEnd | MoveWordLeft | MoveWordRight |
                 Backspace | DeleteForward | HistoryPrevious | HistoryNext |
                 CompletionPrevious | CompletionNext | AcceptCompletion |
                 Paste | Resize | Submit | Clear

reduceComposer(state, action, layout) -> ComposerTransition
ComposerTransition = Updated(state) | SubmitReady(state, expandedText) |
                     SubmissionRejected(state, code)
```

### 2.1 光标、编辑与 Unicode

- `cursorGrapheme` 只能落在 Unicode grapheme cluster 边界；显示宽度、换行和列移动由同一确定性 segmentation/width adapter 计算。不得按 UTF-16 code unit 或单个 code point 拆开组合序列。
- 插入发生在当前 grapheme 边界。Backspace 删除前一个完整 grapheme；Delete 删除后一个完整 grapheme；边界操作是无副作用 no-op。
- Left/Right 每次跨一个 grapheme。Word Left/Right 按项目自有稳定规则跨越连续空白，再跨越连续 word 或 non-word grapheme；规则不依赖 locale，必须有中文、标点、Emoji 和 ASCII 混排测试。
- Home/End 移动到当前**逻辑行**首/尾；重复触发不得越过逻辑换行。提交键与换行键由 Surface 明确映射，不能依赖终端碰巧产生的不可区分序列。
- Up/Down 在由显式换行和终端宽度共同形成的视觉行之间移动，保留 `preferredVisualColumn`；目标较短时夹到末尾，后续继续移动仍尝试原期望列。横向编辑或显式 Home/End 后更新期望列。
- Resize 只重算 Layout/Viewport，不修改 text、payload 或逻辑 cursor；光标仍指向同一 grapheme 边界。

### 2.2 Completion、History 与按键优先级

按键按固定顺序路由：审批/Undo 等已有模态控制优先；其后是已打开的 Completion；再后是 Composer 视觉编辑；只有满足历史边界条件时才进入 History。

- Completion 打开时 Up/Down 只改变候选，Tab 或显式 Accept 接受选中候选；Escape 关闭。候选最多 32，来自封闭命令/参数目录，不枚举文件、Secret、Settings 正文、Tool 或 selector。
- Completion 未打开时，Up/Down 首先执行多行视觉移动。只有 Up 位于第一视觉行且无法再上移、或 Down 位于最后视觉行且无法再下移时，才分别进入 Previous/Next History。
- History 最多 100 条、仅当前进程。首次离开当前草稿时保存 `historyDraft`；回到最新位置必须精确恢复草稿及其 paste payload 所有权。历史浏览后的普通编辑脱离历史索引但保留当前文本。
- Completion 接受、History 替换、Clear 和 Submit 都通过 Reducer 原子更新，不能由多个 React ref/state 各自维护冲突副本。

### 2.3 视觉布局与 viewport

Layout Projection 按终端可用显示列生成视觉行、每个 grapheme 的显示列和 cursor row/column；CJK 宽字符、零宽组合、Emoji 与自动换行必须使用同一宽度函数。`viewportTop` 自动保持 cursor 可见，`viewportHeight` 有界；向上/下编辑和 Resize 后均夹在有效范围。渲染只能读取 Projection，不得通过渲染回调修改编辑状态。错误/预算状态显示固定本地文案与安全 code，不显示 payload 正文。

## 3. 大 Paste 的无损占位与提交

### 3.1 存储与编辑

Paste Action 先完整接收本次终端提供的文本，再决定是否折叠。小 Paste 作为普通 grapheme 插入；超过独立展示阈值的大 Paste：

1. 将原始文本按不可变 payload ID 保存在当前 `ComposerState` 所属的短生命周期内存表；
2. 在 text 中插入一个项目自有、不可与用户文本混淆的原子 paste token；UI 将其投影为固定编号、字符/UTF-8 字节桶，不渲染正文；
3. 光标移动、选择、Backspace/Delete 将 token 视为一个 grapheme-like 原子；删除 token 同时删除无人引用的 payload；
4. History/草稿必须保留 token 到 payload 的完整映射，Clear、成功提交、Session 切换、transport close 与进程退出清理 payload；
5. 禁止把原始 payload 写入日志、诊断、Canonical、JSONL、Checkpoint 或错误信息。

Placeholder 只是 Surface 展示，不是提交协议。Submit 必须在 Slash 解析和 `run.start` 编码**之前**按 text 中出现顺序解析全部 token，检查引用唯一且存在，并逐字无损展开；stale、重复所有权、伪造或孤儿 token 以固定 code 拒绝，保留原草稿和有效 payload 供用户修正。

### 3.2 显式预算与禁止静默截断

提交候选必须同时满足以下独立预算，且测试使用与生产完全相同的计数函数：

| 预算 | Corrective 值 | 计数与失败 |
| --- | --- | --- |
| Composer 可见结构 | 8,192 grapheme/token 单元 | 只约束可见编辑结构；超限拒绝该编辑 Action，不改旧状态 |
| 展开后 Unicode | 1,048,576 Unicode code point | `SUBMISSION_CODE_POINT_LIMIT` |
| Java 字符串兼容 | 1,048,576 UTF-16 code unit | `SUBMISSION_UTF16_LIMIT` |
| 展开后用户文本 UTF-8 | 1 MiB（1,048,576 bytes） | `SUBMISSION_UTF8_LIMIT` |
| 单条 stdio NDJSON 行 | 64 KiB（含 envelope） | 保持既有 reader 安全边界；大提交必须使用 3.3 原子分块扩展 |
| Paste payload 数量 | 32 | `PASTE_COUNT_LIMIT` |
| 单 payload UTF-8 | 1 MiB | `PASTE_ITEM_LIMIT` |
| 全部 payload UTF-8 | 1 MiB | `PASTE_TOTAL_LIMIT` |

8,192 **只能**约束 Composer 中可见的 grapheme 与原子 token 数量，绝不能约束或截断 placeholder 展开后的用户内容。约 1 MiB 的展开预算是 Surface/transport 的有界无损安全预算，不取代 `CTX-06` 的 model-aware 256K Token Context pipeline；提交被完整组装后仍由该权威 pipeline 决定接受、返回类型化超限拒绝，或进入既有类型化 compaction/reduction，不得为适配模型窗口而静默截断。

预算失败不得截断、部分展开、部分提交或丢弃草稿；Reducer 返回 `SubmissionRejected`，保持 text、cursor、History draft 和 payload map 不变。只有 Java/stdio 成功接受并原子提交完整输入后，Surface 才清理已提交 payload。立即 Paste+Enter 必须读取同一 Reducer transition 的最新状态，不能提交旧 React state。

### 3.3 stdio v0 原子分块提交扩展

既有 64 KiB 单条 UTF-8 NDJSON reader 上限保持不变。W4 必须在协议边缘实现项目内部 stdio v0 的原子分块提交扩展，而不是放宽单行上限：

1. `begin` 元数据携带唯一 request/input ID、展开后总 UTF-8 字节数、总 chunk 数和完整文本 SHA-256；这些字段在分配 assembly 前按 3.2 总预算和显式 chunk-count 上限校验；
2. 后续 text chunk 按从零开始的连续 ordinal 发送，每条完整 NDJSON 行均严格小于 64 KiB；chunk 只承载该 input ID、ordinal 与有界文本，不允许 Base64 或其他会改变用户文本的有损转换；
3. `commit` 仅在收到精确 chunk 数、按序拼接后的 UTF-8 字节数与 SHA-256 全部匹配时成功；Java 以严格 UTF-8 解码得到完整输入，再原子进入 Slash/steering/`run.start` 路由；
4. 每个连接最多一个 in-flight assembly，并设置有界总字节、chunk 数和完成 timeout。cancel、timeout、transport close 或 shutdown 必须清理 assembly；重复 `begin`/chunk/`commit`、重复或乱序 ordinal、缺失 chunk、未知 ID、字节数或 digest 不匹配一律 fail closed；
5. 成功 `commit` 前不得写 Session、Canonical Transcript、Checkpoint，不得创建部分 Run、排队部分 steering 或产生任何模型请求；失败只返回关联的类型化协议拒绝并保留 TUI 草稿/payload，绝不消费部分输入。

该扩展由 W4 集成 owner 实现和验证，只是内部 stdio v0 的纠错机制，不恢复 `DIST-04`，也不构成 S14 稳定机器协议承诺。

## 4. 独立 `ModelDiagnostic` 平面

现有 `ModelFailureSummary` 继续是用户可见、固定字段的 Run 终态摘要。新增 `ModelDiagnostic` 是独立、best-effort、本机可选诊断平面：不进入 `AgentRunResult` 公共构造契约、Agent Event 控制真相、stdio/TUI payload、Canonical Transcript、Session JSONL、Checkpoint、Permission 或模型 Context，也不改变重试、StopReason、退出码和 Run 成败。

### 4.1 封闭类型与关联

```text
ModelDiagnosticMode = OFF | SAFE | VERBOSE
ModelFailureStage = REQUEST_TRANSPORT | STREAM_TRANSPORT | RESPONSE_DECODE |
                    FINISH_METADATA | TOOL_ARGUMENTS
ModelFailureReason = TRANSPORT_CLOSED | NETWORK_IO | TIMEOUT |
                     INVALID_RESPONSE | FINISH_MISSING | FINISH_INCONSISTENT |
                     TOOL_JSON_INVALID | UNKNOWN
ModelDiagnosticEvent(
  schemaVersion,
  kind,
  sessionId,
  runId,
  turnNumber,
  attemptNumber,
  stage,
  reason,
  statusClass,
  receivedProviderFrame,
  emittedUserText,
  elapsedMillis,
  recordedAt
)
ModelDiagnosticSink.record(event)
```

Stage/Reason 是封闭枚举；Adapter 只能在已知 SDK 类型和本项目验证点映射，禁止解析自由文本异常来推断类别。Core/Composition 在不使用 Provider request ID 的前提下补齐 Session/Run/Turn/Attempt 关联。SAFE 只记录失败事件；VERBOSE 可额外记录固定枚举的请求/流生命周期与耗时，但不增加原始字段。OFF 是默认，且不得创建诊断目录或文件。

### 4.2 本地 JSONL、轮转和失败隔离

CLI Adapter 的可选 sink 默认写入 `${user.home}/.cc-java/diagnostics`，也可由显式可信 CLI/launcher 参数指定本机目录；路径不会投影到模型、stdio/TUI、Session 或普通失败摘要。

- 单条严格 JSONL 最大 4 KiB；超限事件丢弃并计数，不裁掉字段后伪装有效记录；
- 单文件最大 1 MiB，最多 5 个文件；仅在启动/轮转时删除超过 7 天或超过数量的本 sink 文件；
- 进程内非阻塞队列最多 256 条；满队列增加 bounded drop counter，不阻塞模型流；
- 同目录原子轮转，文件权限在平台支持时限制为当前用户；不支持的权限强化产生安全诊断并关闭 sink，不放宽为共享可读；
- open、enqueue、serialize、write、flush、rotate、prune、permission 或 close 失败必须隔离：最多关闭诊断平面并更新内存计数，绝不改变 Run 结果、重试、Session durable 顺序或 stdout 协议纯净；
- 崩溃时允许丢失尚未 flush 的 best-effort 事件，不把诊断升级成 durable Session 事实。

### 4.3 明确禁止的字段

任何模式都禁止：Prompt、Completion/流式正文、System/Instructions/Memory、Tool 名称/参数/结果、文件正文、命令/输出、Endpoint/Base URL、Header、Cookie、API Key/Token/凭证、Provider request ID、响应正文、原始 frame、原始 JSON、SDK 类型名、异常类名、stack trace、异常 message、绝对 Workspace/home/diagnostic 路径、permission selector 或 Settings 原文。`toString()`、序列化测试和错误路径必须证明这些字段在类型上不存在。

## 5. 实现 DAG 与文件所有权

实现必须在当前 dirty worktree 上保留既有修改，按以下依赖推进；每一 Wave 的 owner 在合并前只修改本表范围，公共文档由最后 Evidence owner 统一对账。

| Wave | 依赖 | 交付 | 文件所有权（计划，不表示已创建） |
| --- | --- | --- | --- |
| W0 | 无 | 本 ADR、Reopened 治理与看板 | `docs/adr/ADR-046*`、`ADR-047*`、`ADR-048*`、矩阵、PRD、技术设计、S08 Gap、progress state/dashboard |
| W1 | W0 | 单一 Command Catalog、严格安全结果 Projection | `cc-java-tui/src/slash-command.ts`、协议/命令相关 Java/TS 测试；不得改 Composer reducer |
| W2 | W0 | 纯 Composer reducer、grapheme/layout/viewport、Paste store/budget | 新 `cc-java-tui/src/input-editor.ts` 及其单元测试；不得改 Java Runtime |
| W3 | W0 | 封闭 ModelDiagnostic 类型、Adapter 映射、Core sink port、本地 JSONL sink | Domain/Core/Model/CLI 的专属 diagnostic 文件与测试；不得把字段加入 Session/stdout |
| W4 | W1+W2+W3 | `app.tsx`、stdio v0 原子分块提交、launcher 集成和真实 TTY 场景 | `cc-java-tui/src/app.tsx`、协议边缘、CLI Composition、launcher；独占 assembly/commit 与冲突文件由单一 integration owner 修改 |
| W5 | W4 | 全量验证、Demo/Gap/证据、独立 review、固定 Commit G3-G6 | README、Demo、Evidence、矩阵/PRD/技术设计/ADR/Gap/progress；只有此 Wave 可恢复 Accepted/L2 |

`app.tsx`、`slash-command.ts` 及对应大测试文件是高冲突区，不得由 W1/W2/W3 并行交叉编辑。若测试文件含字面 NUL，应改为运行时构造以保持文本 Diff，但该清理不能扩大功能范围。

## 6. Corrective G3-G6 的精确验证契约

### 6.1 聚焦确定性测试

TUI 必须新增并执行独立 editor/reducer 测试及现有集成测试，至少覆盖：

- combining mark、Emoji ZWJ/variation selector、CJK 宽度与混排的插入、Left/Right、Backspace/Delete；
- logical Home/End、word movement、显式换行与自动换行的 Up/Down、短行夹取、preferred column 恢复；
- Completion 打开/关闭与 History 边界优先级、草稿恢复、100/32 上限；
- Resize 后 cursor identity、viewport clipping、cursor-following 与错误状态；
- 大 Paste 编号、原子移动/删除、History/草稿映射、Clear/成功提交/transport close 清理；
- 多 placeholder 顺序的逐字节无损展开、伪造/stale/orphan token 拒绝、立即 Paste+Enter；
- 可见 8,192 结构单元与展开后 1,048,576 code point、1,048,576 UTF-16 unit、1 MiB UTF-8、单/总 payload 和计数边界的 exact limit / limit+1；每个超限都不发送并保留草稿，零静默截断；
- 原子分块 begin/chunk/commit 的 exact count/bytes/SHA-256 成功路径，以及重复、乱序、缺失、错 ID、digest/bytes 不匹配、第二个 in-flight、timeout/cancel/transport-close 清理；每条 NDJSON 严格小于 64 KiB，失败前无 Session/Canonical/Checkpoint 写入且无部分 Run；
- 完整组装后仍由 256K Token Context pipeline 权威接受、类型化拒绝或压缩，Slash 与 steering 只在成功 commit 后路由；未知/重复/乱序结果 fail closed，Surface 状态零 Canonical/JSONL/Checkpoint 泄漏。

ModelDiagnostic 必须以 Fake clock/filesystem/sink 与 Spring Adapter fault injection 覆盖：

- 五个 Stage 和八个 Reason（含 pre-output/post-output transport、timeout、decode、finish metadata、tool JSON）映射；
- retry 后 attempt、Run/turn 的精确关联，既有 retry/StopReason/用户摘要行为不变；
- OFF 不创建文件；SAFE 只有失败；VERBOSE 只有固定 lifecycle/耗时字段；
- 4 KiB、1 MiB、5 文件、7 天、256 queue 的边界和轮转/清理；
- 目录不可写、权限强化失败、写/flush/rename/prune/close 异常、队列溢出和崩溃尾部均不改变 Run；
- 带唯一 sentinel 的 Prompt、响应、header、endpoint、request ID、异常、路径、Tool/selector 输入不出现在 local JSONL、stderr、stdout/stdin、TUI、Session JSONL 或 `toString()`；
- strict Java/TypeScript schema 对任何意外新增诊断字段 fail closed。

### 6.2 必须执行的命令与真实场景

```powershell
npm --prefix cc-java-tui run check
.\mvnw.cmd -pl cc-java-model-spring-ai,cc-java-cli -am test
.\mvnw.cmd -pl cc-java-core -am -Dtest=S07ContextMemoryEvalTest,AgentRuntimeContextIntegrationTest,SummaryReductionCoordinatorTest,MemoryRecallAndPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd clean verify
.\mvnw.cmd javadoc:aggregate -Ddoclint=all -DfailOnWarnings=true
pwsh -NoProfile -File .\scripts\TestCodejDevLauncher.ps1
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

G5 还必须在真实 TTY 可复现：多行/换行编辑、组合字符与 CJK/Emoji 移动删除、窄宽 Resize、History/Completion 优先级、大 Paste 折叠与无损提交、超限保留草稿；以及 `/`、`/help`、`/context`、`/doctor`、`/permissions`、`/compact`、steering、recovery-gated Resume。诊断场景必须分别展示 OFF、SAFE、VERBOSE，并用脱敏检查证明本地文件只有封闭字段。

G6 必须以新实现 Commit 为锚点进行独立 review，确认实现逐项符合本 ADR、没有受限源码表达、没有覆盖当前 dirty worktree 的既有修改、没有把诊断变成 Session/stdio 旁路，并同步 README、Demo、Evidence、Gap、矩阵、PRD、技术设计和看板。Commit、push 仍需分别获得明确授权。

## 7. 当前实现、验证与 Review 证据

当前工作树已实现纯 Composer reducer 与 TUI 集成、grapheme/layout/viewport、折叠且无损的 Paste payload、stdio v0 原子 begin/chunk/commit，以及独立本机 ModelDiagnostic。聚焦验证的实际结果如下：

| 范围 | 结果 | 说明 |
| --- | --- | --- |
| Maven Reactor | Domain 45；Core 172；Spring 43（2 skipped）；Tools 101（8 skipped）；CLI 227（11 skipped） | 全部 0 failures / 0 errors |
| 完整 TUI check | 111/111（9 files） | reducer、app、Slash、ack lifecycle、viewport 与协议集成通过 |
| launcher | 59/59 | Context 默认值、诊断参数与开发入口回归通过 |

Review 已发现并修复：Completion 接受时丢草稿/payload、legacy append 静默截断、可伪造 private-use paste token、History 可能保留约 100 MiB payload、regional-indicator Emoji 宽度；time source 异常可中断 Provider、合法空 assistant turn 语义回归、post-frame timeout 错分为 transport closed、原始 Session/Run ID 泄漏；sink 构造/路径/权限失败可中断 Runtime、Windows Junction/reparse 未覆盖，以及 record/close 竞争。修复后分别采用显式拒绝且无损的输入契约、不可外部伪造的 token 语义、有界 retained history、正确 grapheme width、时间源失败隔离、保留空 turn、精确 timeout 分类、临时 keyed-HMAC correlation UUID、sink 安全降级 OFF、NOFOLLOW/basic/DOS/isOther/realpath 校验，以及同步原子 record/close 与已接受队列 drain。

W4 Review 继续发现并修复：按原始 UTF-8 而非最终转义 NDJSON 行分块、socket write 即清理草稿且提交展示泄漏展开正文、viewport/cursor Projection 未真正渲染、assembly 仅惰性过期且 cancel 未清理/ID 可重放、begin/chunk/commit 使用不同 request ID 导致拒绝关联不完整。最终采用对 `encodeCommand` 结果逐行 `<64 KiB` 的 control-heavy/Unicode 安全分块、Java ack 关联与 lossless pending snapshot/拒绝恢复/安全标签、viewport-clipped visual lines 与 grapheme-identity inverse cursor、主动 scheduler expiry/cancel-close 清理/有界 tombstone，以及单一 logical request ID；该波修复后 TUI 105/105、CLI stdio 31/31。

Fresh independent review 又修复三个问题：延迟 ack 覆盖发送后新输入；assembly 期间重复 begin/inputId mismatch 可能 NPE、timer 未取消且 tombstone/correlation 不完整；soft-wrap 精确边界可能显示两个 inverse cursor。最终实现把 submitted snapshot 与新 active draft 分离、ack 只更新 History 且拒绝内容在后续编辑前恢复并合并 paste ownership、同一时刻只允许一个未 ack submit 但不丢后续按键；安全终止旧 assembly、取消 timer、tombstone 两个 ID 并关联原 logical submission，证明无模型副作用；cursor 只在权威 `projection.cursorRow` 可见。CLI stdio 聚焦 33/33、最终 TUI 111/111；独立复审确认无剩余 blocking finding。

完整 Maven Reactor 已重新全绿：Domain 45、Core 172、Spring 43（2 skipped）、Tools 101（8 skipped）、CLI 227（11 skipped），全部 0 failures / 0 errors。

## 8. Gate 状态与退出条件

- G0：Passed（复用 ADR-045 的授权与停止边界；本 ADR 未引入新的参考源码研究结论）。
- G1：Passed（本 ADR 冻结纠错范围、预算、失败语义与 L1→L2 目标）。
- G2：Passed（本 ADR 冻结独立 Composer、Paste、ModelDiagnostic、模块所有权和测试契约）。
- G3：Passed（当前工作树已完成独立 TypeScript/Java/launcher 实现；尚未固定 Commit）。
- G4：Passed（完整 Maven Reactor、TUI 111/111、launcher 59/59、聚焦 fault-injection 与 review 修复全部通过；独立最终复审无 blocking finding）。
- G5：Passed（真实 TTY 启动与 Provider、`/context`、无参数 `/compact`、折叠 Paste、raw Left/Z 光标编辑及 Shift+Enter 第二视觉行验收通过；详细结果见 Demo）。
- G6：Open（唯一缺口是尚无新的 corrective implementation 固定 Commit，不能完成 Commit-scoped 对账）。
- S08 Stage Exit：Corrective / Reopened。

G4/G5 的行为与测试证据已满足契约；取得新的固定 implementation Commit 并以该 hash 完成 G6 对账后，才可恢复 `CLI-08`、`CLI-09` L2 与 S08 Accepted。