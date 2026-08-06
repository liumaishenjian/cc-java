# S08 G5：Instructions、Settings、命令与交互闭环演示

> Stage：S08 G5 完整离线演示
>
> Feature：`BOOT-04`、`BOOT-06`、`CLI-07`、`CLI-08`、`CLI-09`、`MODEL-08`、`PERM-06`、`PERM-12`、`CTX-03`、`CTX-04`、`CTX-12`、`CTX-13`、`CFG-03`、`CFG-04`、`CFG-05`、`CFG-06`、`CFG-08`、`CFG-09`
>
> 参考：公开行为基线 `R2026.03`；授权参考快照 `AUTH-SRC-2026-07-29-A` 的受控机制研究结论见 ADR-045；本演示只验证项目独立 Java/TypeScript 契约，不包含参考源码表达。
>
> 状态：ADR-048 Corrective G5 Passed；G6 与 S08 Stage Exit 仅等待新的固定 implementation Commit。

## 可复现验证

```text
.\mvnw.cmd -pl cc-java-cli -am -Dtest=SessionCommandDispatcherTest,RuntimeStdioCommandHandlerTest,FileSessionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl cc-java-cli -am -Dtest=*Instruction*Test,*Settings*Test -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl cc-java-core -am -Dtest=S07ContextMemoryEvalTest,AgentRuntimeContextIntegrationTest,SummaryReductionCoordinatorTest,MemoryRecallAndPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd clean verify
npm --prefix cc-java-tui run check
.\mvnw.cmd javadoc:aggregate -Ddoclint=all -DfailOnWarnings=true
```

G4 复验结果：S07 Eval 36/36，三条样本 Token 降幅为 49%/49%/30%、实际中位数 49%，Tool
orphan 为 0 且事实、硬约束、完成率不退化；全量 Maven 成功，CLI 208 项通过、10 项按宿主环境
前提跳过；TUI 77/77；aggregate Javadoc 严格模式通过。

## Instructions 与 Settings 场景

1. 测试 Fixture 分别提供固定 user root、Workspace 根、目标目录和 Gitignored local source；发现顺序、
   去重、16/8/32 KiB 单文件预算与 128 KiB 总预算由确定性断言验证。
2. user root 使用独立 guard，Workspace 候选在 realpath 前拒绝 Symlink/Junction；普通文件、UTF-8、
   identity/TOCTOU 与 Gitignore 不可证明时均 fail closed。恶意 Instructions 只能作为模型输入投影，不能
   修改 Tool、Permission、Recovery 或 Workspace 决策。
3. Settings v1 对任意深度 duplicate key、unknown/version/type/size 错误整源拒绝；user/project/local、
   CLI 与 Session overlay 按冻结的逐字段 merge/delete/provenance 契约生成不可变快照。刷新取消、损坏或
   CAS 竞争保留 last-known-good，不暴露正文、Secret 或绝对路径。
4. Runtime 映射只在 idle/下一 Run 边界原子替换；Tool allowlist 只能缩小。`/model` 仅接受启动模型，
   `/permissions` 只切换三个受限 mode，均不能越过 Hard Denial、DENY、PLAN、ToolSource selector、
   Approval、WorkspaceGuard 或 S06 Recovery Gate。

## 已验证行为

1. `session.command` 只接受封闭 intent、严格 arguments、无 Run 关联及最多 128 code point 的非控制字符 `commandId`；重复 ID 复用同一终态，有界 budget 耗尽拒绝新的 ID。
2. `/compact [anchor...]` 最多接受 16 个、每项最多 512 code point 的无控制字符锚点；无参数时 anchors 为空并直接针对当前 Session 的 Canonical Transcript。`codej` 开发入口默认显式传入 256,000/8,192/4,096 Context 容量三元组，因此不会因启动器漏装配而返回 `UNAVAILABLE`。它先执行 S07 C1/C2；即使预算已满足仍可经原 C3/C4 Gate 尝试摘要。成功候选不改 Canonical Transcript、JSONL 或 Checkpoint，只在 Canonical 前缀未变化时一次性用于**下一 Run 的首个模型请求**。
3. `/context` 只投影 latest `ContextUsageView` 的 token 数值、状态/策略/原因枚举；stdio Client 对未知字段、Secret 泄漏、越界数字、错误 session/run 关联和非法结果状态 fail closed。
4. React/Ink 输入 `/` 显示 8 个封闭命令，方向键选择并以 Tab/Enter 补全；Slash parser 与 Java codec 对 compact anchors、mode、resume session ID、commandId 上限及控制字符使用一致的封闭输入边界。help/context/doctor/permissions 成功结果只渲染协议已验证字段与本地固定标签，拒绝结果只使用固定状态代码，不显示服务端自由文本。
5. `/resume <session-id>` 先复用 S06 writable Resume Gate 检查 Workspace identity、writer lease、fence、incomplete side effect 与 Checkpoint recovery；active Run、取消、当前目标、锁定或任一恢复拒绝均保留旧 Session。成功仅发布 `previousSessionId` 与 `resumedSessionId`，TUI 仅在 event Session ID 与目标 ID 一致时切换本地投影。
6. React/Ink 编辑器以 Enter 提交、Ctrl+Enter 换行；缓冲、每 Session 内存历史和封闭补全候选分别限制为 8,192 Unicode code point、100 条和 32 项。历史与草稿不持久化。
7. 活动 Run 中普通提交由 Java stdio Adapter 放入最多 100 条的 FIFO。测试覆盖严格 FIFO、100/101、首个 `run.started` 延迟竞态、重复/乱序事件、queue-full 关联拒绝、取消/clear/resume/shutdown/transport failure 清理，以及未发送标记不进入 Session JSONL。

## ADR-048 Corrective G4/G5 证据

完整 Maven Reactor 已全绿：Domain 45、Core 172、Spring 43（2 skipped）、Tools 101（8 skipped）、CLI 227（11 skipped），全部 0 failures / 0 errors；TUI 9 files 111/111，launcher 59/59。独立最终 review 无 blocking finding；全部 findings 与最终修复见 ADR-048。

真实 TTY 验收结果：

1. launcher 正常启动；真实 Provider prompt 在 1 turn、0 tools 返回 `OK`。
2. `/context` 显示 total 16,721、available-input 243,712、remaining 226,991，状态为 prepared/estimated 且未发生 compaction。
3. 无参数 `/compact` 作用于当前 Session，并安全拒绝无意义的一轮候选，而不是要求先存在 Context View。
4. 2,006-byte bracketed paste 折叠为 `[粘贴 #1 · <64KiB]`，正文不暴露；raw Left 将光标移到折叠 Paste 前，随后输入 `Z` 正确插入该位置。
5. Shift+Enter 产生第二个视觉行。

该结果关闭 ADR-048 G5；G6 唯一剩余条件是取得新的固定 corrective implementation Commit 并完成 Commit-scoped 对账。

## 保持的不变量

- 显式 compact 不覆盖整个下一 Run；消费后仍由既有 `ContextPreparationService.prepare` 为每个模型回合执行 S07 自动 C1-C4 reduction 与 typed-overflow 至多一次恢复。
- Canonical Transcript、Tool Call/Result 批次配对、取消、S05 Permission Pipeline、S06 Recovery Gate 和 ready-only Memory 路径不因命令投影改变。
- 没有 Settings 写入/迁移 UX、规则/selector 编辑、Provider discovery、多模型注册，也没有 S12 Worktree 或 S13 OS Sandbox。Resume 仍只提供既有 S06 Gate 的窄适配，不提供自动 Tool/副作用重放、跨版本迁移或新恢复机制。
