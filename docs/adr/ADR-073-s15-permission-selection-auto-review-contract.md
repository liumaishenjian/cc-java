# ADR-073：S15 Permission Selection 与 Auto Review 契约

- Status: Accepted
- Date: 2026-08-17
- Stage: S15 Independent Innovation
- Feature IDs: `PERM-05`（L0 → L1）、`PERM-02/03/04/06/07/09/10`（组合回归）
- Depends on: ADR-039、ADR-052、ADR-072
- Current → Target: `PERM-05 L0 → L1`；完成 Headless 受限生产接线与离线 Fake 验证

## 决策

### 1. 用户选择与运行内核正交

Domain 新增三项 `PermissionSelection`，由纯函数映射为不可变配置：

| Selection | PermissionMode | ApprovalReviewer |
| --- | --- | --- |
| `PLAN` | `PLAN` | `USER` |
| `ASK` | `DEFAULT` | `USER` |
| `AUTO` | `DEFAULT` | `AUTO_REVIEW` |

`PermissionMode.ACCEPT_EDITS` 继续只用于历史 CLI/Settings/Session 兼容，不进入三项新选择。
Reviewer 只决定 final `ASK` 由谁收敛，不改变 `PermissionPolicy` 的 Effect、规则或 Hard Denial。

### 2. 有界审查请求与严格结果

`ApprovalReviewRequest` 只允许安全白名单：Session/Run/Call ID、Tool 名称、可信 Effect/Source、
是否具体 scope，以及最多 512 code point 的专用摘要；不携带 `ToolCall`、原始 arguments、selector
value、Prompt、源码、命令输出、路径绝对值或 Secret。所有 ID/名称、摘要和集合均在构造时限界。

`ApprovalReviewGateway.review(request, cancellationToken)` 是 Core Port。Gateway 只能返回严格
`ApprovalReviewResult`：

- `ALLOW_ONCE`：仅允许当前 Call ID；
- `DENY`：拒绝当前调用；
- `FAILURE(kind)`：`PROVIDER / TIMEOUT / PARSE / INTERNAL / CANCELLED`。

不存在自由 verdict、默认值或 `ALLOW_SESSION`。Adapter 无法精确解析时必须返回 `PARSE`，异常和
`null` 由 Core 收敛为 `INTERNAL`，当前 Tool 均 Deny。

### 3. Run-owned circuit 与 typed stop seam

`AutoReviewCircuit` 由 `ToolExecutionPipeline.createRunScope(runId)` 在每个 Run 开始时创建并绑定唯一
`RunId`，由 `AgentRuntime` 在终态关闭。默认阈值为三个连续 non-allow；严格 `DENY` 以及
`PROVIDER / TIMEOUT / PARSE / INTERNAL` failure 都累计，`ALLOW_ONCE` 清零。第三次当前
non-allow 仍返回当前 `DENY` 或 `FAILED_CLOSED`，同时标记 `stopAfterCurrentDeny`；后续请求才
返回 `CIRCUIT_OPEN` 且不得调用 Gateway。只有共享 Run token 确认取消时才传播
`CancellationException`，不产生拒绝也不计数；Gateway 自行抛出取消异常或返回 `CANCELLED`
但共享 token 未取消时按内部失败关闭，不能伪造 Run 取消。close 与
acquire/record 在线性锁内完成，关闭后返回 `RUN_CLOSED`，不保留跨 Run registry。

`ToolExecutionPipeline` 会把第三次连续 non-allow 的 stop 标记交给既有 batch 调度，`AgentRuntime`
以 `AUTO_REVIEW_CIRCUIT_OPEN` 结束该 Run；不会继续请求下一模型回合。

### 4. final ASK-only 控制链

完整生产目标顺序为：

```text
validate → pre-tool hook → PermissionPolicy → permission hook
  → final ALLOW/DENY: 原样执行或拒绝，reviewer 不运行
  → final ASK + USER: 既有 ApprovalHandler
  → final ASK + AUTO_REVIEW: AutoReviewCoordinator
       → strict ALLOW_ONCE | DENY | failure-deny/circuit stop
```

Auto Review 不重新运行或覆盖 `PermissionPolicy`，不把 Hook Allow 改成分类请求，也不能在 Hook Deny、
Hard Denial、显式 Deny、PLAN 或显式/Session Allow 后介入。自动 Allow 只形成当前调用的
`PermissionReason.AUTO_REVIEW_ALLOW_ONCE`；不得调用 `SessionPermissionState.grant`。所有失败形成
`AUTO_REVIEW_FAILED_CLOSED` 或 typed circuit stop，并保持唯一 final Permission lifecycle。

### 5. 取消与并发

