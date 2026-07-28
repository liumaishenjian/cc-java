# AGENTS.md

本文档定义仓库级的人类与 AI 贡献者协作规则，适用于仓库根目录下的所有文件。

## 1. 修改前必须阅读

按以下顺序阅读：

1. [README.md](./README.md)
2. [参考架构](./docs/reference-architecture.md)
3. [公开行为基线](./docs/reference-baselines/R2026.03-public-behavior.md)
4. [未核验参考材料隔离登记](./docs/reference-baselines/R2026.03-unverified-source.md)
5. [功能对照矩阵](./docs/feature-parity-matrix.md)
6. [产品需求文档](./docs/product-requirements.md)
7. [技术设计文档](./docs/technical-design.md)
8. [ADR-020](./docs/adr/ADR-020-quarantine-unverified-reference-source.md)、
   [ADR-021](./docs/adr/ADR-021-s02-model-streaming-cli-scope.md)、
   [ADR-018（历史）](./docs/adr/ADR-018-authorized-reference-study.md)、
   [ADR-019（历史）](./docs/adr/ADR-019-s07-progressive-context-reduction.md)与
   [Stage 证据包模板](./docs/templates/stage-evidence-package.md)
9. 本文档

参考架构定义长期学习目标；功能对照矩阵是当前差距和目标等级的权威记录；产品需求文档定义“做什么”；技术设计文档定义当前“如何实现”。不得通过实现细节悄悄改变产品行为，也不得让这些文档相互矛盾。

## 2. 当前项目阶段

仓库已经完成 **S01：Runtime Kernel**，G0-G6 与 Stage Exit 均为 Accepted；被测实现
Commit 为 `5ef0bbbf54c75fcc3c8479c2c52bfbaa29beaabd`。当前处于 **S02 启动 Gate**：
ADR-021 已固定 23 项 Feature 和 G1 目标，生产实现尚未开始。

当前允许并要求：

- 保持 S01 的 Framework-free Domain、显式 Agent Runtime、Tool Pipeline、内存 Session
  与离线 Fake 测试回归继续通过；
- S02 实现范围必须保持 ADR-021 的 23 项 Feature、`Current → Target` 等级、真实
  Provider/流式 Tool Call Spike、CLI 契约、取消边界和可证伪实验；
- 只有 Spike 证明真实用途后，才能确认 Spring Boot、Spring AI、Picocli 与 JLine 的
  准确版本和依赖；
- 在 S02 启动 Gate 完成前，真实 Provider、流式 CLI、文件 Tool、Shell、完整权限策略
  和持久化能力继续保持未实现状态；
- S02 的代码、测试、Demo、证据和看板更新必须作为新的独立变更开展。

不得仅因为 S01 已退出就宣称 S02 或更后阶段能力已经可用。

## 3. 项目定位

`cc-java` 是一个参考成熟 Coding Agent、采用 Java 独立设计和重实现的通用 Agent Runtime 与 CLI。

项目遵循以下学习闭环：

```text
观察公开行为
→ 解释负责该行为的子系统
→ 定义独立的 Java 契约
→ 实现并测试
→ 与参考基线对照
→ 记录剩余差距
→ 真正理解后再创新
```

第一个可以运行的 Coding Loop 只是学习检查点，不是项目最终范围。FixBug、代码评审和测试生成属于未来的 Skill 或示例场景，不是核心领域模型。

## 4. 可追踪性与学习证据

每一个实现任务都必须明确：

1. 所属 Stage（`S01` 至 `S15`）；
2. `docs/feature-parity-matrix.md` 中对应的一个或多个 Feature ID；
3. 当前等级与目标等级（`L0` 至 `L4`）；
4. 公开行为基线、经核验参考材料 ID（未使用时写 `N/A - Not Used`），以及
   `Documented / Observed / Inferred / Unknown`；
5. 正在重现的可独立表达行为或项目需求；
6. 能够证明等级提升的测试、演示或度量；
7. 完成后维护者应当能够解释的设计决策。

