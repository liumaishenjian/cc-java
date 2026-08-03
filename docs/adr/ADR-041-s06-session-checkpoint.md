# ADR-041：S06 Session + Checkpoint 独立契约

- Status: Accepted
- Date: 2026-08-03
- Stage: S06 Session + Checkpoint
- Capability IDs: `LOOP-14`、`SESSION-03/04/05/06/07/08/09/10/11`、`EVAL-02`
- Current → Exit Target:
  - `LOOP-14`、`SESSION-03/04/05/06/07/09/10/11`：L0 → L2
  - `SESSION-08`：L0 → L1
  - `EVAL-02`：L1 → L2
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`；本项目契约为 `Documented`

## 背景与目标

S05 结束时，`AgentSession` 已维护规范消息、Run/Tool 配对、事件序列和进程内 Permission 状态，
但 `InMemorySessionStore` 无法跨进程恢复；普通 lifecycle sink 的失败会被隔离，也不能承担副作用
前必须 durable 的写入。S06 要在不让 Domain/Core 依赖 Path、JSON 或 Jackson 的前提下，把
Session persistence 和 Checkpoint adapter 放在架构边缘，并接入真实 Java Headless、stdio 与
React/Ink 启动路径。

## 1. 模块与职责

```text
CLI / stdio / TUI session selection
  → Core SessionStore Port（create / continue / resume / fork / read-only inspect）
  → File Session Adapter（项目自有 JSONL + local lease）
  → AgentSession safe hydration
  → AgentRuntime canonical journal boundary
  → ToolExecutionPipeline
       permission / approval
       → durable checkpoint.created（仅写 Tool）
       → durable tool.started
       → tool.execute
       → durable checkpoint.completed
       → durable tool.completed
  → recovery report / explicit diff / explicit undo
