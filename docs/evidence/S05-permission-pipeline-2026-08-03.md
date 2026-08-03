# S05 Permission Pipeline Stage Exit 证据

## 1. 结论

- Stage：`S05 Permission Pipeline`
- 状态：`Accepted`（G0-G6 Passed）
- 被测实现：`f7b7137081e2d85417fa5965835d4c014e514dac`
- 参考基线：`R2026.03`
- 授权参考快照：`AUTH-SRC-2026-07-29-A`
- Feature IDs：`BOOT-03`、`CLI-05`、`LOOP-13`、`TOOL-03`、
  `PERM-01/03/04/06/07/08/09/10/11/13`、`HOOK-01`、`SEC-09`
- 证据分类：`COMMIT_VERIFIED`
- 验证日期：`2026-08-03`

上述 Feature 已按 ADR-039 的 S05 退出目标达到 L2。实现 Commit
`f7b7137081e2d85417fa5965835d4c014e514dac` 已完成 Commit-scoped 全量复验、文档对账和维护者
code review，G0-G6 全部通过，S05 Stage Exit 为 Accepted。

## 2. G0-G6

| Gate | 结果 | 证据摘要 |
| --- | --- | --- |
| G0 来源与边界 | Passed | REF-04、AUTH-SRC-2026-07-29-A、Unknown 和停止条件已在基线、ADR-022/038 登记 |
| G1 范围与契约 | Passed | ADR-039 固定三模式、规则优先级、ToolSource-bound selector、Session Grant、Hard Denial、Lifecycle 和延期项 |
| G2 研究与 ADR | Passed | 只采纳职责/状态/边界/验证方法；Java/TypeScript 类型、阈值、错误语义和测试场景独立形成 |
| G3 实现 | Passed | Domain 类型、Policy Kernel、Session 状态、Pipeline、Headless/stdio/Picocli/TUI 与 Fake External 路径均已实现 |
| G4 自动验证 | Passed | S05 定向 Java、Spring AI、TUI、全量 Maven、Javadoc、Dashboard 与 diff 检查均通过并记录于本文件 |
| G5 Demo | Passed | `docs/demos/S05-permission-pipeline.md` 提供可复现命令、正常路径、负例、实测结果和事实边界 |
| G6 退出对账 | Passed | 实现 Commit 已完成全量复验与维护者 code review；矩阵、README、AGENTS、PRD、技术设计、ADR、Demo、Gap、Evidence 和看板一致，Stage Exit Accepted |

## 3. ADR-039 九项可证伪契约

| # | 契约 | 自动证据 |
| --- | --- | --- |
| 1 | 模式、Effect 和冲突规则固定优先级 | `PermissionPolicyTest` 覆盖规则顺序、Hard Denial、PLAN、Ask、Allow 与 Effect Default |
| 2 | Accept Edits 只允许 Workspace Write | `PermissionPolicyTest.acceptEditsOnlyAllowsWorkspaceWriteAndNeverOpaqueProcess` |
| 3 | Session Grant 同 Tool/selector/source，变范围或新 Session 不命中 | `PermissionPolicyTest.sessionGrantMatchesExactToolAndSelectorOnly`、`S05PermissionPipelineTest.allowSessionAppliesOnlyToSameScopeAndNewSessionDoesNotInherit`、stdio 双 Patch 场景 |
| 4 | Protected Paths 与 Network/System 不能被 Allow 覆盖 | `PermissionPolicyTest.hardDenialBeatsRulesSessionGrantPlanAndApprovalPath`、`HeadlessRuntimeSessionTest.hardDenialBlocksProtectedPathDespiteStartupAllowAndApproval`、`WorkspaceWriteHardDenialTest`（平台允许时含 Symlink 逃逸） |
| 5 | Print ASK Fail Closed；Startup Allow 无交互执行 | `HeadlessRuntimeSessionTest.nonInteractiveApprovalDeniesPatchWithoutChangingWorkspace` 与 `startupAllowExecutesRealPatchWithoutInteractiveApproval` |
| 6 | Lifecycle 稳定、Policy/Surface/close/cancel Fail Closed、final 唯一且事件隐私安全 | `S05PermissionPipelineTest.stablePermissionLifecycleHasOneFinalOutcome`、`policyEvaluationFailureFailsClosedWithInitialAndOneFinalOutcome`、`approvalSurfaceFailureFailsClosedWithOneFinalOutcome`、`permissionLifecycleDoesNotExposeCommandArgumentsOrSelectorValue`、`StdioApprovalCoordinatorTest` |
| 7 | 拒绝回传、第三次固定拒绝、新 scope 可恢复 | `AgentRuntimeTest.returnsDeniedToolResultToModelWhenApprovalRejectsAskDecision`、`S05PermissionPipelineTest.thirdRepeatedScopeDenialDoesNotRequestApprovalAndNewScopeStillDoes` |
| 8 | Fake MCP/Plugin/Sub-Agent 统一 Pipeline 与输出上限 | `S05PermissionPipelineTest.fakeExternalSourcesUseSamePermissionAndAbsoluteOutputCeiling` |
| 9 | AGENTS/伪规则/伪来源不能提权 | `PermissionPolicyTest.sourceAndModelSuppliedPseudoRulesCannotExpandPermission`、`HeadlessRuntimeSessionTest.sensitiveReadReturnsCorrectableErrorAndProjectInstructionsCannotElevateIt` |