所有 Stage 使用 [Stage 证据包模板](./docs/templates/stage-evidence-package.md)中的
G0-G6 Gate。当前来源隔离和独立重实现边界由
[ADR-020](./docs/adr/ADR-020-quarantine-unverified-reference-source.md)定义。

能力等级提升时，必须在同一个变更中更新功能对照矩阵。一个 Stage 只有同时具备以下材料才算完成：

- 设计说明或 ADR；
- 在可行情况下提供确定性测试；
- 可复现的演示；
- 与参考行为的对照；
- 简短的差距报告，明确仍然缺失的内容。

不得仅因为某个功能“看起来有趣”就加入实现。应优先关闭当前 Stage 的差距；如果确实进行独立创新，必须记录假设和评测方案。

### 4.1 项目进度看板强制更新

[功能对照矩阵](./docs/feature-parity-matrix.md)始终是 Capability、等级和 Stage 路线的
权威来源；[`docs/progress-state.properties`](./docs/progress-state.properties)只记录
当前 Stage、G0-G6、阻塞项和最近验证；[`docs/progress.html`](./docs/progress.html)是由
前两者生成并附带源码/构建摘要的只读展示，不是第三份事实来源。

以下任一情况发生时，任务完成前必须更新并校验进度看板：

- 修改任意生产或测试 Java 代码；
- 修改父 POM、模块 POM、Maven Wrapper、构建配置或仓库脚本；
- 修改 Capability Level、Stage 目标、Gate 状态、阻塞项、测试证据或能力声明；
- 新增、删除、合并或重命名 Feature ID。

强制流程：

1. 如果能力、范围或证据发生变化，先更新功能矩阵和对应 ADR/PRD/技术设计/Demo/Gap；
2. 无论 Capability Level 是否变化，都更新 `progress-state.properties` 的
   `last.updated` 和 `last.change`；没有等级变化时必须明确写出；
3. 代码输入或功能矩阵发生变化时，根据生成器报出的当前值更新
   `inputs.code.digest` 或 `inputs.matrix.digest`；该确认表示维护者已经重新审视本次变更
   对 Stage、Gate、证据和能力等级的影响，不能只机械复制摘要而跳过判断；
4. Gate、阻塞项和 `evidence.*` 只能根据实际证据更新，不能因为代码已编写就提前标记通过；
5. 在仓库根目录执行：

   ```text
   java scripts/ProgressDashboard.java
   java scripts/ProgressDashboard.java --check
   java scripts/ProgressDashboard.java --self-test
   ```

6. `docs/progress.html` 必须与相关代码和文档处于同一个变更中；
7. `--check` 未通过时，不得宣称任务完成、不得提升 Capability Level、不得退出 Stage。

禁止手工编辑 `docs/progress.html`。如果看板展示需要变化，应修改矩阵、状态文件或生成器。
生成器会把 Java 源码、POM、Wrapper 和仓库脚本的摘要写入 HTML，因此这些文件发生变化
却没有重新生成看板时，`--check` 会失败。
仅修改与项目进度完全无关的纯排版/拼写且不接触代码时，可以不更新
`progress-state.properties`，但只要输入文件发生变化仍必须重新生成并运行 `--check`。

## 5. 来源控制与独立重实现规则

以下规则不可妥协：

- 不复制或翻译泄露、反编译或其他受限制的源码；
- 不复制内部 Prompt、注释、错误文案、私有类型名、文件布局或实现常量；
- 不把受限制源码仓库用作依赖、子模块、测试 Fixture 或 Golden Output 来源；
- 不在查看受限制源码后凭记忆还原其具体表达；
- 默认只从公开文档、公开接口和独立设计的黑盒场景中提取行为需求；
- 使用能由本项目需求解释的独立命名和 Java 原生设计；
- 记录重要的第三方启发来源和适用的许可证义务；
- 不以产品名或商标暗示本项目与原产品存在官方关系。

“仅用于学习”“不商用”以及在自己的 GitHub 账号中保存副本，都不会自动取得再发布权。如果来源不清楚，应停止使用该材料，只保留能够独立表达和验证的行为需求。