```

- Domain 保存框架无关的 Session 选择、元数据摘要、恢复状态、checkpoint/undo 结果值对象。
- Core Port 只表达语义记录、租约和恢复操作，不暴露 `Path`、JSON node 或文件锁类型。
- 文件编码、目录权限、真实路径、原子替换和锁实现位于 CLI/本地基础设施边缘；S06 不新增空模块。
- `LifecycleDispatcher` 继续服务可失败并隔离的观察 sink；durable journal 使用独立强一致端口，
  不把持久正确性建立在可忽略 sink 上。

## 2. Session 身份、选择与 Workspace 绑定

### 2.1 操作

| 操作 | 输入 | 身份/历史 | 可写条件 |
| --- | --- | --- | --- |
| Create | Workspace + 非 Secret metadata | 新 ID、空历史 | 获得 Writer lease |
| Continue | Workspace | 选择该 Workspace 最近可恢复 Session | 版本/完整性/lease 均通过 |
| Resume | Workspace + Session ID | 继续同一 ID 和规范历史 | ID 归属 Workspace 且可写恢复安全 |
| Fork | Workspace + Source Session ID | 新 ID、复制来源当时规范历史并记录 parent | Source 可只读解析；新 Session 获得 lease |
| Inspect | Workspace + Session ID | 同一 ID 的只读投影 | 永不获得 Tool 执行能力 |

- Continue 只在同一规范 Workspace identity 中选择最近 Session，不跨 Workspace 猜测。
- Resume 不创建新 ID；Fork 必须创建新 ID，后续记录只追加到新 journal，原文件字节保持不变。
- Session metadata 至少保存 schema、Session ID、创建/更新时间、Workspace identity、Model、
  Permission Mode、parent Session ID（可选）和恢复摘要；不保存 API Key、端点或任意 Secret。
- Workspace identity 使用规范真实路径的本地绑定与稳定 fingerprint。真实路径仅保存在本机私有
  store 中，普通日志/stdio/TUI 只投影 fingerprint 或安全显示名。

### 2.2 存储位置

生产默认 store 位于 Workspace 外的用户本地私有目录，测试和 Demo 必须允许显式注入临时
storage root。Session 数据不得写进被管理仓库，也不得改变用户 Git 状态。

## 3. 项目自有 JSONL Schema

### 3.1 文件结构

- 每个 Session 一个 append-only journal；第一条必须是 `session.created`。
- 每行一个 UTF-8 JSON object，包含固定 `schemaMajor=1`、单调 `sequence`、`recordType` 和
  对应的有界 payload。
- 后续记录只追加，不原地更新历史；可变投影（最近时间、未完成 Tool、checkpoint 状态）由重放
  构建，不是第二份事实来源。
- S06 只保证读取 major 1；未知 major、缺失首记录、序列重复/倒退、字段类型错误或不变量破坏
  均拒绝可写打开。
- Schema 是内部项目格式，不是稳定外部 Export；S14 才决定迁移与长期兼容。

### 3.2 聚合语义记录

S06 journal 允许的记录类别为：

```text
session.created（可选 parentSessionId 表达 Fork lineage）
run.started（原子持有本次 User Message） | run.completed
assistant.appended（只持有聚合 Assistant Message）
permission.decided
tool.resolved | tool.started | tool.completed
checkpoint.created | checkpoint.completed | checkpoint.undo.completed
```

- `run.started` 原子持有 Run ID 与本次 User Message；不再用第二条通用 message record 重复保存 User。
- `assistant.appended` 只保存 Assistant 语义边界，一次性保存聚合文本和整批 Tool Calls，并且必须在
  任何一项 Tool 执行前 durable。它禁止保存 User、`ToolResultMessage` 和模型流 token/chunk。
- Fork 的来源关系由新 journal 首条 `session.created.parentSessionId` 表达；关闭仅释放本机 Writer
  lease，不写会阻止后续 Resume 的永久 `session.closed` 事实。
- execute=0 的 Unknown Tool、参数无效、Permission/Approval Deny 使用单条 `tool.resolved` 作为唯一
  durable 事实，原子持有完整、已规范化、有界且脱敏的 `ToolResult` 与固定未执行原因；resolved
  成功后才能追加内存 Tool Result，且绝不伪造 started。
- 最终 Allow 后，`tool.started` 在真实执行前 durable append；`tool.completed` 是已开始调用 Result
  的唯一 durable 事实，原子持有完整、已规范化、有界且脱敏的 `ToolResult`。不能先写摘要、再用
  另一条 message record 补 Result。
- `permission.decided` 只保存恢复拒绝防循环或审计所需的类型化决定、reason 和安全 scope digest；
  不保存完整命令、selector value 或审批展示正文。S06 不把 Session Grant 变成 S08 持久 Settings。
- 展示进度、流式 delta、原始 Provider 响应、完整生命周期对象和普通 telemetry 不进入规范 journal。

### 3.3 输入上限与损坏

实现必须给单行、文件总量、字符串、集合和 record 数量设置硬上限，且在分配大对象前检查文件和
行大小。解析策略：

- 最后一行没有换行且不是完整 JSON object：识别为 damaged tail，忽略该尾部、报告恢复警告；
- 最后一行虽可解析但 Schema/业务不变量无效：视为损坏并拒绝可写打开；
- 任意中间空行、损坏 JSON、超限、序列错误或未知 major：拒绝恢复，不扫描后续内容猜测修复；
- damaged tail 或未完成副作用下可以显式 Inspect；是否允许继续写由第 5 节恢复 Gate 决定；
- 不支持自动截断/修复原 journal，避免把只读恢复悄悄变成破坏性迁移。

## 4. 单 Writer 与只读恢复

- S06 L1 的可写打开只采用 Session 专属 OS exclusive `FileLock`；不写 PID、启动身份或 heartbeat
  metadata，也不根据任何不可信文本判断活跃状态。
- 同一 Session 存在 OS 锁持有者时，第二个 create/resume/continue 明确返回 `SESSION_ACTIVE`；
  同一 Store 的重复 Resume 也先由内存 Writer 表拒绝，不能覆盖或破坏原 Writer。Inspect 仍可并行
  只读解析，但其 Core 投影预先 fenced，不能调用 `AgentRuntime.run`、Tool 或 Undo。
- S06 不实现 stale lease 判断或主动 reclaim。正常 close 释放 lock/channel；异常退出依赖 OS 释放锁，
  下一 Writer 仍须重新通过 journal 重放和 incomplete-side-effect Gate。
- 该 L1 只承诺本机文件系统上的单 Writer 检测与确定性测试；网络文件系统、多主机、heartbeat、
  stale reclaim 和完整跨平台加固延期到 S14。

## 5. Tool 配对与崩溃恢复 Gate

### 5.1 执行顺序

每个 Tool Call 在聚合 Assistant durable 后进入以下唯一分支：

```text
unknown / invalid / denied
→ normalize bounded and privacy-safe ToolResult
→ tool.resolved durable（完整 Result + 固定未执行原因）
→ append the same ToolResult to in-memory canonical history

