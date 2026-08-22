# ADR-082：S15 durable Plan execution Run 的原子 Surface 投影

- Status: Accepted
- Date: 2026-08-22
- Stage: S15 Independent Innovation（Batch 7）
- Feature IDs: `PLAN-01`（保持 L1）、`CLI-01/06`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Capability Change: 无；S15 Exit 保持 OPEN
- Builds On: ADR-078、ADR-080

## 1. 故障与边界

`plan.review.resolve` 在 Java 中先完成 durable approval 与执行任务入队，再发布
`plan.execution.accepted`；executor 线程可能先发布 `run.started`。旧 Ink 路径只把 review picker
标记为 submitted，没有在 `resolvePlanReview` 返回 execution requestId 后立即建立 `RunView`，因此合法的
提前 `run.started` 无法按 requestId 关联。Reducer 把该内部投影错误升级为 transport failure，界面又把所有
`failed` phase 固定显示为“连接已关闭”，导致真实执行已经进入 `EXECUTING` 并执行 Tool 时，Surface 仍误导用户退出。

Java Runtime、stdio authority 与 Ink projection 是三个边界：Java 决定执行是否被接受；stdio client 校验
Session/request/run 协议；Reducer 只维护显示投影。Reducer 关联错误不能伪造 transport 已关闭，Surface 也不能
依赖 executor 与 stdio event emitter 的线程调度顺序。

## 2. 受控参考研究

按 ADR-022 对 `AUTH-SRC-2026-07-29-A` 做窄读，只核对 Plan 退出 picker、permission restore、keep/clear
实现查询、Plan 恢复与 transport/session 状态职责。未复制或翻译函数体、Prompt、文案、私有名称、文件布局、
常量、Fixture 或字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | Plan picker 先收敛批准/拒绝与执行期权限选择；UI 不直接执行 Tool。 |
| Observed | keep-context 与 clear-context 都在交互决定中明确化；clear 会安排新的实现查询，而不是把批准计划丢弃。 |
| Observed | Resume 恢复 Plan 关联，Fork 创建独立可写身份；恢复不等于副作用重放。 |
| Observed | transport/session 活动状态与 Plan/permission UI 状态是分离职责。 |
| Inferred | Surface 应在签发会产生新 Run 的控制命令时先登记本地 correlation，再允许异步 Run 事件消费；交互失败必须回滚该投影。 |
| Unknown | 参考产品是否公开稳定 requestId/runId 协议、全部迟到事件策略和所有 transport/crash interleaving。 |

下述 requestId/runId reducer、rollback action、错误文案和测试均为 codej 独立设计。

## 3. 决策

1. `StdioClient.resolvePlanReview` 对 `APPROVE_AUTO`、`APPROVE_USER`、`CONTINUE_PLANNING` 在写 stdin 前
   预登记唯一 pending requestId；写入失败同步回滚。`REJECT` 不登记新 Run。
2. Ink 在 `resolvePlanReview` 成功返回 requestId 的同一同步输入处理内立即 dispatch `run.submitted`，然后才
   等待任何 `plan.execution.accepted`、`run.started`、Tool、Model 或终态事件。AUTO/USER 使用明确 execution
   label；feedback 使用同一 planId 的 planning label。
3. picker 在发送前先以 ref 标记 submitted，阻止 React rerender 前的重复 Enter；同步发送异常恢复 picker。
   `protocol.error` 在 `run.started` 前到达时删除未开始的 optimistic Run 并恢复原 review/feedback；不得残留
   幽灵 Run。REJECT 始终不创建 Run。
4. `plan.execution.accepted` 与 `run.started` 的先后顺序都合法；Surface 只依赖预登记 requestId 和随后绑定的
   runId。普通 run、steering 与 Plan feedback 继续复用同一 `run.submitted → run.started → terminal` 投影模型。
5. 未知 requestId、错配 runId、重复或迟到的 Run/Tool/terminal event 被安全忽略并产生有界 projection notice；
   它们不得改变 transport phase、不得完成当前其他 Run，也不得把内部错误显示为连接关闭。
6. 只有 `StdioClient.onFailure` 的真实 decode/authority/child transport failure 才 dispatch `transport.failed`。
   真实 transport failure 会移除尚未获得 runId 的 optimistic submission，并把已经 started 的本地 Run 标为
   `transport_closed`，同时保留明确连接失败 UI。
7. Resume/成功 Session 切换、child exit、shutdown 与 transport failure 清理 pending Plan correlation；
   EXECUTING restart 仍由 Java `PLAN_EXECUTION_RECOVERY` gate 决定，TUI 不自动重放。

## 4. 可证伪验证

- Reducer：unknown request、runId mismatch、迟到 Tool/terminal 不改变 transport 或其他 Run；submission rollback
  无幽灵行；真实 transport failure 与 projection notice 分离。
- 真实 Ink：Plan review 默认 Enter 后立即收到提前 `run.started`，再收到 accepted、多个 Tool、Model 与完成；
  execution 行可见且终态正确，无“无法关联”或“连接已关闭”。
- 分支：APPROVE_USER、AUTO、Tab KEEP/CLEAR、REJECT、feedback、同步提交异常、协议拒绝、执行 failed、cancelled
  和真实 transport failure。
- 真实 Java stdio 与安装副本 E2E 必须通过 `AgentTui → reducer → render` 断言 Tool、final text、verification 与终态，
  不能只观察 raw protocol event。

`PLAN-01` 继续保持 L1：真实 Provider 计划/执行质量、跨平台安装、完整 crash/remote interleaving 与 L4 A/B Eval
仍未完成，S15 Exit 保持 OPEN。
