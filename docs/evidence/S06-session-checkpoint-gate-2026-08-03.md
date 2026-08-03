# S06 Session + Checkpoint 启动 Gate 证据

## 元数据

```text
Stage: S06 Session + Checkpoint
Status: Accepted（G0-G6 Passed）
Release / Commit: 0a9df85b4a2d8532826c63aa96889540369cd1e9
Reference Behavior Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Feature IDs: LOOP-14, SESSION-03/04/05/06/07/08/09/10/11, EVAL-02
Current → Exit Target Levels:
  LOOP-14, SESSION-03/04/05/06/07/09/10/11: L0 → L2
  SESSION-08: L0 → L1
  EVAL-02: L1 → L2
Owner: cc-java maintainers
Date: 2026-08-03
```

## G0：来源与授权（Passed）

- 公开输入：`REF-06` 的 Session 可观察行为、`REF-02` 的文件变更行为，以及本项目参考架构、
  PRD、技术设计和矩阵已独立表达的 S06 需求；内部存储格式不是公开行为输入。
- 授权输入：`AUTH-SRC-2026-07-29-A`，本机只读路径为 `G:\AI Cloud\claude-code-main`；沿用
  已登记的 1,902 个文件、30,382,832 字节与 Tree SHA-256
  `5f820b7a05b704a5e49cfd7747189af265def28a73227889c3ff028aeab79301`。
- Exact Revision、License、Rights Holder、候选上游关系和跨平台锁行为仍为 `Unknown`。
- 研究输出只记录 Transcript、Session 选择/分叉、活跃所有权、未完成 Tool、文件历史和显式恢复
  的职责、状态与验证方法；未包含参考函数体、Prompt、私有名称、布局、常量、内部格式或错误文案。
- 参考字节未进入仓库、依赖、Fixture、Golden Output 或发布物；停止条件见 ADR-040 和 ADR-022。

## G1：范围与可证伪目标（Passed）

S05 提供统一 Tool Pipeline、类型化 Permission final decision、进程内 AgentSession、规范 Tool Result
配对、WorkspaceGuard、Patch/Write 原子替换和 Command 进程控制。S06 退出目标固定为：

- `LOOP-14`、`SESSION-03/04/05/06/07/09/10/11`：L0 → L2；
- `SESSION-08`：L0 → L1，只实现本机单 Writer 检测与只读恢复；S14 再做生产跨平台加固；
- `EVAL-02`：L1 → L2，增加恢复后 Behavior Replay；
- `SESSION-12/13` Export/Retention 保持 L0，稳定协议、Migration、SQLite 和跨版本兼容留到 S14；
- S07 Context、S08 Settings/Slash Command 全套和 S13 OS Sandbox 不进入本 Stage。

独立行为、输入/输出、状态、失败语义和被否决方案由 ADR-041 固定。本 Gate 不提升 Capability
Level；等级只能在生产路径、测试、Demo 和 G4 实际证据同一变更通过后更新。

## G2：研究与 ADR（Passed）

- ADR-040 区分 `Observed / Inferred / Unknown`、采纳边界、有意偏离、安全边界和停止条件；
- ADR-041 固定项目自有 major 1 append-only semantic JSONL、Workspace binding、
  create/continue/resume/fork、单 Writer/Inspect、损坏策略、durable Tool pairing、recovery Gate、
  write-ahead ordinary-file checkpoint、diff/undo conflict guard 和 production surfaces；
- Core 不依赖 JSON/Path；文件 adapter 位于架构边缘。普通 lifecycle sink 与必须成功的 durable
  journal 明确分离；
- 不兼容参考内部格式，不逐 token 持久化，不自动重放副作用，不自动修改损坏 journal，不使用
  Git reset，不把租约/Permission/Checkpoint 描述为 Sandbox。

## G3-G6：当前状态

| Gate | 当前状态 | 实际证据 |
| --- | --- | --- |
| G3 独立实现 | Passed | Domain/Core/CLI/TUI 已接入项目自有 JSONL、hydrate/recovery、Tool durable pipeline、Checkpoint phase、CLI/stdio/TUI；未增加后期 Stage 能力 |
| G4 自动验证 | Passed | 聚焦 Session/Checkpoint/Behavior Replay/stdio/TUI 已通过；最终全量命令和计数记录于下文 |
| G5 Demo | Passed | `docs/demos/S06-session-checkpoint.md` 可复现 create/resume/fork/replay/checkpoint/diff/undo 与安全负例 |
| G6 退出对账 | Passed | 实现 Commit `0a9df85b4a2d8532826c63aa96889540369cd1e9` 已完成 Commit-scoped 全量复验与协调者审查；矩阵、README、AGENTS、PRD、技术设计、ADR、Demo、Gap、Evidence 和看板一致，Stage Exit Accepted |

