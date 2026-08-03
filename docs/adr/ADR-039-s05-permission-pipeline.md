# ADR-039：S05 Permission Pipeline

- Status: Accepted
- Date: 2026-08-02
- Stage: S05 Permission Pipeline
- Capability IDs: `BOOT-03`、`CLI-05`、`LOOP-13`、`TOOL-03`、`PERM-01`、
  `PERM-03/04/06/07/08/09/10/11/13`、`HOOK-01`、`SEC-09`
- Current → Exit Target:
  - `BOOT-03`、`CLI-05`、`TOOL-03`、`PERM-01/03/07`、`HOOK-01`：L1 → L2
  - `LOOP-13`、`PERM-04/06/08/09/10/11/13`、`SEC-09`：L0 → L2
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`；本项目契约为 `Documented`

## 背景与目标

S04 的 `FixedPermissionGate` 已保证所有生产 Tool 经过同一 Pipeline，并实现固定
`DEFAULT/PLAN`、`Allow Once/Deny`、Print Ask→Deny 和拒绝 Tool Result。它没有规则来源、
类型化原因、Session Grant、Hard Denial 或完整 Permission Lifecycle。

S05 只把应用层 Permission 提升到可解释、可测试的 L2。它不改变 Tool Adapter 的路径/
参数安全职责，不实现持久 Session、分层 Settings、外部 Hook/MCP/Plugin、分类器或 OS
Sandbox。

## 独立契约

### 1. 模式

| 模式 | Read | Workspace Write | Process | Network / System |
| --- | --- | --- | --- | --- |
| `DEFAULT` | Allow | Ask | Ask | Hard Deny |
| `PLAN` | Allow | Deny | Deny | Hard Deny |
| `ACCEPT_EDITS` | Allow | Allow（仍须通过 Tool 安全校验） | Ask | Hard Deny |

`ACCEPT_EDITS` 只基于可信 `ToolEffect.WRITE_WORKSPACE` 自动放行；不解析
`run_command` 文本来猜测“这也是编辑”。PLAN 的用户可选入口必须进入生产装配，但不会
允许项目指令或普通规则把写入/进程重新放开。

### 2. 规则与 selector

S05 的规则是不可变值，包含：

```text
source   = STARTUP | SESSION
behavior = ALLOW | ASK | DENY
matcher  = Tool 名称 + 可信 ToolSource + 可选规范化 selector
```

- Tool-wide 规则可用于 Read/Write 等已知 Tool；`run_command` 禁止 Tool-wide Session
  Allow，只允许同一完整命令 selector。
- selector 同时绑定 Tool Definition 声明的可信 `ToolSource`；同名、同参数但来源变化时不得
  复用 Startup/Session Grant；来源只参与身份匹配，不能绕过统一 Pipeline。
- `apply_patch/write_file` 的 selector 至少包含 Tool 名称、可信来源和经过提取器规范化的
  Workspace 相对目标；不同 Tool、来源、路径或命令不得命中。
- selector 由应用代码在参数校验后从可信 Tool-specific 解析器生成；模型不能直接声明
  “此调用应匹配某规则”。无法安全生成 selector 时只能回到 Ask/Deny。
- `STARTUP` 规则由 Composition Root 显式注入，便于 Print 预授权和 Fake；本阶段不从磁盘
  Settings 加载。`SESSION` 规则只存在当前 `AgentSession`，关闭即清空。

### 3. 决策优先级

```text
Hard Denial
→ Explicit DENY rule
→ PLAN mode restriction
→ Explicit ASK rule
→ Explicit ALLOW rule（含 Session Grant）
→ ACCEPT_EDITS / mode default
→ Tool Effect default
→ User Approval
```

优先级是代码不变量而非列表迭代偶然结果。Hard Denial、显式 Deny 和 PLAN 不能被
Allow、Session Grant 或用户批准覆盖。同范围同时存在 Ask/Allow 时 Ask 胜出；无匹配规则
才使用模式和 Effect 默认。

### 4. Hard Denial 与 Protected Paths

可信 `HardDenialPolicy` 至少拒绝：

- `NETWORK_OR_REMOTE`、`SYSTEM_OR_DESTRUCTIVE`；
- 现有 WorkspaceGuard 已定义的 `.git`、Provider 本地配置、Secret/敏感路径；
- selector 生成发现的绝对路径、Traversal、Symlink/Junction 逃逸或不可解释范围。

Permission 层的预拒绝用于阻止任何批准路径，Tool Adapter 仍须在执行前再次运行自己的
安全校验，防止 TOCTOU。应用层拒绝不是文件/进程/网络 Sandbox。

### 5. 类型化决定与审批范围

`PermissionDecision` 的三值行为保持稳定，但 Gate 返回的 outcome 还必须携带固定原因、
规则来源和可选 approval scope。Approval Handler 返回：

- `ALLOW_ONCE`：只允许当前 Call ID；
- `ALLOW_SESSION(scope)`：先核对 scope 与已展示调用一致，再写入当前 Session Grant，
  随后允许本次调用；
- `DENY`：不执行并返回匹配 Call ID 的 Denied Tool Result。

Approval ID、取消、EOF、关闭和一次性竞争收敛继续复用 `StdioApprovalCoordinator` 的
Fail Closed 机制。Surface 只消费安全 Preview 和类型化 scope，不执行 Tool、不访问 Runtime
私有状态。

### 6. Permission Lifecycle

Core/Pipeline 是唯一权威，顺序为：

```text
BeforeTool
→ PermissionEvaluationStarted
→ PermissionEvaluated(initial outcome)
→ [ApprovalRequested → PermissionDecided(final outcome)]
→ Execute or Denied Result
→ AfterTool
```

无需人工审批时仍有唯一 final outcome。Policy 评估抛异常或返回非法结果时也必须 Fail
Closed 为类型化 `POLICY_EVALUATION_FAILED_CLOSED`，依次发布初始 Evaluated 与唯一 final
Decided，再产生 Denied Result；不能直接跳到通用 Internal Error。

Lifecycle 使用独立的隐私安全值对象，只含 Tool/Call ID、Effect、固定 reason、rule source、
是否交互和“是否为具体 scope”等类型化摘要；不持有原始 `ToolCall`、`PermissionOutcome` 或
完整 selector value，因此 record accessor 与 `toString()` 都不能泄露完整命令、Prompt、任意
参数、文件正文、Secret 或未经专用处理的路径。审批端口仍在 Pipeline 内部使用完整 selector，
以保证精确展示、决策核对和 Session Grant，不把该敏感值复制进可观察生命周期。

S09 才允许外部 Hook 观察/阻断；S05 只补齐内部事件和终端投影。

### 7. 拒绝恢复与防循环

Denied Tool Result 继续进入规范消息，模型可以换方案、缩小范围或最终解释。Session 按
规范化 scope 记录拒绝次数：

- 首次与第二次相同范围请求可按原策略返回 Ask/Deny；
- 第三次及以后在当前 Session 固定 Deny，不再弹出同一审批；
- 成功执行只清零同一 scope 的连续拒绝，不影响其他 scope；
- 计数只在内存中，S06 才处理崩溃/恢复，S14 才研究分类器和高级治理。

阈值 `3` 是本项目独立、可测试的产品决策，不来自参考实现常量。用户主动 Session Allow
会结束对应 scope 的拒绝循环；Hard Denial 永不因此降级。

### 8. 外部 Tool 统一入口

S05 用独立 Fake 分别声明 `ToolSource.MCP/PLUGIN/SUB_AGENT`，注册到现有 `ToolRegistry`，
并由同一个 `ToolExecutionPipeline` 验证：

```text
resolve → validate → permission → approval → execute → normalize/limit → event
```

来源不能跳过任何步骤。真实 Transport、Provider SPI、信任、命名空间与生命周期分别留在
S10、S11、S12；本阶段不创建空 Adapter 模块。

## 已实现的生产边界

S05 实现复用并演进：

- Domain：`PermissionMode` 增加 `ACCEPT_EDITS`，并新增规则来源、绑定可信 ToolSource 的
  selector、reason/outcome 与 approval response 值对象；`LifecycleEvent` 增加类型化权限阶段。
- Core：生产装配使用 `PermissionPolicy`、`DefaultHardDenialPolicy` 与
  `InMemorySessionPermissionState`；`ToolExecutionPipeline` 继续是唯一调用点，并统一处理
  Session Grant、拒绝计数、审批异常 Fail Closed 和最终 64K 字符上限。
- CLI/TUI：`HeadlessRuntimeSession` 接收显式模式和 Startup Rules；Picocli 暴露
  `--permission-mode`；stdio/TUI 支持 `allow_session` 和安全审批 scope。

真实外部 Adapter、持久规则、外部 Hook 和 OS Sandbox 仍不在本 Stage。

## 可证伪测试契约

1. 表驱动覆盖所有模式、Effect 和冲突规则，证明 Hard Deny/Deny/PLAN/Ask 优先级；
2. Accept Edits 只自动允许 Workspace Write，不自动允许 Process；
3. Session Grant 只命中同 Tool/selector，变路径、变命令、变来源或新 Session 均不命中；
4. 保护路径与 Network/System 即使有 Startup/Session Allow 也不执行；
5. Print Ask→Deny，显式 Startup Allow 可无交互执行；
6. 生命周期顺序稳定、Policy/Surface 异常均 Fail Closed 且 final 决定唯一；权限事件对象和
   `toString()` 不出现恶意命令参数或 selector value；取消/EOF/close 仍确定性拒绝；
7. 拒绝结果回传模型，相同 scope 第三次不再请求审批，模型仍可改用新 scope；
8. Fake MCP/Plugin/Sub-Agent Tool 全部经过权限和最终输出 hard ceiling；
9. 恶意 `AGENTS.md`、Tool 参数中的伪规则和 ToolSource 都不能扩大权限。

启动 Gate 证据见
[S05 Permission Gate](../evidence/S05-permission-gate-2026-08-02.md)；生产实现与验证结果见
[S05 Stage Evidence](../evidence/S05-permission-pipeline-2026-08-03.md)。

## 延后内容

- S06：Grant/拒绝状态持久化、崩溃恢复和未完成副作用；
- S08：User/Project/Local Settings、模式 CLI/Slash Command 的完整配置来源；
- S09：外部 Permission Hook；
- S10/S11/S12：真实 MCP/Plugin/Sub-Agent Adapter 与信任；
- S13：Managed Policy、OS 文件/进程/网络 Sandbox 和攻击性隔离；
- S14：自动分类、长期拒绝治理、兼容和稳定外部协议。
