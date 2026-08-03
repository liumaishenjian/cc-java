# S05 Permission Pipeline Demo

- Stage: S05 Permission Pipeline
- Feature IDs: `BOOT-03`、`CLI-05`、`LOOP-13`、`TOOL-03`、`PERM-01/03/04/06/07/08/09/10/11/13`、`HOOK-01`、`SEC-09`
- Current → Target: ADR-039 所列 L0/L1 → L2
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制 `Observed / Inferred`；本项目实现与测试 `Documented / Observed`

## 1. Demo 证明什么

本 Demo 使用独立编写的 Fake Tool、Scripted Model 和临时 Workspace，通过真实 Domain/Core、
Headless Runtime 与 stdio Adapter 验证：

```text
可信 ToolDefinition + 已校验参数
→ 生成 ToolName + ToolSource + selector
→ 固定优先级 PermissionPolicy
→ 可选 Allow Once / Allow Session / Deny
→ 唯一 PermissionDecided
→ Execute 或匹配 Call ID 的 Denied Tool Result
→ 最终输出硬上限与 AfterTool
```

可核验行为包括三种模式、冲突规则优先级、同 scope Session Allow、变来源不命中、Protected
Paths/Hard Denial、Print Fail Closed、第三次重复拒绝不再弹窗，以及 Fake
MCP/Plugin/Sub-Agent 不能绕过 Pipeline。

## 2. 前置条件

- JDK 21；
- 仓库 Maven Wrapper；
- Node.js 22（TUI 回归）；
- 不需要网络、API Key、真实模型或外部 Tool Server。

## 3. 运行命令

在仓库根目录执行核心 Permission 场景：

```powershell
.\mvnw.cmd -pl cc-java-core -am `
  "-Dtest=PermissionPolicyTest,S05PermissionPipelineTest,AgentRuntimeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

执行 Headless/stdio 真实装配场景：

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest,RuntimeStdioCommandHandlerTest,StdioApprovalCoordinatorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

执行终端 Approval UI 回归：

```powershell
npm.cmd --prefix cc-java-tui run check
```

## 4. 可核验观察点

| 场景 | 预期证据 |
| --- | --- |
| 固定优先级 | Hard Denial > Deny > PLAN > Ask > Allow > Mode/Effect Default，不受规则列表顺序影响 |
| Accept Edits | `WRITE_WORKSPACE` 自动 Allow；`EXECUTE_PROCESS` 仍 Ask |
| Session Allow | 第一次审批后同 Tool、同 ToolSource、同 selector 不再审批；变命令、变来源或新 Session 不命中 |
| Protected Paths | `.git/config` 即使 Startup Allow + Accept Edits 仍 Deny，文件不变且不请求审批 |
| Print | 默认拒绝 ASK；匹配 Startup Allow 可无交互执行真实 Patch |
| Lifecycle | 每次调用有一个 final `PermissionDecided`；Policy/Surface 异常分别收敛为类型化 Fail Closed Deny 且不执行 Tool |
| 事件隐私 | Permission 事件只含 Call ID、Tool、Effect 和类型化决定摘要；恶意完整命令与 selector value 不出现在事件对象或 `toString()` |
| 拒绝恢复 | Denied Result 保持 Call ID 回传；同 scope 第三次固定拒绝，新 scope 仍审批 |
| Fake External | MCP/Plugin/Sub-Agent Deny 时执行 0 次；Allow 时执行 1 次；超大结果裁到 64,000 字符 |
| 注入负例 | `AGENTS.md` 和 Tool 参数中的伪 rule/source/effect 不能扩大权限 |

2026-08-03 工作树初次实测与 lifecycle review 修复后的最终测试数字统一记录在 S05 Stage
Evidence；本 Demo 不复制可能随回归新增而变化的计数。

## 5. 事实边界

- `STARTUP` 规则当前只能由 Composition Root 编程注入；仓库尚未提供用户可编辑的规则文件
  或 CLI 规则表达式，分层 Settings 属于 S08；
- Session Grant 和拒绝计数只存在当前进程内，关闭 Session 即清除；持久化与崩溃恢复属于
  S06；
- Fake MCP/Plugin/Sub-Agent 只证明统一入口，不代表真实 Adapter、Transport、认证或信任 UX
  已实现；这些属于 S10-S12；
- S05 Lifecycle 是内部只读事件，不是外部 Hook DSL；S09 才提供可配置 Hook；
- Permission、Protected Paths 和 Tool Adapter 的应用层校验都不是 OS Sandbox；文件、进程和
  网络隔离属于 S13。