## G4 可证伪测试契约

### 1. JSONL Schema 与往返

- major 1 首记录、单调 sequence、Unicode、有界字符串/集合/行/文件/record 数；
- System、`run.started` 原子持有的 User、仅包含 Assistant 的 `assistant.appended`、Run stop、
  Permission 安全摘要与 Tool 状态往返；Tool Result 禁止进入 Assistant record，execute=0 结果只能由
  `tool.resolved` 重建，已 started 结果只能由 `tool.completed` 重建；两者均原子持有完整有界脱敏 Result；
- 模型 chunk/delta、普通 UI 事件、API Key、端点、Provider 原始响应和未经裁剪 Tool 输出不落盘；
- unknown major、缺首记录、重复/倒退 sequence、字段类型错误、中间损坏和超限拒绝；
- 仅最后一个无换行不完整 JSON 被分类 damaged tail，返回警告且不修改原文件。

### 2. Session 选择与隔离

- Create 生成 Workspace-aware 新 ID；Continue 只选择同 Workspace 最近合法 Session；
- Resume 复用指定 ID，Workspace 错配/未知 ID/不可写恢复确定性失败；
- Fork 生成新 ID/parent，复制规范历史，后续追加不改变 source journal 字节；
- Metadata 只有非 Secret model/mode/workspace fingerprint/usage 与 lineage，普通输出不含真实路径。

### 3. Concurrent Open

- 第一 Writer 持有 OS `FileLock` 时，第二 Store Writer 与同一 Store 重复 Resume 都明确
  `SESSION_ACTIVE`；失败不能覆盖原 Writer，Inspect 可读但 run/tool/undo 被拒绝；
- 正常 close 释放 lock/channel，下一 Writer 可恢复；S06 L1 不写 PID/heartbeat metadata、不判断
  stale lease，也不主动 reclaim，异常退出仅依赖 OS 释放锁；
- 打开/解析失败释放 channel、lock 和临时资源，不留下假活跃状态；网络文件系统、多主机、
  heartbeat/stale-reclaim 与跨平台加固延期 S14。

### 4. Crash Points 与未完成副作用

以 fault injector 在以下 durable 边界中断：

```text
before checkpoint
checkpoint durable / before tool.started
tool.started durable / before execute
after execute / before tool.completed
tool.completed durable
```

断言聚合 Assistant 先于 resolved/started、execute count、journal pairing、recovery classification 和
可写 Gate；resolved/completed durable 成功后才能追加内存 Tool Result。Unknown、Invalid、Denied 走
resolved 且 execute=0；resolved/started 写失败都 fence，Assistant Call 无终结记录分类为未执行中断。
任何 started 无 completed 的 Tool 都不伪造 Result，其中 Write/Process/Network/System Tool 分类为
潜在副作用、阻止可写恢复且绝不自动重放。

最小 Core fault-injection 必须单独覆盖：Denied→resolved durable、Unknown→resolved durable、
started 写失败 execute=0/fence、execute 后 completed 写失败无内存 Result/fence、fenced Session 后续
Run 在任何 journal/model/tool 调用前拒绝，以及 run.completed 写失败返回/发布 INTERNAL_ERROR 并清理。

### 5. Checkpoint / Diff / Undo

- 现有普通文件保存 pre-image，新文件保存不存在标记；checkpoint 写失败时 Tool execute=0；
- Apply Patch/Write File 完成后输出有界 diff，Undo 原子恢复/删除，重复 Undo 幂等；
- 当前内容被用户修改、文件类型变化、Symlink/Junction、Traversal、绝对路径、敏感路径、备份损坏、
  未知 post-image 均 Fail Closed；
- Shell、进程和远端副作用明确报告不可恢复，不运行 Git reset/checkout/clean。

### 6. Behavior Replay 与协议

- Scripted Model 对恢复历史重复执行得到同消息顺序、Call ID 配对、StopReason 和恢复分类；
- CLI 默认/Create、`--continue`、`--resume`、`--fork` 均进入同一 persistent composition root；
- stdio initialize 返回安全 Session 恢复摘要，checkpoint diff/undo 命令有大小/Schema/状态机限制；
- React/Ink 只转发选择和呈现恢复状态，不直接打开文件或执行 Undo。

## G5 Demo 与 Gap

