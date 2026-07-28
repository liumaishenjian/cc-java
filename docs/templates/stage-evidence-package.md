# Stage 证据包模板

该模板用于 S01-S15 的设计、实现、验证和退出。单个 Capability 在 G0-G4 通过、
验证结果已经持久记录并与矩阵同一变更对账后，可以提升等级；整个 Stage 只有继续通过
G5-G6，才可以宣称退出。Capability 已达到阶段等级但 Stage Exit 仍为 Open 是允许状态，
必须明确记录剩余 Exit Blocker。

## 元数据

```text
Stage:
Status: Proposed | In Progress | Accepted | Blocked
Release / Commit:
Reference Behavior Baseline:
Authorized Snapshot ID: <ID | N/A - Not Used>
Feature IDs:
Current → Exit Target Levels:
Owner:
Date:
```

## G0：来源与授权

- 来源 URL、文档版本和访问日期；
- 使用授权材料时：记录授权范围、快照日期、Revision、可复现指纹、仓库外隔离位置，
  以及缺失的构建、测试、许可证或版本信息；
- 未使用授权材料时：Snapshot ID 写 `N/A - Not Used`，记录“不需要授权快照”的理由；
  不要求虚构授权范围、Revision 或指纹，但公开来源记录仍必须完整；
- 公开资料结论标记 `Documented / Observed / Inferred / Unknown`；授权源码结论只使用
  `Observed / Inferred / Unknown`。

未通过条件：

- 来源或授权范围不清楚；
- 无法区分事实与推断；
- 需要把参考源码放入仓库、Fixture 或 Golden Output 才能继续。

## G1：范围与可证伪目标

- Stage 和 Feature ID；
- 当前等级与本 Stage 退出目标；
- 正在复现的独立行为；
- 最小可证伪实验；
- 明确延期能力和 Accepted Deviation；
- 上一个 Stage 提供的前置契约。

未通过条件：

- 只有功能名称，没有输入、输出、状态和失败语义；
- 目标等级或验收证据不明确；
- 为后期能力提前建立无实际用途的模块。

## G2：研究与 ADR

研究记录只包含：

- 子系统职责和依赖；
- 状态迁移与终止条件；
- 协议不变量；
- 失败恢复、降级和防循环策略；
- 验证方法及尚未确认的内容。

使用授权源码研究结论时，必须单独建立“参考结论采纳 ADR”；Stage 设计 ADR 可以互链，
但不能替代。采纳 ADR 或未使用授权材料时的 Stage ADR 必须说明：

- 独立 Java 契约、命名和模块边界；
- 安全、权限和副作用边界；
- 被否决方案；
- 与参考机制的相同点、偏离和理由；
- 哪些结论不会进入本 Stage。

## G3：独立 Java 实现

- Fake/Fixture 测试先行；
- 只实现当前 Stage 所需最小能力；
- Adapter 保持在架构边缘；
- 预算、取消、事件和结构化错误贯穿新路径；
- 公共契约和核心类型具有准确中文 Javadoc；
- 不复制或逐行翻译参考源码表达。

## G4：验证

记录：

```text
Command:
Environment:
Date:
Commit:
Passed / Failed / Skipped:
Metric Before:
Metric After:
```

验证至少包含当前 Stage 已进入实现范围的：

- 正常路径；
- 边界条件；
- 失败，以及本 Stage 已实现的取消路径；
- 恢复或降级；
- 权限与安全负例；
- 独立编写的行为对照；
- 适合本 Stage 的量化指标。

取消、恢复、安全或其他后期路径尚未进入本 Stage 时，必须记录为
`Deferred to Sxx`，不能伪造测试，也不会仅因此阻止早期 Stage 的 Capability 升级。

参考源码文本、内部 Prompt、私有数据和固定自然语言不得作为断言。

## G5：可复现 Demo

Demo 必须写明：

- 前置条件和 Fixture；
- 可复制命令和输入；
- 预期观察点；
- 一次实际执行结果或 CI 链接；
- 至少一个负例；
- 当前事实边界和未实现能力。

仅有操作说明、没有可核验结果，不构成完成证据。

## G6：退出对账

- G0-G4 已验证的 Capability Level、Stage Target 和证据链接已在同一变更中对账；
- README、PRD、技术设计和 ADR 没有能力声明冲突；
- 测试、Demo、行为对照和 Gap Report 已互链；
- Gap Report 明确参考仍然更强之处；
- 已记录独立偏离、风险和下一阻塞能力；
- 构建与 Demo 能由仓库声明的标准工具链复现。

## 各 Stage 的最低专项证据

| Stage | 必须提供的专项证据 |
| --- | --- |
| S01 | Fake Model 回放、多 Tool 配对、预算原子性、唯一终态和事件顺序 |
| S02 | 流式 Chunk 聚合/中断、Provider opt-in E2E、TTY/Print 和模型流取消 |
| S03 | Realpath、穿越、Symlink/Junction、敏感文件、大小上限和注入 Fixture |
| S04 | Patch 原子性、脏工作区、命令超时/取消、进程树清理和编码闭环 |
| S05 | Permission 优先级属性测试、模式、Session Allow、硬拒绝和拒绝恢复 |
| S06 | JSONL 往返、崩溃点注入、resume/fork、未完成副作用和 Checkpoint/Undo |
| S07 | Protocol Round 不变量、四级减压、失败回退、防抖、事实保持率和压缩率 |
| S08 | 配置来源/合并、不得提权、层级指令和 `/doctor` 来源报告 |
| S09 | Hook 顺序、Matcher、阻断/非阻断、超时和失败隔离 |
| S10 | STDIO/远程 Stub、多 Server、权限、命名冲突、断连和认证失败 |
| S11 | Skill 懒加载/触发、Plugin 来源/命名空间、安装卸载和恶意资源 |
| S12 | RuntimeScope 隔离、父子摘要、预算/取消、并发、Worktree 和多 Agent Eval |
| S13 | File/Process/Network 逃逸、环境/Secret 和攻击性回归 |
| S14 | 协议兼容、SDK/Headless/Daemon、OTel 隐私、Fallback 和跨平台发行 |
| S15 | 创新假设、相对 S14 的 A/B Eval、收益/成本/安全阈值和 L4 证据 |
