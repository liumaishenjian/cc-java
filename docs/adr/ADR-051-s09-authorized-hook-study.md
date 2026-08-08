# ADR-051：S09 授权 Hook 机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-08
- Stage: S09 Hooks
- Features: `HOOK-01`～`HOOK-13`
- Sources: `AUTH-SRC-2026-07-29-A`（仓库外只读机制研究）、`REF-07`（公开 Hooks 文档）、
  OpenAI Codex 公共 Hooks 设计文档（补充对照）

## Context

S01～S08 已经形成只读 Lifecycle、统一 Tool Pipeline、Permission、Session/Checkpoint、
Context 和 Instructions/Settings。下一阶段需要让用户在不修改 Runtime 核心的情况下观察、阻断
或补充生命周期，同时不能把 Hook 误当成第二套 Tool/Permission/Agent Loop。

维护者已授权使用 `AUTH-SRC-2026-07-29-A` 进行仓库外只读研究。该授权只允许提炼职责、状态、
边界、失败恢复和验证方法；不允许复制函数体、Prompt、私有命名、文件布局、常量或内部格式。

## 研究结论（抽象机制）

以下结论标注为授权快照内的 `Observed`、公开文档的 `Documented`，或需要本项目验证的
`Inferred`，不作为参考源码字节或测试 Oracle：

| 结论 | 等级 | 对本项目的启发 |
| --- | --- | --- |
| Event → Matcher → Handler 是独立三段职责；多个匹配 Handler 需要可追踪的结果聚合 | Observed / Documented | Domain 只表达协议，Core 负责匹配和收敛 |
| Pre Tool 可阻断，Post Tool 只能反馈，不能撤销已经发生的副作用 | Observed / Documented | Pre 放在 Permission 前；Post 为观察/Context 旁路 |
| Permission Hook 的 deny 优先，allow 不能覆盖 Hard Denial，abstain 回到普通审批 | Observed / Inferred | Permission 仍由现有 Policy/Approval 唯一决定 |
| Command/HTTP Handler 需要结构化输入、超时、取消、输出上限和错误策略 | Observed / Documented | 外部 Adapter 不能向 Core 泄漏命令行、密钥或原始输出 |
| 配置来源、信任、Session 生命周期和 Handler 资源需要独立管理 | Observed / Inferred | 后续加入来源 fingerprint、信任 Gate 和有界执行器 |
| Hook 自身不重新进入 Agent Loop；模型 Tool 仍走统一 Pipeline | Inferred | 递归由 Coordinator 边界阻断，不能靠 Prompt 防护 |

Codex 公共设计还提示：匹配组应以稳定配置顺序聚合，多个命令可有界并发，事件输入使用
严格 JSON，超时/输出应可诊断，项目配置的信任不可默认为已批准。本项目只采纳这些可独立
解释的机制，不复制其协议字段、实现表达或文案。

## 采纳与偏离

### 采纳

1. `LifecycleDispatcher` 保持 observation-only；阻断 Hook 使用独立 `HookCoordinator`。
2. Domain 使用项目自有 `HookEventKind`、`HookInvocation`、`HookMatcher`、结果和聚合类型。
3. 匹配 Handler 可并发执行，但返回结果按显式绑定顺序重排；`DENY/BLOCK` 优先于 `ALLOW`。
4. 每个绑定拥有 `FAIL_OPEN`、`FAIL_CLOSED` 或 `OBSERVE_ONLY`，超时、取消、异常和未信任均转为结构化状态。
5. Hook 输入只允许有界、已脱敏的摘要；原始 Tool 参数、文件正文、Provider Key、命令行和完整
   stdout/stderr 不进入规范 Hook 结果。
6. Pre Tool 在参数校验之后、Permission 之前；Post Tool 在 Result 规范化和 durable 记录之后。

### 有意偏离

1. 第一切片不支持 Handler 改写 Tool 输入（updated input），避免多个 Handler 的重写顺序破坏
   Workspace/Permission 不变量；需要时另建 ADR 和冲突策略。
2. 第一切片不支持 Prompt/Agent Handler；这属于 S12/S15，不能让 Hook 重新进入模型循环。
3. HTTP 只在后续 Adapter 中允许 loopback；远程网络、认证和重定向留给 S13 安全边界。
4. Hook Context 先作为有界聚合结果返回；在完成 Context 投影和 Session 语义评审前，不能静默
   写入 Canonical Transcript。

## 可证伪验证

- Matcher 正则、事件边界、绑定顺序和多个 Handler 的并发聚合；
- deny/block 优先、非阻断 Post 不可停止 Pipeline、Hard Denial 不可被 Hook 覆盖；
- 超时、取消、异常、非法输出、输出上限和未信任 Handler；
- Pre Tool 的 Tool Result 保留原始 Call ID，且 Permission/副作用不会提前发生；
- Post Tool 只能观察规范结果；Hook 进程不能递归进入 Tool Pipeline；
- 后续 Command/HTTP Adapter 再验证 argv、最小环境、进程树清理、loopback 和无孤儿进程。

## 后续边界

本 ADR 只冻结研究结论和采纳边界，不宣称 Command/HTTP、持久配置、Trust UI、完整 Session/Compact
Hook 已实现。它由 ADR-052 的产品/架构契约和 S09 G0-G6 证据包继续约束。