仓库许可证仍是开放决策。在维护者确认前，不得添加 `LICENSE` 文件，也不接受外部代码贡献。

### 5.1 未核验材料的隔离

`UNVERIFIED-SRC-2026-03-31-A` 当前为 `QUARANTINED`。其来源、Revision、许可证和
授权范围不能被仓库内证据独立核验，因此：

- 不得读取、搜索、分析或继续使用；
- 不得把历史研究结论写入活动 PRD、技术设计、矩阵、测试或代码；
- 不得把它称为“已授权源码”或把维护者口头说明当作可复现证据；
- S01 和当前 S02 的 `Authorized Snapshot ID` 写 `N/A - Not Used`；
- 只有满足 ADR-020 的证据条件并由新 ADR 接受后，才可重新评估。

ADR-018、ADR-019 只保留为历史，不是活动设计依据。S07 到来时必须使用公开来源和独立
场景重新研究 Context 方案。

## 6. 核心架构不变量

- 模型只能提出操作意图，是否执行由确定性的应用代码决定。
- Agent Runtime 掌握模型/工具循环、预算、取消和终止状态。
- `ModelGateway` 表示一个模型回合并返回原始 Tool Call；Spring AI 不得在 Core 背后自动运行整个循环。
- 内置 Tool、MCP Tool 和 Plugin Tool 必须经过同一个 `ToolExecutionPipeline`。
- Pipeline 负责参数校验、生命周期事件、权限、审批、执行、截断、脱敏和结果转换。
- Tool Call ID 与对应的 Tool Result ID 必须准确匹配并保持协议顺序。
- CLI、未来桌面端和 SDK 只消费事件，不承载 Agent 决策。
- Core 和 Domain 类型不得依赖 Spring AI、Reactor、Picocli、JLine、文件系统或持久化框架类型。
- Permission 规则不能被描述成 OS Sandbox。
- README 中的能力声明必须与真实代码和矩阵等级一致。

初始模块依赖方向：

```text
cc-java-domain
        ↑
cc-java-core
    ↑           ↑
cc-java-model-spring-ai   cc-java-tools-local
             \             /
                 cc-java-cli
```

模块规则：

- `cc-java-domain` 保存框架无关的不可变协议和值对象；
- `cc-java-core` 负责 Runtime、Agent Loop、端口、Context、限制、生命周期和权限管线；
- `cc-java-model-spring-ai` 只负责项目协议与 Spring AI 之间的转换；
- `cc-java-tools-local` 实现项目 Tool 契约和本地执行安全；
- `cc-java-cli` 是 Composition Root 和终端适配器；
- 只有当前 Stage 确实需要时才创建新模块。

## 7. Stage 纪律

Stage 是可验证的学习切片，不表示后续能力不在项目范围内。

- S01-S04：Runtime Kernel、模型流式输出、仓库读取、受控 Patch 与命令循环；
- S05-S08：深度权限、Session、Checkpoint、Context、Instructions 与 Settings；
- S09-S11：Hooks、MCP、Skills 与 Plugins；
- S12-S13：Subagent、Worktree、后台执行与 Sandbox；
- S14-S15：生产级 Harness 和经过评测的独立创新。

不得在早期 Stage 中提前完整实现后期能力。应保留文档规定的扩展缝隙、记录延期差距并继续沿矩阵推进，不能把第一个可运行版本当成项目完成。

## 8. 安全规则

- 用户输入、仓库文件、模型输出、Tool 参数、命令输出和外部集成都属于不可信输入。
- 绝不能依赖 Prompt 实施访问控制。
- 每次文件操作前都要解析并验证真实路径。
- 拒绝路径穿越、绝对路径滥用、符号链接逃逸和 Windows Junction 逃逸。
- 保护敏感文件，并限制文件大小、结果数量、输出字节、回合数、调用数和运行时间。
- 不得把模型或用户文本直接插值到 Shell 字符串中。
- 应尽量使用结构化参数执行已批准命令，并固定工作目录、超时、输出上限和取消机制。
- 默认不得记录 API Key、完整 Prompt、完整源码文件或未经处理的敏感 Tool 输出。
- 密钥只能来自环境变量或外部 Secret Store，不能写入提交的配置。
- 不得加入真实公司端点、凭证、Schema、日志、工单、源码或未脱敏业务数据。
- Commit、Push、Merge、Release、Deployment 和外部系统写入需要单独、明确的用户授权。

