# ADR-085：S15 Run command acceptance handshake

- Status: Accepted
- Date: 2026-08-22
- Stage: S15 Independent Innovation（生产正确性修复）
- Feature IDs: `CLI-01`、`CLI-07`、`CLI-11`、`PLAN-01`、`OBS-04`（等级不变）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Capability Change: 无；S15 Exit 保持 OPEN

## 1. 问题与根因

stdio v0 之前把“命令已写入 Java stdin”和“Java 已接受一个新 Run”混为一体。TUI 在
`startRun` 返回本地 `requestId` 后立即预建 `running` Run，并仅用未来的 `run.started` 或
`protocol.error` 推断命令结果。`StdioClient` 还根据本地 `activeRunId` 猜测同一条输入应当是新 Run
还是 steering。

该推断在 Run 终态边界存在确定性竞态：Java 先把 `run.completed` 放入异步 stdout 事件队列，
随后 Handler 才执行 `finish` 并迁移到 `READY`。TUI 可能已消费终态、清除本地 active Run 并立即提交
下一条输入，而 Java 此时仍将它判为 steering。于是同一 request 在 Client 侧登记为 fresh Run、在 Java
侧登记为 queued steering；没有独立 acceptance 时，草稿 pending 可能悬挂，或 correlation 因
`steering.queued` 与本地猜测冲突而关闭 transport。普通 `run.start` 又在写 stdin 后才登记 pending，
因此还存在极快 `run.started` 先于本地 correlation 的第二个竞态。

ADR-082 的 requestId early-event 防御继续有效，但其中“本地 requestId 返回即预建 running Run”的部分被本
ADR 取代：本地发送只代表 `submitting`，Java disposition 才代表 `accepted/queued/rejected`，
`run.started` 才代表 `running`。

## 2. 受控参考研究

按 ADR-022 对授权快照中的输入队列、结构化请求 correlation、关闭恢复和重复响应防御做窄读，只提炼状态、
恢复和测试方法；未复制或翻译函数体、Prompt、错误文案、私有名称、布局、常量、Fixture 或源码字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | 活动处理与等待输入使用不同队列/展示状态；排队文本不会被冒充为已经开始的模型工作。 |
| Observed | 结构化 request 使用独立 pending registry；输入流关闭时统一终结全部 pending，而不是让 Promise 永久等待。 |
| Observed | 已解决/孤儿响应需要 tombstone 或去重边界，避免迟到响应再次驱动可变状态。 |
| Observed | 单一 writer/drain 保持 stream event 与 control response 的确定顺序。 |
| Inferred | Surface 不应从本地 busy/idle 快照推断服务端最终会接受还是排队；authority 必须返回 correlated disposition。 |
| Inferred | timeout 后恢复输入只能恢复本地草稿，不能自动重发；迟到 acceptance 必须成为显式异常状态或被安全隔离。 |
| Unknown | 参考机制是否公开稳定的 Run command accepted/queued/rejected envelope、具体 watchdog 时长和 late-ack UI 文案。 |

## 3. 独立设计决策

1. stdio v0 新增内部事件 `run.command.result`，严格绑定 `requestId + sessionId`，只携带：
   - `commandType`：`run.start`、`plan.start`、`plan.review.resolve` 或 `skill.invoke`；
   - `disposition`：`accepted`、`queued` 或 `rejected`；
   - 固定 `code`；
   - 仅 `queued` 可携带有界 `queueDepth`。
   事件不携带 Prompt、文件内容、Plan Markdown、异常正文、路径或 Provider 信息。
2. 所有产生 Run 的入口都必须确定收敛为 `accepted`、`queued`、`rejected` 或 transport terminal：普通/分块
   `run.start`、`plan.start`、会继续规划或批准执行的 `plan.review.resolve`、`skill.invoke`。纯 REJECT、
   空反馈且不产生 Run 的决定不进入该状态机。
3. Java 对 fresh Run 先让单线程 executor 接受一个受 gate 阻塞的 worker，再发布 `accepted`，最后才放行
   Runtime。因此 `run.started` 不能早于 acceptance；入队或 acceptance 传输失败时 worker 不写 Session、不请求
   模型、不执行 Tool。queued steering 先发布统一 `queued` disposition，既有 `steering.queued` 仅保留队列 UX。
