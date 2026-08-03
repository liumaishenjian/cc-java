# ADR-040：S06 授权 Session / Checkpoint 机制研究采纳边界

- Status: Accepted
- Date: 2026-08-03
- Stage: S06 Session + Checkpoint
- Capability IDs: `LOOP-14`、`SESSION-03/04/05/06/07/08/09/10/11`、`EVAL-02`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred / Unknown`；采纳边界为 `Documented`

## 背景

S05 已使 Tool 权限和审批经过统一 `ToolExecutionPipeline`，但 Session、权限状态和规范消息仍只
存在于进程内，进程中断后无法区分“已提出副作用”与“副作用已完成”。S06 要建立本项目自有
持久 Session、崩溃恢复和普通文件 Checkpoint。按照 ADR-022 与成熟核心机制研究要求，设计前
必须先理解授权快照中的职责、状态迁移和失败边界，但不能复制其实现表达或内部格式。

## 受控研究范围

本轮在仓库外只读研究了持久 Transcript、Session 身份与 Workspace 归属、最近会话选择、指定
恢复、分叉、活跃所有权、崩溃后规范链重建、未配对 Tool、文件修改历史与显式恢复等机制。
研究只提炼职责和验证方法，未复制或逐行翻译函数体、Prompt、注释、错误文案、私有类型名、
文件布局、实现常量或内部数据格式；参考字节未进入仓库、Fixture、Golden Output、依赖或发布物。

最小观察登记在
[R2026.03 授权参考源码基线](../reference-baselines/R2026.03-authorized-source.md)。

## Observed

1. 持久对话保存聚合后的语义记录，而不是把模型流的每个 token 当成规范 Transcript 条目。
2. Session 身份、Workspace 归属、元数据和 Transcript 写入是可分离职责；恢复选择需要先确认
   Workspace 范围。
3. Resume 继续同一 Session 语义，Fork 创建新身份并保留来源关系；分叉后的追加不能修改原
   Session。
4. 恢复会重建有效规范链，并把缺少对应结果的 Tool 使用识别为未完成状态；进度展示事件不等于
   规范对话历史。
5. 活跃 Session 有显式所有权/进程登记，并需要区分仍活跃与陈旧所有权。
6. 文件恢复保存 Agent 修改前状态或“不存在”标记，独立于 Git；恢复前展示差异，并由显式用户
   动作触发。

## Inferred

1. Java Runtime 必须把“可失败并隔离的观察事件”与“副作用前必须成功的 durable journal”分开；
   后者失败时应 Fail Closed，不能继续执行副作用。
2. Tool 的持久 Started/Completed 应围绕真实执行边界成对记录；恢复发现 Started 无 Completed
   时只能报告潜在副作用，不能自动再次调用 Tool。
3. 单 Writer 租约只解决同一本地 Session 的并发写入，不是 OS Sandbox、分布式锁或跨主机一致性。
4. Undo 必须 compare-before-restore：只有当前普通文件仍等于该次 Agent 已知的 post-image 时，
   才能恢复 pre-image；否则拒绝覆盖用户后续修改。
5. JSONL 损坏处理需要区分可证明是崩溃产生的最后一个不完整记录与中间损坏；后者不能进入可写
   恢复。

## Unknown

- 快照对应的准确产品版本、Revision、许可证和权利人；
- 参考内部格式的稳定性、兼容承诺和迁移策略；
- 不同操作系统、网络文件系统和多主机环境的锁语义；
- 参考文件恢复对全部文件类型、远端副作用和进程副作用的覆盖范围；
- 内部保留策略、稳定 Export 协议和长期存储限制。

这些 Unknown 不作为 S06 的假设或测试 Oracle。

## 采纳边界

S06 采纳以下机制目标，但使用本项目独立 Java/JSONL 契约和命名：

- 版本化、append-only、聚合语义 Session journal；
- Workspace-aware metadata 与 create/continue/resume/fork；
- 单 Writer 检测、显式只读恢复和陈旧租约判断；
- durable Tool Started/Completed 与未完成副作用报告；
- 写 Tool 执行前的普通文件 Checkpoint、Diff 和显式 Undo；
- 崩溃点、损坏输入、版本拒绝、分叉隔离和冲突恢复的确定性测试。

## 有意偏离与延期

- 不读取、兼容或模拟参考产品内部 JSONL；Schema、字段、错误和目录布局完全由本项目定义。
- 不采用参考私有名称、常量、锁文件格式、恢复文案、遥测标签或保留策略。
- S06 不做逐 token 持久化，也不把未经裁剪的生命周期事件、Provider 原始响应或完整命令参数
  机械写入 journal。
- S06 的并发能力只达到单机单 Writer 检测与只读恢复 L1；跨平台加固、网络文件系统和生产级
  陈旧所有权治理留到 S14。
- 稳定 Export、Retention、跨版本 Migration、SQLite 和公开机器协议留到 S14。
- Context 压缩/摘要属于 S07；分层持久 Settings 和 Slash Command 全套属于 S08；OS Sandbox
  属于 S13。
- Checkpoint 不恢复 Symlink/Junction、Shell、进程、网络或远端副作用，不执行 Git reset、clean、
  checkout 或 commit。

## 安全与隐私边界

- 用户输入、Workspace 文件、journal、锁文件和 checkpoint 均是不可信输入；读取前实施真实路径、
  普通文件、大小、Schema 和字段上限校验。
- Workspace 真实路径只用于本地身份核对，不进入普通日志、TUI 遥测或外部导出；API Key、端点、
  Secret、Provider 原始错误、未经裁剪 Tool 输出不持久化。
- 恢复和 Undo 都不能扩大 S05 Permission 或绕过 Tool Adapter 的安全校验。
- Session 恢复不是执行授权；存在未完成副作用时，默认阻止继续写入，直到用户显式接受只读检查
  或完成受支持的恢复动作。
- 应用层租约、Checkpoint 与 Undo 均不构成 OS Sandbox 或事务文件系统。

## 可证伪验证

本研究理解必须由 ADR-041 的独立测试契约证伪，至少覆盖：

1. 聚合消息与 Run/Tool 语义记录往返，证明不写逐 token 事件；
2. continue/resume 的 Workspace 约束与 fork 新 ID/原历史不变；
3. 第二 Writer 被拒绝、只读打开不获得执行能力、陈旧租约有界恢复；
4. started 前、执行中、completed 前等崩溃点，证明未完成副作用可见且永不自动重放；
5. 最后一条不完整记录可带警告只读恢复，而中间损坏、超限字段和未知 major version 被拒绝；
6. 写前 Checkpoint 失败时 Tool 不执行，Undo 对普通文件/新文件成功，对冲突和链接拒绝；
7. Fake Model 对恢复后的规范历史进行确定性 Behavior Replay。

## 停止条件

若授权范围被撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要复制参考字节、内部
格式或私有表达，立即停止使用材料并恢复隔离。本 ADR 只接受研究边界，不代表 S06 已实现，
不单独提升任何 Capability Level。