如果便利性与安全边界冲突，应保留安全边界并记录取舍。

## 9. 变更流程

实现前：

1. 确认 Stage、Feature ID 和目标等级；
2. 阅读对应参考资料和验收条件；
3. 列出受影响的模块边界、协议和安全不变量；
4. 在架构选择被隐藏进代码之前记录 ADR；
5. 设计能够证伪当前理解的最小实验。

实现过程中：

- 保留用户已有且与当前任务无关的改动；
- 把适配器放在架构边缘；
- 避免推测性抽象和面向未来的空模块；
- 使用结构化错误和明确终止状态；
- 在每条新执行路径中传播预算、取消和生命周期事件；
- 在接入真实 Provider 前优先使用确定性的 Fake Model 和 Fake Tool 测试；
- JDK 或已有依赖足够时，不增加新依赖。

完成前：

1. 运行最小相关测试，并在可行时运行更广泛的模块测试；
2. 运行对应 Stage 的演示或行为对照；
3. 确认 Diff 中没有密钥、私有数据或受限制源码表达；
4. 更新矩阵等级和证据链接；
5. 更新能力声明并记录剩余差距。

## 10. 测试要求

Agent Loop 必须能够通过脚本化 Fake `ModelGateway` 在无网络、无 API Key 的条件下完成测试。

至少覆盖：

- 直接最终响应与流式结果聚合；
- 单轮和多轮 Tool 调用；
- 一个模型回合包含多个 Tool Call；
- Tool Call/Tool Result ID 精确匹配；
- 非法参数、未知 Tool、权限拒绝和 Tool 失败；
- 模型失败、空响应和不完整流；
- 回合、Tool、Token、输出和时间限制；
- 用户 Steering、取消和子进程清理；
- 进入对应 Stage 后的 Session 恢复、未完成副作用检测和 Context 压缩。

Tool 测试必须覆盖路径穿越、绝对路径、符号链接/Junction 逃逸、敏感文件、大小上限、输出截断和脏工作区保护。

真实模型测试必须显式启用，不作为普通 CI 的前提，不断言固定自然语言，也不得暴露凭证或私有仓库。

行为对照只能使用独立编写的任务和可观察结果，不得把受限制源码文本作为测试期望值。

## 11. 多 Tool Call 协议

当一个模型回合包含多个 Tool Call 时：

1. 只追加一次包含全部 Tool Call 的 Assistant Message；
2. 按当前 Stage 的顺序策略执行调用；
3. 为每个 Call ID 准确追加一个 Tool Result；
4. 整批调用到达明确终止状态后，才能请求下一个模型回合。

不得为每个 Tool 重复追加同一条 Assistant Message。

## 12. 中文注释与 Javadoc 规范

这是学习项目，代码不仅要能运行，还要能够帮助维护者理解成熟 Agent Harness 的设计。因此，重要模块、核心类和关键实体必须提供有信息量的中文说明。

### 12.1 必须使用中文 Javadoc 的对象

以下对象在创建或修改时必须使用标准 `/** ... */` Javadoc：

- 核心模块的主要包；使用 `package-info.java` 说明模块职责、边界和依赖方向；
- Agent Runtime、Agent Loop、Tool Pipeline、Permission、Context、Session、Lifecycle 等核心类和核心接口；
- Domain 中承担协议语义的实体、值对象、枚举和 `record`；
- Port、SPI、Adapter 的公共契约；
- 安全边界类、状态机、策略类、预算/取消控制和异常体系；
- 对外公开或会被其他模块调用的重要方法；
- 行为不直观、存在重要不变量或容易被误用的实现。

### 12.2 Javadoc 应说明什么

Javadoc 优先解释“为什么”和“契约”，而不是逐字复述代码。根据对象性质说明：