4. 已解码 Run-producing 命令的应用层拒绝先发布 `rejected` disposition，再发布隐私安全
   `protocol.error` 诊断。Client 以 disposition 终结 handshake，不能依靠是否出现未来 Run 事件猜测。
5. `StdioClient` 必须在第一次 stdin write 前登记 generic pending submission；不得预判 fresh/steering。它严格校验
   disposition、Session 和 request correlation，并在 `run.started` 时绑定 Java authority Run ID。
6. TUI 状态依次为 `submitting → accepted|queued → running ↔ retrying → terminal`。`run.started` 前不得显示
   “等待模型响应”、模型 attempt 或 retry；queued 只表示 Java 持有尚未启动的内存输入。
7. durable Plan 的单次审批 UI 在发送原子 `plan.review.resolve` 前，必须先通过类型化 Session command 恢复进入
   Plan 前的权限选择；恢复未确认时 review 保持待决定。这样批准执行与其后的普通输入都不会继续误走 Plan Runtime。
8. 每个 submitting handshake 使用有界 watchdog。普通拒绝恢复原 Composer 草稿并删除 active pending；watchdog 到期则先
   恢复草稿，随后立即把 outcome-unknown transport 置为 terminal 并终止子进程，要求通过 Session recovery 决定后续动作。
   两者都不得自动调用 `startRun`、自动恢复 Plan 执行或重放副作用。transport failure/child exit 同样统一终结全部 pending，
   但 TUI 必须把已经 accepted/queued 的输入视为可能已产生副作用，不能重新放回 Composer。
9. 已经 accepted/queued、但 Runtime 尚未分配 Run ID 就失败时，Java 发布无正文的 `run.launch.failed`；Client 终结 pending，
   TUI 删除未启动 Run 并恢复 ready。它不能伪造成模型失败、重试中或 transport failure。
10. 活连接内的 rejection request 保留有界 tombstone，使迟到 `run.started` 或 terminal 不能完成其他 request、再次清空草稿或
   触发自动发送。watchdog 不等待迟到 authority：它直接关闭 transport，因此 late acceptance 只能随被终止的连接一起隔离，
   不能在同一连接继续运行成不可见 Run。
11. pending ownership 表必须覆盖并独立终结：Run submission、queued steering、durable Plan handoff、Session command、
   Provider control、file suggestions、approval/question；只有前四种 Run-producing submission 进入本 ADR handshake，
   其他 map 继续使用各自的 typed terminal。

## 4. 可证伪验证

- Java Handler 测试在 `run.completed` emitter callback 内立即提交下一输入，证明 Java 可合法返回 `queued`，随后恰好
  一个 `run.started` 和一个 terminal；这直接覆盖原竞态窗口。
- Server 测试证明已解码 Run-producing 拒绝的顺序为 `run.command.result(rejected)` 后
  `protocol.error`，且 requestId/code 精确关联。
- Client/Fake child 测试覆盖 accepted、queued、rejected、无 ack watchdog 后立即关闭、early event、disconnect 和 child exit；
  所有 pending/timer 在 terminal 或 transport close 后为零。
- Reducer/Ink 测试证明 submitting/accepted/queued 不显示模型等待或 retry，只有 `run.started` 后进入 running；watchdog/
  reject 恢复草稿且不调用第二次 `startRun`；durable `/plan` 审批先恢复原权限，launch failure 能恢复 ready。
- 真实 Java stdio 与 Ink E2E 覆盖 accepted Plan 完成和 verification 后立即普通提交，Session journal 必须出现新的
  `run.started`，且请求只执行一次。

## 5. 剩余差距

- stdio v0 仍是内部协议，不因新增 disposition 获得稳定外部兼容承诺；stable v1/daemon 继续使用自身正式契约。
- watchdog 只能识别 acceptance transport 不确定性，不能证明外部 Provider、Tool 或操作系统没有独立副作用；因此 late
  authority 必须 fail closed，不能自动重放。
- 本修复不提供真实 Provider 计划质量、跨平台安装或 S15 L4 A/B Eval，`PLAN-01`、`OBS-04` 与 Stage 状态均不提升。
