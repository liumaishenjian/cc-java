# S08 Instructions + Settings Stage Exit 证据

> Stage：S08
>
> 实现 Commit：`7bac8ead3a9ebb30df410f97703525035beee660`
>
> Baseline：`R2026.03`；授权快照：`AUTH-SRC-2026-07-29-A`
>
> 结论：G0-G6 Passed，Stage Exit Accepted

## G0-G2：来源、范围与独立契约

- G0：ADR-045 在 ADR-022 的受控边界内研究分层 Instructions、Settings/LKG、命令、诊断和失败恢复机制；未复制参考源码表达。
- G1：ADR-046 冻结 18 个 Feature ID、L2 退出目标、文件位置、优先级、输入上限、命令语义和延期边界。
- G2：ADR-047 冻结 Domain/Core/Application/Adapter 所有权、独立 user-root guard、严格 parser、copy-on-write LKG、Runtime 原子映射与 A-F 测试矩阵。

## G3：实现与独立审计

- A：user/project/directory/local Instructions 发现、预算、去重、路径安全与 Headless 请求投影。审计修复 Workspace 内部 Symlink 在 realpath 后被跟随的问题。
- B：Settings v1 duplicate-key/unknown/type/size 整源拒绝、逐字段 merge/delete/provenance、Gitignore fail closed 与 LKG/CAS。
- C：model、PermissionMode、Tool visibility 只在安全边界应用；Tool allowlist 只能缩小，不能绕过 S05/S06/S07 Gate。
- D：`/help`、`/clear`、`/doctor`、`/model`、`/permissions`、`/compact`、`/context` 的类型化 Java/stdio/TUI 投影。
- E：8,192 code point 编辑器、100 条 Session 内存历史、32 项封闭补全与 100 条 FIFO steering；未发送正文不持久化。
- F：`/resume` 复用 S06 Workspace/writer/fence/incomplete-side-effect/Checkpoint recovery Gate，不自动重放 Tool 或副作用。

## G4：验证

```text
.\mvnw.cmd -pl cc-java-core -am -Dtest=S07ContextMemoryEvalTest,AgentRuntimeContextIntegrationTest,SummaryReductionCoordinatorTest,MemoryRecallAndPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd clean verify
npm --prefix cc-java-tui run check
.\mvnw.cmd javadoc:aggregate -Ddoclint=all -DfailOnWarnings=true
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

- S07 定量复验 36/36；Tool orphan 0，事实/硬约束/完成率不退化，三样本估算 Token 降幅 49%/49%/30%，中位 49%。
- Maven 全量 Reactor 成功；CLI 208 通过、10 项按 Windows/Symlink/Junction 环境前提跳过。
- React/Ink build 与 77/77 测试通过。
- Aggregate Javadoc 在 `doclint=all`、`failOnWarnings=true` 下通过。

## G5-G6：Demo、Gap 与对账

- G5：`docs/demos/S08-g3-d-command-projections.md` 覆盖 Instructions、Settings、Runtime 映射、命令、编辑/steering 与 Resume；`docs/gap-reports/S08.md` 记录延期边界。
- G6：功能矩阵、PRD、技术设计、README 与看板按实现 Commit 对账；18 个 S08 Feature 达到退出目标 L2。
- 保持延期：Settings 写入/迁移、rules/selector 编辑、Provider discovery/多模型注册、Managed Policy、S12 Sub-Agent/后台任务/Worktree、S13 OS Sandbox、S14 稳定协议/Export/Retention/Migration。

## Stage Exit

S08 Accepted 不表示存在 OS Sandbox、稳定机器协议、跨版本迁移或自动副作用重放。内置 Tool、未来外部 Tool 与所有命令仍受既有确定性 Pipeline、Permission、Workspace 和 Recovery Gate 约束。
