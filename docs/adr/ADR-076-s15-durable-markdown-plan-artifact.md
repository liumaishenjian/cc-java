# ADR-076：S15 Durable Markdown PlanArtifact 与 Session 恢复基础

- Status: Accepted
- Date: 2026-08-20
- Stage: S15 Independent Innovation（有界基础批次）
- Feature IDs: `PLAN-01`
- Current → Batch Target: `L1 → L1`；本批建立 L2 必要基础，不提前升级
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Supersedes in Part: [ADR-074](./ADR-074-s15-plan-step-gate.md) 的“严格 proposal JSON + 内存步骤 Gate”主体设计
- Preserves: ADR-074 的只读规划、显式批准、完整 Runtime/Pipeline 执行、关联校验与失败关闭不变量

## 1. 背景与本批边界

ADR-074 以严格 JSON proposal、`PlanDocument` 和进程内步骤 Gate 证明了“批准前只读、批准后才执行”这条控制链，但把用户计划长期形态绑定到模型专用 JSON 和内存步骤状态。它不能支撑可编辑 Markdown、revision CAS、重启恢复和 Fork 可写身份隔离。

本 ADR 冻结替代主体：**用户计划是 Session-owned、durable、revisioned Markdown artifact；旧 `PlanDocument` 与内部命令保持兼容，但不再是未来 Plan UX 的唯一事实形态。** 本 ADR 的基础批次只实现 artifact、边缘 store 与 Canonical Session 恢复基础；持续规划与结构化 TUI 后由 ADR-077 Batch 2 实现。预算/403/WebFetch、Evidence Gate 或真实 Provider 质量 Eval仍未实现。

## 2. 受控参考研究（G0/G2）

2026-08-20 在仓库外只读研究 `G:\AI Cloud\claude-code-main`，至少核对：

- `src/commands/plan/plan.tsx`
- `src/utils/plans.ts`
- `src/utils/messages.ts` 中 Plan workflow
- `src/tools/ExitPlanModeTool/ExitPlanModeV2Tool.ts`
- `src/components/permissions/ExitPlanModePermissionRequest/ExitPlanModePermissionRequest.tsx`
- `src/screens/REPL.tsx`
- `src/utils/sessionRestore.ts`
- `src/utils/conversationRecovery.ts`
- `src/utils/sessionStorage.ts`（仅恢复职责与边界）

只提炼职责、状态与恢复机制；未复制函数体、Prompt、文案、私有命名、布局、常量或内部格式。

| 分类 | 最小机制结论 |
| --- | --- |
| Observed | 进入只读规划、维护计划文件、展示/编辑计划、请求退出批准、恢复执行权限是分离职责；UI 只收敛选择，不直接执行 Tool。 |
| Observed | 计划内容以可读文件存在，Session 持有稳定关联；Resume 尝试复用关联，文件缺失时可从已持久的会话材料恢复。 |
| Observed | Fork 不复用原 Session 的可写计划身份，而是创建新的计划文件关联，避免两个 Session 相互覆盖。 |
| Observed | 退出审批可以携带用户编辑后的计划；批准、继续规划与拒绝是不同状态转移，执行仍回到普通 Runtime/Permission 控制面。 |
| Inferred | cc-java 应把 artifact 内容、身份、revision、digest 与生命周期状态建模为 Domain 值；文件系统只作为 edge projection，Canonical journal 提供缺失文件恢复事实。 |
| Inferred | 本地 artifact 是可重建投影，journal 是跨文件崩溃后的 authoritative source；合法但落后/领先的 projection 可确定收敛，损坏/身份冲突必须 Fail Closed。 |
| Observed | Resume 复用原 Session 关联；Fork 使用新 Session 身份和新的计划文件关联，避免原会话与分叉相互覆盖。崩溃恢复只重建会话/计划上下文，不等同于自动重放副作用。 |
| Unknown | 授权快照准确 Revision/发行版本、内部格式兼容承诺、多主机并发、所有远程/协作编辑语义和公开行为稳定性。 |

上述 `Observed` 只表示授权快照内机制，不构成公开产品黑盒证据，也不作为本项目 schema、常量或测试 Oracle。