permission final allow
→ [write effect: checkpoint.created durable]
→ tool.started durable
→ execute once
→ normalize bounded and privacy-safe ToolResult
→ [write effect: checkpoint.completed durable（持有 post-image digest）]
→ tool.completed durable（原子持有完整 ToolResult）
→ append the same ToolResult to in-memory canonical history
```

- `tool.resolved` 写失败、Checkpoint 写失败或 `tool.started` 写失败时 execute 次数必须为 0，并立即
  fence 当前 Run/Session；不能调用普通 `finish` 让模型看到一个 journal 中不存在的 Tool Result。
- 恢复看到 durable Assistant Tool Call 但没有对应 resolved/started 时，分类为“未执行中断”并阻止
  请求下一模型回合；不能伪造 Result，也不能自动重试。
- fenced/read-only Session 必须在生成 Run ID、写 `run.started`、调用 Model 或 Tool 之前拒绝新 Run，
  后续尝试对 journal/model/tool 均为零调用。
- `tool.completed` 与其完整 Tool Result 必须使用同一 Call ID；只有 completed durable 成功后才能把
  同一 Result 追加到内存历史。恢复只从 `tool.completed` 重建 `ToolResultMessage`，检查每个 started
  至多一个 completed。
- Tool 已执行但 `tool.completed` 持久失败时，当前 Run 以内部失败停止，不能在内存历史伪造 Result；
  journal 保留 started 无 completed，恢复将其分类为潜在副作用并阻止可写打开。
- `run.completed` 持久失败时，返回值与唯一 `RunFinished` 必须降级为保留原计数的
  `INTERNAL_ERROR`，同时可靠释放 active run 并 fence Session；不得把原 `COMPLETED` 返回 Surface。
- Unknown/invalid/denied Tool 未开始副作用，不写 `tool.started`；它们仍产生原 Call ID 的规范
 失败/拒绝 Tool Result。
- 观察性 `BeforeTool/AfterTool` 可以保持 S05 Surface 语义，但不得替代 durable pairing。

### 5.2 恢复分类

| journal 状态 | 恢复结论 | 自动行为 |
| --- | --- | --- |
| resolved（含完整有界 Tool Result） | 未执行且已确定返回 | 仅由 resolved 重建规范 ToolResultMessage |
| started + completed（含完整有界 Tool Result） | 已执行完成 | 仅由 completed 重建规范 ToolResultMessage |
| Assistant Tool Call 无 resolved/started | 未执行中断 | fence；不得请求下一模型回合或自动重试 |
| started 无 completed，Read | 未完成只读调用 | fence 并报告；不重放、不伪造 Result |
| started 无 completed，Write/Process/Network/System | 潜在副作用 | fence；阻止可写 Run，绝不自动重放 |
| checkpoint 已创建但 Tool 未 started | 未执行副作用 | fence；保留可解释恢复记录 |
| resolved + started、completed 无 started，或 message 携带 Tool Result | journal 不变量破坏 | 拒绝恢复 |

恢复报告只说明 Session、Call ID、Tool、可信 Effect、checkpoint 可用性和固定原因，不泄露原始参数。
用户可以 Inspect、显式 Undo 受支持的文件 checkpoint，或 Fork 到新 Session 保留审计链；不能通过
普通 prompt 文字宣称“已经处理”来绕过 Gate。

## 6. File Checkpoint、Diff 与 Undo

### 6.1 创建

- 仅 `ToolEffect.WRITE_WORKSPACE` 且 Tool 提供可验证的目标文件计划时创建 checkpoint；首批接入
  `apply_patch` 和 `write_file`。
- Checkpoint 必须在 Tool execute 前 durable 完成；每个目标再次经过 `WorkspaceGuard`、敏感路径、
  Workspace containment、普通文件和 Symlink/Junction 拒绝。
- 已存在文件保存有界 pre-image、大小和 digest；新文件保存“不存在”标记。备份位于 Session 私有
  store，不修改 Git Index。
- Tool 完成后先把类型化 post-state 以本地 `POST_PREPARED` 阶段 durable 保存，再追加
  `checkpoint.completed`；post-state 必须且只能是普通文件 SHA-256 digest 或已知 `ABSENT`。
  因此 `write_file` 执行失败且目标仍不存在时仍可提交 `COMPLETED_ABSENT` 与规范 ToolResult，
  不应把“已知不存在”误判为持久化未知并 fence。
- journal 返回后按 post-state 写 `COMPLETED_PRESENT` 或 `COMPLETED_ABSENT`。journal 调用抛错时
  保留 `POST_JOURNAL_UNCERTAIN`，不猜测 append 是否成功、不写 `tool.completed`，并 fence Session。
- `checkpoint.created` 同样先写 durable pre-image 与 `CREATE_PREPARED`；journal 结果不确定时保留
  pre-image 和 `CREATE_JOURNAL_UNCERTAIN`，不得通过清理目录抹掉审计材料。
- 若执行失败后无法证明 Workspace 状态，恢复报告标记 Potential Side Effect。

### 6.2 Diff

显式 Diff 比较 checkpoint pre-image 与当前 Workspace 普通文件，返回有界、相对路径化的文本差异
和冲突状态。它不调用 Git，不读取链接目标，不在普通日志暴露 storage root 或绝对 Workspace 路径。

### 6.3 Undo

- Undo 是按 checkpoint 的显式操作，不自动 reset 整个 Workspace，也不撤销不相关用户改动。
- 已存在文件：只有当前 digest 等于该 checkpoint 的 post-image 时，才以同目录临时文件 + 原子替换
  恢复 pre-image。
- 新文件：只有当前普通文件 digest 等于 Agent 创建的 post-image 时才删除；不存在时幂等成功，内容
  已变化时拒绝。
- 当前为 Symlink/Junction、类型变化、越界、敏感路径、digest 冲突、未知 post-image 或备份损坏时
  Fail Closed。
- Undo 只能在持有 Writer lease、Session 未 fenced、没有活动 Run 且收到独立显式确认时执行；
  UI 文案或普通 prompt 不能替代该确定性 Gate。
- 修改前先写 `UNDO_PREPARED`；最终 Move/Delete 前再次执行 NOFOLLOW、realpath、普通文件与
  post digest 重检。Workspace 修改完成后写 `UNDO_APPLIED`，再 durable append
  `checkpoint.undo.completed`，最后写 `UNDONE`。
- 只有 clean `COMPLETED_PRESENT` 或 `COMPLETED_ABSENT` 可以进入 Undo；`UNDO_PREPARED` 也不能
  作为“尚未执行”而重试，因为崩溃可能发生在最终文件操作与 metadata 更新之间。
- `checkpoint.undo.completed` 调用抛错时保留 `UNDO_JOURNAL_UNCERTAIN`；因为 Workspace 可能已经
  修改，重启后 `UNDO_PREPARED`、`UNDO_APPLIED`、`UNDO_JOURNAL_UNCERTAIN` 均生成固定
  `CHECKPOINT_UNDO_UNCERTAIN` issue，阻止 Resume/Fork/Continue 选择和新 Run，只允许 Inspect 与
  人工检查，绝不自动重试 Undo。重复已提交 Undo 返回已恢复状态，不重复覆盖。
- Checkpoint list/status 直接投影完整 durable phase，不把 prepared/uncertain 压缩成
  `postImageKnown`/`undone` 布尔值。
- Metadata 目录名必须是合法 Checkpoint ID，且与 metadata ID 一致；digest 只接受小写 64 位
  SHA-256 hex，枚举在 materialize 前强制数量上限，全部 staged 文件在 Move 前 `force(true)`。
- created 尚未 durable 前的失败清理只对已知 `metadata.json`、`pre-image.bin` 普通文件做
  NOFOLLOW 精确删除；不使用递归 walk，不跟随或删除未知条目。
- Shell、进程、远端、环境变量、权限变更和链接副作用不在可恢复范围，也不伪装成成功。

## 7. Core 恢复与确定性 Replay

- File adapter 先完整验证并重放为框架无关 recovery snapshot，再由 Core 的受控 factory 重建
  `AgentSession`；外部 adapter 不能绕过 Call ID、消息顺序、Run 唯一终态和事件 sequence 不变量。
- 恢复只重建规范消息、必要 Session Permission 拒绝状态和恢复报告；普通 UI events 不作为模型历史。
- `AgentRuntime` 后续请求使用恢复后的 canonical messages；Scripted Fake Model 必须证明同一 journal
  在多次读取中产生确定顺序、相同 Call ID 配对和相同 StopReason。
- 恢复失败不得部分发布成可写 Session；lease、临时文件和打开的 channel 必须释放。

## 8. Production Surface

### 8.1 Java CLI

Picocli 增加互斥的 Session 选择：

- 默认 create；
- `--continue`：同 Workspace 最近 Session；
- `--resume <session-id>`：指定 Session；
- `--fork <session-id>`：从指定 Session 创建新 ID。

storage root 使用安全生产默认，测试可通过组合根注入；不提供 S14 稳定 Export/Retention 子命令。
Print 和 stdio 都必须使用相同 Session selection，不得只有测试桩支持恢复。

### 8.2 stdio / TUI

内部 stdio v0 的 initialize 结果增加安全 Session ID、open mode、parent、read-only/incomplete/warning
摘要；增加显式 checkpoint list/diff/undo 命令与有界结果。React/Ink 只负责参数转发、恢复警告和
Undo 确认/展示，不直接读取 Session 文件或执行恢复。TUI 通过 `C` 请求列表、方向键选择、`D` 请求
Diff、`U` 打开针对当前 Checkpoint ID/相对目标的确认面板；只有大写 `Y`（Shift+Y）被编码为
`confirmed=true`，小写 `y` 不执行 Undo，`N`/Esc 取消。Checkpoint payload 在 TUI 边界严格校验
字段白名单、ID、相对路径、完整 phase、undoable 与 phase 一致性、条目数、status、文本/消息上限和
控制字符。

这仍是内部 v0，不是 S14 稳定公共 JSON/JSONL API，也不提前实现 S08 `/resume` 等 Slash Command。

## 9. 被否决方案

- **把 JSONL sink 挂到 LifecycleDispatcher**：sink 异常被故意隔离，无法保证副作用前 durable；否决。
- **逐 token/chunk 持久化**：放大写入和恢复复杂度，且不是规范语义历史；否决。
- **依赖 Git stash/reset/checkout**：会触及用户不相关工作区状态，也不能覆盖非 Git Workspace；否决。
- **自动重跑未完成 Tool**：无法证明上次副作用是否已发生；否决。
- **自动截断损坏 journal**：把恢复读取变成破坏性迁移；S06 否决。
- **SQLite 或新空基础设施模块**：超出当前最小需求；否决。
- **兼容参考内部格式**：来源和兼容承诺未知且违反独立重实现边界；否决。

## 10. 可证伪测试契约

1. JSONL 正常 round-trip、Unicode、多 Tool、聚合 Assistant、大小边界与不含 chunk；
2. create/continue/resume/fork、Workspace 错配、最近选择与 fork 原 journal 字节不变；
3. 第二 Writer、同 Store 重复 Resume、只读 Inspect、close 后锁释放和打开失败资源释放；
4. unknown major、缺首记录、序列错误、中间损坏、超限、damaged tail 分类；
5. checkpoint 前失败、started 后崩溃、Tool 执行后 completed 前崩溃、completed 正常路径；
6. 未完成副作用阻止可写 Run，Fake execute count 证明恢复不自动重放；
7. 已存在文件和新文件 checkpoint/diff/undo、重复 undo、用户改动冲突、Symlink/Junction、敏感路径；
8. Scripted Model 从恢复历史继续并保持 Call ID、消息顺序和唯一终态；
9. CLI Print/stdio 与 TUI 参数转发进入真实 persistent composition root；
10. Session 文件、日志、stdio/TUI 事件不出现 API Key、端点、完整命令、provider 原始响应或
    checkpoint storage 绝对路径。

启动 Gate 见 [S06 Session + Checkpoint Gate](../evidence/S06-session-checkpoint-gate-2026-08-03.md)。
实现、Demo、Gap 和 G4-G6 证据只能在实际验证后更新。

## 11. 延后内容

- S07：Context usage、完整 Turn 淘汰、摘要和压缩；
- S08：分层持久 Settings、完整 Session/Context Slash Commands；
- S13：OS 文件/进程/网络 Sandbox；
- S14：稳定 Export、Retention、Migration、SQLite 评估、跨平台/网络文件系统 lease、公开机器协议。