Gateway 必须接收生产接线传入的同一个 Run `CancellationToken`。调用前或调用后观察到共享 token
取消时，Coordinator 抛出 `CancellationException` 交还 Run；未伴随 token 取消的异常或
`CANCELLED` 结果不得控制 Run，必须失败关闭并计入 circuit。Circuit 由一个 Run owner 关闭；
若未来安全读 Tool batch 并发调用 reviewer，acquire/failure/close 仍必须线性化且总 non-allow 上限
不能被并发超卖。

## 生产接线与范围

Headless Composition Root 在创建每个不可变 `HeadlessRuntimeScope` 时，把当前已绑定 Provider、凭证
lease 与 Run 生命周期的 `ModelGateway` 包装为 `ModelGatewayApprovalReviewGateway`，并将
`RuntimeConfiguration.approvalReviewer()` 与 `AutoReviewCoordinator` 注入既有
`ToolExecutionPipeline`。这不创建第二个 Provider client、credential lease、AgentRuntime 或重试链。

Adapter 为每次复核发送固定系统指令与 `ApprovalReviewRequest` 的白名单 JSON envelope，`toolDefinitions`
始终为空；任何 Tool Call、非精确 JSON、超长文本、Provider/timeout/运行时失败都收敛为固定失败分类。
`AUTO_REVIEW` 仅在 permission hook 后仍为 final `ASK` 时运行；Hard Denial、规则 Deny、PLAN、Hook
Deny 与既有 Allow 均保留原路径。`ALLOW_ONCE` 不写入 `SessionPermissionState`，不会形成 Session Grant。

stdio v0 接受 `selection=PLAN|ASK|AUTO`，并将 mode、reviewer、selection 三字段严格投影给 TUI。
React/Ink 的 `/permissions` 打开固定三项 picker：`Plan`、`Ask for approval`、`Approve for me`；
选择只影响下一 Run，`ACCEPT_EDITS` 继续是兼容 mode，不进入 picker。TUI 仍只消费 Runtime 事件，
不能把自动复核重新解释为用户审批；真实 Provider 的误放行率、延迟、成本与对照 A/B Eval 不在本批证据内。

## 可证伪测试

1. `PermissionSelection` 三项映射精确，`ACCEPT_EDITS` 不可由 Selection 产生；
2. coordinator 拒绝非 final ASK 输入，并证明 Gateway 零调用；
3. Hook/Policy precedence 使用现有测试保持，无 reviewer bypass；
4. strict Allow 只返回 once，Session rules/grant 数量不变；
5. strict Deny、Provider/timeout/parse/internal/null/throw 全部 fail closed；
6. 取消前、调用中和迟到 Allow 只有在共享 token 已取消时抛出 `CancellationException` 且不计数；
   未伴随 token 取消的 Gateway cancel 信号失败关闭并计数；
7. `DENY` 与固定 failure 连续累计、Allow reset；第三次当前决定携带 stop，后续才为 `CIRCUIT_OPEN`，Gateway 调用数不再增加；Run close 后为 `RUN_CLOSED`；
8. DTO 的 recent context 条目数、单项/总长度和不可变复制均有确定性回归。

## 可验证证据

- `ModelGatewayApprovalReviewGatewayTest` 覆盖无 Tool 白名单 envelope、精确 `ALLOW_ONCE`、Tool Call/
  非精确输出的 `PARSE`，以及 Provider/取消失败分类；
- `HeadlessRuntimeSessionTest` 覆盖真实 Headless Pipeline 的先读后 patch：自动允许只执行当前 patch，
  自动拒绝不写入 Workspace 且不调用交互 `ApprovalHandler`；
- `RuntimeStdioCommandHandlerTest` 经真实 Handler → Dispatcher → Settings 路径覆盖三项 selection 与
  `ACCEPT_EDITS → ADVANCED` 兼容投影；TUI 195 项回归覆盖 picker 标签、导航、单次提交、Esc 与严格协议；
- 既有 Core `AutoReviewCoordinator`、`AutoReviewCircuit` 与 Pipeline 回归覆盖 precedence、取消、失败关闭、
  circuit 和唯一 Permission lifecycle。

## Deferred gap

- 真实 Provider 的误放行率、延迟、成本和基线 A/B Eval；达到这些证据前 `PERM-05` 保持 L1，不提升 L2；
- 真实在线模型输出的对抗性/提示注入评测，以及独立 reviewer 模型或路由策略的收益评估；
- 审查请求当前只携带宿主生成的粗粒度脱敏上下文和 Tool 元数据；尚未完成能够兼顾语义判别质量与
  Prompt/源码/参数最小披露的真实 Provider 评测，不能据此声称与成熟产品等价。
