# ADR-072：S15 Permission Auto Review 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-16
- Stage: S15 Independent Innovation（Batch A）
- Feature IDs: `PERM-05`（研究输入支持后续 L0 → L1）、`PERM-02/03/04/06/07/09/10`（组合回归，不改变等级）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenAI Codex `rust-v0.147.0`，Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Classification: 授权快照为 `Observed / Inferred / Unknown`；Codex 固定公开源码为 `Observed`；采纳边界为本项目 `Documented`

## 背景

S05 已固定 Hard Denial → Deny → PLAN → Ask → Allow → Mode/Effect Default 的确定性顺序，
S09 又允许 Permission Hook 在最终人工审批前给出受信意见。S15 希望研究一种更窄的自动审查：
只有既有控制链最终仍为 `ASK` 时，才由独立模型审查端口给出“仅本次允许”或拒绝；它不能
成为新的 Permission Mode、规则来源、Session Grant 或 Pipeline 旁路。

本轮按 ADR-022 在仓库外只读研究 `G:\AI Cloud\claude-code-main`，并以 Apache-2.0 的
`G:\AI Cloud\codex-rust-v0.147.0` 固定公开快照交叉验证。两类来源只用于抽象职责、状态、
不变量、失败恢复与验证方法；未复制函数体、Prompt、文案、私有名称、文件布局或实现常量。

## 双源研究结论

| 机制结论 | 授权快照 | Codex 0.147.0 | 本项目采纳 |
| --- | --- | --- | --- |
| 执行权限范围与何时请求审批是不同维度 | Observed | Observed | `PermissionMode` 与 `ApprovalReviewer` 正交 |
| 自动路径仍须服从危险路径、显式拒绝、规划限制和 Tool 安全检查 | Observed | Observed / Inferred | 自动审查只消费最终 `ASK`，不重新评估前置 Policy |
| 自动审查失败需要有界熔断并回到更保守行为 | Observed / Inferred | Inferred | Run-owned、默认三次失败的 `AutoReviewCircuit` |
| 自动决定不应隐式扩大后续调用范围 | Observed | Observed / Inferred | 自动允许只等价 `ALLOW_ONCE`，绝不创建 Session Grant |
| 取消必须与当前 Run 共用，不可在 Run 终态后迟到放行 | Observed | Observed | Gateway 接收共享 `CancellationToken`，取消传播为 Run 控制流，不转换为拒绝或 circuit 计数 |

Codex 的公开 preset 还直接表明：approval policy 与 permission profile 可以配对但保持两个字段，
且“从不询问”并不等于更强的文件/网络隔离。本项目只采纳该职责分离，不照搬枚举、CLI 文案或
profile 常量。

## Unknown

- 授权快照的准确 Revision、发行版本、许可证、权利人及公开再使用权；
- 参考自动分类器的内部 Prompt、模型、规则、阈值、遥测、质量指标和全部降级路径；
- 公开 Codex 的 preset 是否代表所有 Surface、平台和未来版本的完整行为；
- 自动审查在真实 Provider、长会话和高风险 Tool 上的误放行率与延迟收益。

这些 Unknown 不进入本项目 Prompt、常量或测试 Oracle。默认失败阈值 `3` 是 cc-java 为
Batch A 独立选择的保守、可证伪上限。

## 采纳与偏离

1. 采纳 reviewer 与 permission mode 正交、final-ASK-only、一次允许、失败关闭、共享取消和
   Run-owned 熔断。
2. `Plan / Ask / Auto` 只是产品选择映射：`PLAN+USER`、`DEFAULT+USER`、
   `DEFAULT+AUTO_REVIEW`；`ACCEPT_EDITS` 仅保留旧配置兼容，不是新选择项。
3. 不采用“跳过 Permission”“从不询问即全权限”或自动写入 Session/持久规则的设计。
4. 不复制参考分类 Prompt、规则类别、拒绝文案、类型名、遥测字段、常量或文件布局。
5. 本研究本身只冻结机制边界；后续 ADR-073 已独立完成 Domain/Core、Headless、stdio 与 TUI
   生产接线并以离线 Fake/E2E 将 `PERM-05` 提升到 L1。真实 Provider 质量 Eval 仍延后。

## 可证伪验证

- Hard Denial、Deny、PLAN、Ask/Allow 优先级仍由现有 `PermissionPolicy` 决定，reviewer 无调用机会；
- 只有 hooks 后仍为 final `ASK` 的路径可以调用自动 reviewer；Hook Allow/Deny 均不调用；
- 自动 reviewer 只能返回 Allow Once 或 Deny，无法表达 Allow Session；
- 非严格 verdict、Provider/timeout/parse/internal failure 均拒绝当前 Tool；
- Run 取消传播至 Gateway；取消或迟到 Allow 必须抛出 `CancellationException`，不产生 deny 或 circuit 计数；
- 同一 Run 连续三次 `DENY` 或 reviewer non-cancel failure 后，第三次当前决定携带 typed stop，后续不再调用 Gateway；close 后拒绝使用；
- DTO 的字符串、集合和自由文本均有硬上限，且不包含 Tool 原始参数、Prompt、文件正文或 Secret。

## 停止条件

授权范围撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要复制参考 Prompt、字节、
私有格式与命名时立即停止。该研究 ADR 不单独提升 Capability Level；L1 由 ADR-073 的生产实现与
可证伪证据支持。
