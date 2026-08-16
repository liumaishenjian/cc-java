# S15 Permission 三选与受限 Auto Review Demo

- Stage: S15 Independent Innovation
- Feature ID: `PERM-05`
- Current → Target: `L0 → L1`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 双源机制为 `Observed / Inferred`；本项目契约与实现为 `Documented / Tested`

## 可独立表达的行为

`/permissions` 在 React/Ink 中打开固定三项 picker：

1. `Plan` → `PermissionMode.PLAN + ApprovalReviewer.USER`；
2. `Ask for approval` → `DEFAULT + USER`；
3. `Approve for me` → `DEFAULT + AUTO_REVIEW`。

picker 默认停在 `Ask for approval`，Enter 只提交一次，Esc 不提交。历史
`ACCEPT_EDITS` 继续可通过旧 mode 协议设置，但不会出现在 picker。

`AUTO_REVIEW` 不改变确定性 Permission Policy。只有 Hard Denial、显式规则、PLAN、Session Grant
与 permission hook 全部求值后仍为最终 `ASK` 的调用，才进入独立的无 Tool 模型回合。严格
`ALLOW_ONCE` 只放行当前 Call；`DENY`、解析/Provider/timeout/internal failure 均拒绝当前调用。
连续三次 non-allow 后，当前拒绝先形成匹配 Tool Result，再以 typed stop 结束 Run。

## 离线复验

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai,cc-java-cli -am `
  "-Dtest=ModelGatewayApprovalReviewGatewayTest,HeadlessRuntimeSessionTest,AutoReviewCoordinatorTest,ToolExecutionPipelineTest,AgentRuntimeTest,ParallelToolBatchExecutorTest,RuntimeStdioCommandHandlerTest,StdioProtocolCodecTest,SessionCommandDispatcherTest,SettingsApplicationServiceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

npm --prefix cc-java-tui run check
```

关键自动断言包括：

- Headless Fake 先读后 patch 在 AUTO allow 时写入当前 patch，且不调用交互审批；
- AUTO deny 不写 Workspace，Run 可以继续形成确定性 Tool Result；
- 共享 token 真实取消才控制 Run；伪造 cancel 信号失败关闭；
- stdio 真实经过 Handler → Dispatcher → SettingsApplicationService 后查询到一致的
  mode/reviewer/selection；
- TUI 展示精确三标签，方向键选择，快速双 Enter 只有一个 wire command，Esc 为零 command；
- 严格协议拒绝缺字段、未知枚举、额外字段及 mode/selection 冲突。

## 预期观察

- `PLAN` 的写与命令仍由既有 Policy 拒绝，模型 reviewer 零调用；
- `ASK` 继续使用用户 `Allow Once / Session / Deny`；
- `AUTO` 只收敛最终 `ASK`，Hook Allow/Deny、Hard Denial 与 Tool Adapter 安全检查不可绕过；
- 所有 Tool Call/Result ID 与批次顺序保持一一对应；
- 自动允许不创建 Session Grant，也不持久化规则。

## 证据边界

本 Demo 是离线 Fake、真实 Java composition 与 React/Ink 输入 E2E，不是在线 Provider 质量证据。
当前审查请求只提供宿主生成的有界粗粒度上下文和可信 Tool 元数据；真实 Provider 的误放行率、
语义判别质量、延迟、成本和 A/B Eval 均未完成。因此 `PERM-05` 为 L1，不提升到 L2。