## 3. 独立设计

### 3.1 Domain artifact

`PlanArtifact` 至少包含：

```text
planId / sessionId / revision
Markdown content / SHA-256 contentDigest
status / createdAt / updatedAt
```

不变量：

1. revision 从 1 开始，每次成功更新严格 `+1`；
2. digest 必须等于完整 UTF-8 Markdown；
3. Session 是唯一可写 owner；
4. Fork 复制内容但生成新 `planId/sessionId`，revision 重置为 1，并统一回到 `AWAITING_APPROVAL`；不继承源审批、执行中或终态；
5. status 不授予 Tool 权限，也不解除 Recovery Gate；
6. `nextRevision` 的 `updatedAt` 不得早于当前 revision；墙钟回退时钳制到旧 `updatedAt`；
7. Domain/Core 不依赖 `Path`、JSON、Jackson、FileLock 或文件布局。

### 3.2 Port 与 generation + manifest edge store

Core `PlanArtifactStore` 只暴露 `load`、revision+digest CAS `save` 和 create-only `restoreMissing`。CLI 边缘 `FilePlanArtifactStore` 使用固定 Session 私有目录与固定格式文件名，不接收外部路径；严格验证普通文件、realpath、Symlink/Junction/重解析、UTF-8、大小、identity 与 digest。调用方必须持有 Session single-writer lease；revision+digest CAS 防迟到编辑，但不替代 writer fence。

每个 revision 的正文是不可变 generation（文件名绑定 revision+digest），唯一 authoritative 可见入口是 `plan.manifest.json`。发布顺序是：同目录 `CREATE_NEW` 写 generation 并 force → 同目录 `CREATE_NEW` 写 manifest stage 并 force → **单次** `ATOMIC_MOVE` 替换 authoritative manifest → 重读验证。崩溃发生在 manifest 切换前，只留下可忽略 generation orphan，读者仍看到完整旧版；切换后只看到完整新版。两个 rename 不构成原子事务，也不再如此宣称。原子移动不可用时失败关闭，不回退普通覆盖。

稳定 `plan.md` 若未来提供，只能是可重建、非权威的人类投影；本批不生成该文件。orphan 清理只扫描最多 64 个条目、仅删除超过一小时且高于当前 manifest revision（或无 manifest）的 generation/temp；清理失败不影响权威读写。

### 3.3 Canonical Session 与恢复

保存采用 generation prepare → canonical journal append+force → manifest commit。journal 是跨文件 authoritative source，projection 是可重建缓存：

- generation 已落盘而 journal 未提交：无可见 manifest 变化，只是安全 orphan；
- journal 已提交而 manifest 未切换/缺失/落后：Resume 由已验证 journal 重建 generation/manifest；
- 本地 manifest 合法但领先 journal：视为未完成 projection commit，移除指针并保留 generation 供有界清理；不永久阻塞恢复；
- manifest/generation 损坏、identity 冲突或 digest 篡改：Fail Closed，不用 journal 静默覆盖未知字节；
- 任一步同步失败 fence 当前 Session；恢复只收敛 projection，不执行 Plan、不恢复活动 Tool、不自动重放副作用；
- Fork：复制会话历史，但移除源 artifact/snapshot 事件，追加新 plan/session identity、revision 1、`AWAITING_APPROVAL` 的新链；原 Session 字节与后续修改不变。

`plan.artifact.saved` 携带完整 Markdown 而不是路径；生产状态迁移把 artifact 与兼容 `PlanDocument` projection 聚合在同一条 JSONL 记录中，避免两个 append 之间的永久不一致窗口。Domain 的唯一 `PlanLifecyclePolicy` 同时服务写前检查与 Codec replay：首 revision 只能是 `DRAFT/AWAITING_APPROVAL`，相邻 revision 必须严格 `+1` 且符合统一状态链，非终态允许保持同状态的普通 Markdown revision，终态禁止继续追加。重复批准、重复拒绝及 approved 后拒绝由 Headless 在比较 before/after 后跳过持久化；`AWAITING_APPROVAL -> DRAFT` 已由 ADR-077 Batch 2 启用，用于同一 planId/sessionId/revision chain 的反馈继续规划。非法写在 journal append 前以固定错误码拒绝，journal、manifest 和当前 artifact 均不变。历史独立 `plan.snapshot` 仍兼容；Core 构造器直接验证 document/state/artifact 的 planId、sessionId、status、digest、Gate 和步骤游标，legacy plan-only/artifact-only 继续合法。