- Demo：`docs/demos/S06-session-checkpoint.md`，覆盖 Session Schema/选择/租约、Resume/Fork Behavior
  Replay、真实活动 Run Undo Gate、Checkpoint durable phase、stdio 与 React/Ink 逐项二次确认；
- Gap：`docs/gap-reports/S06.md`，明确本机 lease L1、内部协议、普通文件恢复边界，以及 S07/S08/
  S13/S14 延期能力；
- 安全负例包括未完成 Write Tool、created/completed/undo journal uncertain、用户 post-image 冲突、
  最终 TOCTOU、Symlink、备份损坏、无显式确认和 TUI 畸形 payload；所有场景都不自动重放副作用。

## 验证命令与结果

```powershell
.\mvnw.cmd -pl cc-java-core -am test
.\mvnw.cmd -pl cc-java-tools-local -am test
.\mvnw.cmd -pl cc-java-cli -am test
npm.cmd --prefix cc-java-tui run check
.\mvnw.cmd clean verify
.\mvnw.cmd "-DskipTests" javadoc:aggregate
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

聚焦结果（2026-08-03，Windows 10 Pro、Java 21、Maven Wrapper 3.9.16、Node.js 22）：

- `FileCheckpointCoordinatorTest,FileSessionStoreTest`：26 项运行，25 项通过，1 项因当前环境不能创建
  Symlink 按设计跳过；0 失败、0 错误；
- `HeadlessRuntimeSessionTest#resumeAndForkReplayIdenticalCanonicalHistoryIntoModel+realActiveRunBlocksCheckpointUndoUntilBlockingModelReturns`：2 项通过；
- Java stdio Checkpoint 聚焦：12 项通过；
- `npm.cmd --prefix cc-java-tui run check`：7 个测试文件、48 项测试全部通过；
- 生产源码 grep 未发现 `ForTest`、`test-only`、Run 测试 seam 或反射残留；活动 Run Gate 使用真实
  `application.run` + 阻塞 Fake ModelGateway 建立状态。

最终验证结果：

- `.\mvnw.cmd clean verify`：BUILD SUCCESS；Java 共运行 236 项，226 项通过、10 项按设计跳过
  （2 项真实 Provider opt-in、缺失 rg、Windows Junction/Symlink 能力等环境条件）；0 失败、0 错误；
- `npm.cmd --prefix cc-java-tui run check`：TypeScript build 通过；7 个测试文件、48 项测试全部通过；
- `.\mvnw.cmd "-DskipTests" javadoc:aggregate`：首次暴露 31 项 S06 公共契约文档警告，补齐中文
  Javadoc 后 BUILD SUCCESS；
- `pwsh -NoProfile -File .\scripts\TestCodejDevLauncher.ps1`：50 项断言通过；
- Dashboard generate/check/self-test 全部通过，矩阵为 55 项 L2、31 项 L1、107 项 L0，覆盖率
  24.35%；
- `git diff --check` 通过，仅有 Windows checkout 的 LF→CRLF 提示；
- 协调者 G6 审查发现并修复 TUI 快捷键误拦截普通小写 `c/d/u` 输入，以及 `protocol.ts` 中 3 个
  实际 NUL 字符；快捷键现仅在输入为空时接受大写 `C/D/U`，且 Diff/Undo/方向键要求面板已打开，
  `coding` 完整提交且不发送 Checkpoint 命令等回归包含在上述 48 项 TUI 测试中并通过；
- 生产源码 grep 未发现 `ForTest`/test-only/反射测试 seam；tracked diff 未发现凭证模式。唯一真实
  Provider 本地配置位于 Git-ignored `config/provider.local.properties`，审计只通过 `git check-ignore`
  确认忽略规则，未把其值复制到证据或仓库；
- 变更未读取或加入商业产品内部 JSONL、受限制源码字节、真实公司端点或私有业务数据；文档持续
  区分应用层 Permission/FileLock/Checkpoint 与 OS Sandbox。

实现 Commit `0a9df85b4a2d8532826c63aa96889540369cd1e9` 已通过 Commit-scoped 全量复验与协调者 G6
审查，G0-G6 全部 Passed，S06 Stage Exit 为 Accepted。本次只同步 Commit-scoped 验收状态，无
Capability Level 再变化：`SESSION-08` 仍为 L1，其余所列 S06 Feature 保持 L2；S07 Context、S08
Settings、S13 OS Sandbox 与 S14 稳定 Export/Retention/Migration 延期边界不变。下一步进入 S07
Context Engineering 授权研究与启动 Gate。
