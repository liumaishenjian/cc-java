# ADR-080：S15 Plan Evidence Gate 与安装包构建身份闭环

- Status: Accepted
- Date: 2026-08-21
- Stage: S15 Independent Innovation（Batch 5）
- Feature IDs: `PLAN-01`、`CLI-01/06`、`DIST-02/06`
- Reference Baseline: `R2026.03`
- Authorized Snapshot: `AUTH-SRC-2026-07-29-A`
- Capability Change: 无；`PLAN-01` 保持 L1，S15 Exit 保持 OPEN

## 受控研究结论

本批仅对 Plan 执行完成、验证、TUI 键盘交互、launcher/build/version 与 transport/crash recovery
做新的只读机制核对；没有复制或翻译函数体、Prompt、文案、私有名称、布局、常量、Fixture 或字节。

| 分类 | 最小机制结论 |
| --- | --- |
| Observed | Plan 批准只允许开始实现；执行与后续验证是不同生命周期，验证可由独立控制路径触发 |
| Observed | 交互 picker 以真实键盘选择收敛批准模式和 keep/clear；UI 不直接执行 Tool |
| Observed | Resume/Fork 恢复 Plan 关联，崩溃恢复不表示副作用重放；transport 有独立关闭与失败状态 |
| Observed | 构建版本是发行入口的固定输入，开发构建与可更新发行构建需要可区分 |
| Inferred | codej 必须把 approved execution brief、workspace revision、预期证据和真实引用形成 durable Gate，并让普通 Agent `COMPLETED` 只表示 Run 停止，不自动表示 Plan 完成 |
| Unknown | 参考产品默认是否强制验证、是否持久保存逐项 evidence ledger、全部平台 launcher 与断线语义 |

`PlanEvidenceLedger` **明确是 codej 独立增强**，不是直接观察到的参考类型、格式或默认策略。

## 独立设计

1. 规划期新增唯一受控 `declare_plan_evidence` Tool，只声明有界 `DELIVERABLE` 相对路径或
   `VERIFICATION` Tool 名；它写 Session-owned Ledger，不写 Workspace、不接受命令、不解析 Markdown。
2. Ledger 绑定 `sessionId/planId/approved revision/ExecutionBrief digest/workspace digest`；journal 与
   manifest 保存同一完整值，引用只含相对路径或 callId、SHA-256、封闭 reason 和时间。
3. 执行 Run `COMPLETED` 后，确定性验证器通过 WorkspaceGuard 验证普通文件，并从 canonical
   ToolResult 查找同名成功结果。模型 prose、Markdown checkbox、finalText 与错误输出都不是证据。
4. required evidence 全部 PASSED 或独立 typed user skip 后才写 `COMPLETED`；否则写
   `NEEDS_VERIFICATION`。skip 必须绑定具体 requirement 和 `decision-*`，durable 且可审计。
5. Evidence Gate 不改变 PlanArtifact/ExecutionBrief CAS、原子 approval/enqueue、Permission/
   AutoReview/Hard Denial、预算、Hook/Checkpoint/MCP/Plugin/Skill Pipeline；EXECUTING restart 继续禁止重放。
6. Ink picker 用真实 Arrow/Tab/Enter 覆盖默认自动执行、普通审批、继续规划和 keep/clear；连接向导的
   one-shot stdin 登录完成页保持输入监听，使用 ref 阻止重复副作用而不是关闭整个 useInput。
7. 发行 manifest 记录当前 commit、生产输入 digest、实际 CLI JAR/TUI digest；`--version` 重新计算包内
   digest，不匹配即 exit 1。安装版测试启动真实 Java stdio initialize/shutdown，stderr 必须为 0。

## 可证伪验证与边界

- Fake/真实 Java Plan 测试覆盖 evidence pass、缺失→NEEDS_VERIFICATION、显式 skip 与 restart；
- stdio/TUI 覆盖 question、durable review、Arrow/Tab/Enter、真实 Tool delivery、唯一终态与干净 child exit；
- packaged launcher 对 current commit/source/JAR/TUI digest 对账，并用篡改 manifest 负例失败关闭；
- 不声称真实 Provider 默认验证、在线 Plan 质量、多人并发、多平台安装或 L4 收益已经完成。