现有 `plan.snapshot` 必须允许多次 append，恢复采用最后一个完整合法投影；这修正了旧 codec 只接受一次 snapshot、无法承载状态迁移的问题。旧 major-1 Session 没有 artifact 记录时仍按空 artifact 恢复，保持旧 Plan/Session 兼容。

### 3.4 与旧 PlanDocument 的迁移边界

- `PlanDocument`、严格 proposal parser、`PlanModeCoordinator` 和内部 `plan-*` 命令继续工作；
- 新 proposal 安装、批准、拒绝、开始执行与终态同步创建/推进 artifact revision，并在同一 Session journal 保存；
- ADR-077 后模型通过受控 artifact Tool 直接维护 Markdown；旧结构化提案仅为内部兼容桥；
- 不把 artifact 自动解释为可执行步骤，不自动重放副作用。

## 4. 安全不变量

1. 规划期只读 Tool 与 PLAN Permission 不变；artifact 写入是可信应用控制面，不是模型任意 Workspace 写入。
2. 显式批准仍绑定当前 Plan 身份和 digest；执行继续进入完整 AgentRuntime 与唯一 Tool Pipeline。
3. journal 或 projection 任一写入不确定时 fence Session，不能继续副作用；恢复由 canonical journal 确定收敛结果。
4. 本地合法 projection 可按 journal 重建/回退；manifest/generation 损坏、身份或摘要冲突不自动修复。
5. 文件名、目录、JSON schema、常量均为 cc-java 独立设计，不解析参考数据。
6. Permission、artifact、FileLock、Checkpoint 均不是 OS Sandbox。

## 5. 可证伪测试

本批要求覆盖：

- generation 已落盘但 manifest 未切换时仍只见完整旧 revision；
- manifest 指向缺失或篡改 generation 时结构化拒绝；
- stale revision 与错误 digest 均拒绝且当前内容不变；
- Resume 恢复同一 identity/revision；
- journal 已提交而 manifest 缺失，以及 journal 恰比旧 manifest 快一版时，均由 journal 恢复/fast-forward；
- 本地 projection 领先 journal 时移除 manifest、orphan 不阻塞恢复；
- Fork 新 plan/session identity、独立 revision 链、终态源重置为等待审批，修改 Fork 不改变来源；
- 非法初态/状态跳转在 append 前结构化拒绝且三份持久状态不变；revision/时间/状态/digest/owner 恶意 journal 链在 replay 结构化拒绝；
- 重复 reject、重复 approve、approved 后 reject 不新增 revision，double reject 关闭后可正常 Resume；
- Fork 新 target 在 journal 写完后的 artifact/启动阶段故障只回滚本次新目录的固定一级文件；writer entry 释放、source 字节不变，无法安全精确删除时保留可 Resume 的 journal，不递归宽删。
- Session mismatch、Symlink/Junction/路径身份拒绝；
- 没有 artifact 记录的旧 Session 与旧 `PlanDocument` E2E 保持兼容。

## 6. 延期与等级纪律

持续 PLANNING Runtime、模型直接 Markdown revision、stdio/TUI 结构化问题与 review 已由 ADR-077 实现。以下仍延期：Batch 3 durable approval-to-execution、预算/403/WebFetch、Evidence Gate、多人/远程冲突、稳定跨版本 migration、真实 Provider proposal Eval 与 S15 L4 A/B Eval。

本批完成后 `PLAN-01` 仍为 L1。只有用户可用的 durable Markdown 编辑/审批/执行恢复全链、故障与真实体验证据完整，才可评估 L2；本 ADR 不提前提升等级或宣称 S15 Exit。