## 4. Review Finding 修复

- Session Grant selector 现同时绑定 Tool 名称、可信 `ToolSource` 和规范化值；同名同命令从
  BUILT_IN 改为 MCP 时不返回 `SESSION_GRANT`。空字符串是合法 Tool-wide selector，校验
  文案不再误称“selector 为空”。
- `SpringAiModelGateway` 的 `receivedOutput` 现在表示失败前是否收到过任意 raw Provider
  response：完全空流为 false，metadata-only/result-null raw response 为 true；无调用的
  `invalidResponse(String)` overload 已删除。
- Policy 评估抛异常或返回非法结果时，Pipeline 现在类型化收敛为
  `POLICY_EVALUATION_FAILED_CLOSED`，发布初始 Evaluated 与唯一 final Decided，不调用审批或
  Tool；不再跳过 Permission Lifecycle 返回通用 Internal Error。
- Permission Lifecycle 事件改用独立安全摘要，不再持有原始 `ToolCall`、完整
  `PermissionOutcome` 或 selector value；审批端口内部仍使用完整 selector。恶意命令 secret
  不出现在四种新增事件的对象图公开字段或 `toString()`。

## 5. Commit-scoped 验证结果

环境：Windows 10 Pro、Java 21、Maven Wrapper 3.9.16、Node.js 22。

### 定向验证

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am `
  "-Dtest=SpringAiModelGatewayTest,OpenAiStreamingContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：17 项通过，0 失败，0 跳过。

```powershell
.\mvnw.cmd -pl cc-java-core -am `
  "-Dtest=PermissionPolicyTest,S05PermissionPipelineTest,ToolExecutionPipelineTest,AgentRuntimeTest,FixedPermissionGateTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：47 项通过，0 失败，0 跳过；新增 2 项分别证伪 Policy 评估异常绕过 final 生命周期、
以及完整命令/selector value 泄露到 Permission 事件对象或 `toString()`。

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest,RuntimeStdioCommandHandlerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：21 项运行，19 项通过，2 项因当前环境没有 `rg` 或不能创建 Symlink 按设计跳过。

### 全量验证

```powershell
.\mvnw.cmd clean verify
```

结果：成功；Java 共运行 199 项，190 项通过、9 项按设计跳过（2 项真实 Provider opt-in，
2 项当前环境无 `rg`，1 项 Windows Junction 权限策略，3 项当前 Windows 无 Symlink 权限，
1 项 CLI 高级搜索因无 `rg`）。0 失败、0 错误。

```powershell
npm.cmd --prefix cc-java-tui run check
```

结果：成功；7 个测试文件、40 项测试全部通过。

```powershell
.\mvnw.cmd "-DskipTests" javadoc:aggregate
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

结果：全部成功。Javadoc 首次运行暴露 62 项新 S05 公共契约说明缺失，补齐中文构造器、
枚举、参数和返回值文档后重新运行成功；看板已生成且 check/self-test 通过；diff check 只
报告 Windows checkout 的 LF→CRLF 提示，没有空白错误。

## 6. 安全与来源审计

- 本次 Git diff 未加入 API Key、Provider 本地配置、真实公司端点、私有业务数据或受限制
  源码字节；本机 Git-ignored `config/provider.local.properties` 含真实凭证，审计只确认其未被
  跟踪或纳入 diff，未读取、复制或记录其值；
- Permission selector 不保存 Prompt、文件正文或 Secret；Lifecycle/stdio 只投影类型化安全
  scope 和专用预览；
- Tool 参数、仓库内容和模型输出不能构造 Startup/Session Rule，也不能伪造可信 ToolSource；
- Hard Denial 后 Tool 不执行；Allow 后本地文件 Tool 仍由 WorkspaceGuard 复验 realpath、
  敏感路径和 TOCTOU；
- 当前没有 OS Sandbox，证据与能力声明未把应用层 Permission 描述为隔离能力；
- 真实 MCP/Plugin/Sub-Agent、外部 Hook 和 S06+ 未提前实现。

## 7. 剩余差距

详细差距见 `docs/gap-reports/S05.md`。S05 已无退出阻塞项；剩余能力包括持久
Session/Checkpoint（S06）、分层 Settings（S08）、外部 Hook（S09）、真实外部 Tool Adapter
与信任（S10-S12）以及 OS Sandbox（S13）。下一步进入 S06 授权研究与启动 Gate，当前不把
这些能力描述为已实现。
