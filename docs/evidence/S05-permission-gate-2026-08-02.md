# S05 Permission Pipeline 启动 Gate 证据

## 元数据

```text
Stage: S05 Permission Pipeline
Status: In Progress
Release / Commit: WORKTREE
Reference Behavior Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Feature IDs: BOOT-03, CLI-05, LOOP-13, TOOL-03, PERM-01/03/04/06/07/08/09/10/11/13,
             HOOK-01, SEC-09
Date: 2026-08-02
```

## G0：来源与授权（Passed）

- 公开输入：`REF-04` 官方权限模式，以及本项目 PRD/矩阵中已经独立表达的 S05 需求；
- 授权输入：`AUTH-SRC-2026-07-29-A`，只读路径按基线选择
  `G:\AI Cloud\claude-code-main`；Snapshot 文件数、字节数和 Tree SHA-256 沿用登记值；
- 授权范围和 Unknown：只允许机制学习；准确 Revision、许可证、权利人和候选上游关系仍为
  Unknown；
- 研究输出只记录规则来源、优先级、Session Grant、保护性检查、审批收敛、拒绝恢复和统一
  Tool 入口，未包含参考函数体、Prompt、私有类型、布局、常量、内部格式或错误文案；
- 研究采纳边界见 ADR-038，停止条件沿用 ADR-022。

## G1：范围与目标（Passed）

S04 提供统一 `ToolExecutionPipeline`、五类 Effect、固定 DEFAULT/PLAN、Allow Once/Deny、
Print Ask→Deny、匹配 Call ID 的拒绝结果和取消 Fail Closed。S05 退出目标固定为：

- `BOOT-03/CLI-05/TOOL-03/PERM-01/03/07/HOOK-01` L1 → L2；
- `LOOP-13/PERM-04/06/08/09/10/11/13/SEC-09` L0 → L2；
- `PERM-12` 分层持久配置仍为 L0，留到 S08/S13；
- 真实 Hook、MCP、Plugin、Sub-Agent、Sandbox 与自动分类不进入 S05。

最小独立行为和失败语义由 ADR-039 固定。本 Gate 不提升 Capability Level；等级必须等到
生产实现和 G4 实际证据后再修改。

## G2：研究与 ADR（Passed）

- ADR-038：区分参考观察、本项目采纳、有意偏离和停止条件；
- ADR-039：固定三模式、规则/selector、Hard Denial、优先级、范围化审批、Lifecycle、
  拒绝防循环、Fake External Tool 和延期边界；
- 不复制参考规则字符串格式或阈值；拒绝阈值、Java 类型和测试场景均由项目独立定义；
- Permission 明确不是 OS Sandbox，Allow 不能绕过 WorkspaceGuard 或 Tool 参数安全校验。

## G3-G6：后续状态

本文件保留 2026-08-02 启动 Gate 的历史事实；2026-08-03 的生产实现已使 G3-G5 在工作树
通过，G6 仍等待 Commit-scoped 复验和维护者验收。当前权威结果见
[S05 Permission Pipeline 工作树证据](./S05-permission-pipeline-2026-08-03.md)。

| Gate | 当前状态 | 证据 |
| --- | --- | --- |
| G3 独立实现 | Passed | Policy Kernel、Session/Modes、Approval/Surface、Recovery/Fake External 已实现 |
| G4 自动验证 | Passed | 定向与全量工作树命令记录于新证据包 |
| G5 Demo | Passed | `docs/demos/S05-permission-pipeline.md` |
| G6 退出对账 | Open | 工作树文档已对账；等待聚焦 Commit 与维护者 code review |

## G4 可证伪测试契约

### 1. 优先级属性/表驱动测试

对 Mode × Effect × Hard Denial × Deny/Ask/Allow × Session Grant 组合生成期望，验证：

```text
Hard Denial > DENY > PLAN > ASK > ALLOW > ACCEPT_EDITS/default > Effect default
```

特别断言 Session Allow 不能覆盖 Hard Denial、显式 Deny 或 PLAN；Ask 与 Allow 冲突时 Ask。

### 2. 模式与 Print

- DEFAULT：Read Allow，Write/Process Ask；
- PLAN：只允许 Read；
- ACCEPT_EDITS：Read/Workspace Write Allow，Process Ask；
- Print 中未预授权 Ask 收敛为 Deny；匹配 Startup Allow 可执行，但仍经过 validate、Hard
  Denial 和 normalize。

### 3. Session Grant

- `allow_session` 立即允许已展示调用，并允许同 Tool/selector 后续调用；
- 变 Tool、变相对路径、变完整命令、无法规范化 selector 或新 Session 均不命中；
- 关闭 Session 后 Grant 不存在；不存在持久文件写入。

### 4. Hard Denial 与注入

- `.git`、Provider 本地配置、Secret、Traversal、外部 Junction/Symlink、Network/System
  Tool 即使有 Startup/Session Allow 也不执行；
- 恶意 `AGENTS.md`、Tool 参数伪造 rule/source/effect 不改变决策；
- Tool Adapter 在 Allow 后仍再次运行 WorkspaceGuard/参数校验。

### 5. Lifecycle 与 Fail Closed

验证 `BeforeTool → EvaluationStarted → Evaluated → [ApprovalRequested] → Decided →
[Execute] → AfterTool`，且每次调用只有一个最终决定。错误 ID、重复决定、取消、shutdown、
stdin EOF、Handler close 和 Surface 异常全部拒绝并释放等待者。

### 6. 拒绝恢复/防循环

- Denied Tool Result 使用原 Call ID 回到模型，模型可换方案并最终完成；
- 同 Session/同 scope 连续第三次请求固定 Deny，不再产生审批请求；
- 新 scope 仍按正常策略评估；同 scope 成功或 Session Allow 终止连续拒绝状态；
- 不使用模型分类器作为普通 CI 前提。

### 7. Fake External Tool 统一入口

分别以 `ToolSource.MCP/PLUGIN/SUB_AGENT` 注册独立 Fake，验证：

- resolve、schema/business validate、permission、approval、execute、输出裁剪、事件顺序；
- Deny 时 execute 次数为 0；Allow 时每个 Call ID 只执行一次；
- 超大输出仍由 `ToolExecutionPipeline.ABSOLUTE_MAX_OUTPUT_CHARACTERS` 裁剪；
- 来源只进入规则/事件，不构成绕过凭据；不实现真实外部 Transport。

## 未来标准验证命令

```powershell
.\mvnw.cmd -pl cc-java-core -am test
.\mvnw.cmd -pl cc-java-cli -am test
.\mvnw.cmd clean verify
.\mvnw.cmd "-DskipTests" javadoc:aggregate
npm.cmd --prefix cc-java-tui run check
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

该段命令最初作为未来验证计划记录；实际生产验证、L2 提升和剩余 G6 边界以
[S05 Permission Pipeline 工作树证据](./S05-permission-pipeline-2026-08-03.md)为准。Stage Exit
仍未宣称 Accepted。