- 在整体 Agent Harness 中承担的职责；
- 明确不负责什么，以及与相邻模块的边界；
- 关键状态、不变量和终止条件；
- 输入输出语义、空值约定和所有权；
- 副作用、权限要求、线程模型和取消行为；
- 失败语义，以及调用者应如何处理；
- `record` 各组件的业务或协议含义；
- 必要的 `@param`、`@return`、`@throws` 和 `@since`。

示例：

```java
/**
 * 驱动单次 Agent Run 中的模型回合与工具回合。
 *
 * <p>该类型只负责状态迁移和终止判断，不直接访问文件系统、
 * 调用终端 UI 或执行具体工具。所有工具请求都必须交给
 * {@code ToolExecutionPipeline}。</p>
 *
 * @since 0.1.0
 */
public final class AgentRuntime {
}
```

### 12.3 注释质量要求

- 中文是项目代码注释和 Javadoc 的默认语言；类名、协议名和行业术语可以保留英文。
- 注释必须与实现同步；过期注释按缺陷处理。
- 不为简单 Getter、Setter、显而易见的构造器或一眼可读的语句添加机械注释。
- 不写“初始化变量”“循环列表”之类只复述语法的低价值注释。
- 复杂算法可以使用行内中文注释解释决策原因、边界条件和安全考虑。
- TODO 必须说明未完成原因、对应 Feature ID 或 Issue，以及可以删除 TODO 的条件。
- 不能用长篇注释掩盖命名混乱或职责过大的设计；应先改善代码结构。

代码评审时，核心实现缺少必要的中文 Javadoc，或注释不能帮助理解架构契约，均视为未完成。

## 13. 文档规范

- 所有文档使用 UTF-8 Markdown。
- 描述行为时使用 `FR-*`、`NFR-*` 和 Feature ID。
- 决策状态使用 `Proposed`、`Accepted`、`Open` 或 `Superseded`。
- 对版本敏感的参考结论添加日期或 Baseline ID。
- 框架和产品行为优先引用公开的一手官方文档。
- 明确区分观察到的行为、推断和本项目独立设计。
- 产品范围或能力改变时，同步更新 PRD、技术设计和功能对照矩阵。
- 只有在 Mermaid 能明显改善流程、状态或依赖关系表达时才使用。

## 14. 依赖与版本策略

已确认的技术基线只有 Java 21、Maven 3.9.16、JUnit 5.14.3 和 AssertJ 3.27.7。
Spring Boot、Spring AI、Picocli、JLine 与首个 Provider 的准确版本保持 Deferred，
只能在 S02 完成官方来源核验和真实 Provider/Streaming/CLI Spike 后通过 ADR 确认。

引入依赖时：

- 使用 Spring Boot Parent 或 BOM 管理 Boot 依赖；
- 单独导入 Spring AI BOM；
- 使用 Maven Wrapper；
- 优先选择 Maven Central 中的稳定版本；
- 从一个真实模型 Provider 和一个 Fake Gateway 开始；
- 在变更说明中解释每一个非测试依赖的用途。

## 15. Git 规范

- 使用聚焦的提交，Commit Subject 采用 `docs:`、`feat:`、`fix:`、`test:`、`refactor:` 等 Conventional Commit 风格。
- 除非维护者明确要求，否则不重写共享历史。
- 不提交构建产物、IDE 状态、密钥或本地模型配置。
- 除非用户明确授权，否则不执行 Push、Merge、Release 或其他外部系统变更。

## 16. 完成定义

一项变更只有同时满足以下条件才算完成：

- 达到声明的 Feature ID 目标等级；
- 能从公开需求和独立设计解释其行为；
- 模块依赖和执行管线保持文档规定的边界；
- 相关离线测试和 Stage 证据通过；
- 重要模块、核心类、关键实体和公共契约具有必要且准确的中文 Javadoc；
- 功能对照矩阵和能力声明准确；
- 项目进度状态已更新，`java scripts/ProgressDashboard.java --check` 通过；
- 剩余差距与风险被明确说明，而不是隐藏。
