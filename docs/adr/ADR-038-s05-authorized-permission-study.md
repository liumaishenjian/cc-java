# ADR-038：S05 授权 Permission 机制研究采纳边界

- Status: Accepted
- Date: 2026-08-02
- Stage: S05 Permission Pipeline
- Capability IDs: `BOOT-03`、`CLI-05`、`LOOP-13`、`TOOL-03`、`PERM-01`、
  `PERM-03/04/06/07/08/09/10/11/13`、`HOOK-01`、`SEC-09`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred / Unknown`；采纳边界为 `Documented`

## 背景

S04 已用固定 `DEFAULT/PLAN` 和 `Allow Once/Deny` 证明副作用 Tool 必须经过 Java
`ToolExecutionPipeline`。S05 要把这条骨架扩展为可解释的模式、规则、Session Grant、
Hard Denial、Permission Lifecycle 和拒绝恢复。按照 ADR-022 与仓库成熟核心机制研究要求，
生产设计前必须先理解授权快照中的职责和失败边界，但不能复制其表达。

## 受控研究范围

本轮只读研究了权限值对象、规则装载与来源、Tool/参数匹配、模式决策、审批队列、
Session 更新、保护性检查、非交互收敛、拒绝计数，以及内置/外部 Tool 的执行入口。
研究只提炼机制，未复制或逐行翻译函数体、Prompt、注释、错误文案、私有类型名、文件布局、
内部格式或实现常量；参考字节没有进入仓库、Fixture、Golden Output、依赖或发布物。

最小观察已登记在
[R2026.03 授权参考源码基线](../reference-baselines/R2026.03-authorized-source.md)。

## 采纳的机制

1. **决定与原因分离**：`Allow / Ask / Deny` 只是行为；规则来源、模式、Hard Denial、
   Session Grant 或人工审批必须另有类型化原因，便于生命周期和测试解释。
2. **拒绝与保护优先**：可信保护策略和显式 Deny 必须早于普通 Allow、模式默认和 Session
   Grant；项目指令、模型文本、Tool 参数与 Tool 来源不能提升优先级。
3. **模式与规则正交**：模式定义默认行为，声明性规则只覆盖明确匹配范围；`Accept Edits`
   不能被解释成通用 Shell 或系统权限。
4. **Session Grant 是有界规则**：允许当前 Session 不是布尔总开关，而是对已展示 Tool 与
   可解释 selector 的内存授权；关闭 Session 后失效。
5. **审批只做收敛**：Surface 只能提交对匹配请求的范围化决定，不能直接执行 Tool；取消、
   EOF、关闭和竞争决定必须一次性 Fail Closed。
6. **统一执行入口**：内置、MCP、Plugin 和 Sub-Agent Tool 的来源只参与规则/诊断，不能
   绕过参数校验、权限、审批、执行、裁剪和事件。
7. **拒绝需要恢复策略**：Denied Tool Result 应回到模型；相同拒绝范围的重复请求必须有
   确定性去循环策略，而不是无限弹出相同审批。

## 有意偏离

- S05 只实现 `DEFAULT`、`PLAN`、`ACCEPT_EDITS`；参考材料中的自动分类、绕过、
  不询问等模式不进入本阶段。
- S05 只接受装配时 `STARTUP` 规则和进程内 `SESSION` Grant。用户/项目/本地/Managed
  配置文件的装载、合并和编辑属于 S08/S13。
- `run_command` 保持不透明进程 Tool；Accept Edits 不根据 Shell 文本猜测其是否等价于
  文件编辑。命令 Session Allow 只能匹配同一完整规范化命令。
- 不采用参考拒绝阈值、内部错误文案、遥测标签或规则字符串格式。本项目将用独立 Java
  值对象和可证伪场景定义边界。
- 本阶段不实现 Permission Hook、真实 MCP/Plugin/Sub-Agent Adapter、OS Sandbox、
  模型分类器或持久权限迁移。

## 安全边界

- Hard Denial 是应用层可信策略，不是 Prompt，也不是项目配置。
- S05 Permission 仍运行在当前 OS 账户下，不是 S13 Sandbox。
- Tool Adapter 的 WorkspaceGuard、敏感路径、命令固定 Shell/环境和输出限制继续独立执行；
  Permission Allow 不能跳过 Tool 自身安全校验。
- 参考源码不作为测试 Oracle；所有测试使用本项目独立 Fake、临时 Workspace 和公开需求。

## 可证伪验证

采纳理解必须由 ADR-039 的测试契约证伪，重点包括：优先级全组合、Session 越界不命中、
保护路径不可被 Allow 覆盖、Print Ask 安全拒绝、拒绝后继续推理、重复拒绝去循环，以及
Fake MCP/Plugin/Sub-Agent Tool 无法绕过统一 Pipeline。

## 停止条件

若授权范围被撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要复制参考字节，
立即停止使用该材料并恢复隔离。该 ADR 只接受研究边界，不代表 S05 已实现，也不提升任何
Capability Level。
