# ADR-042：S07 授权 Context / Memory 机制研究采纳边界

- Status: Accepted
- Date: 2026-08-04
- Stage: S07 Context Engineering
- Capability IDs: `LOOP-11`、`CTX-06/07/08/09/10/11/12/13/17/18`、`OBS-04`
- Current → S07 Exit Target: `L0 → L2`（本 ADR 仅完成 G0-G2，不提升等级）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 公开资料为 `Documented`；授权快照机制为 `Observed / Inferred / Unknown`；本项目采纳边界为 `Documented`

## 背景与 G0-G2 结论

S06 已把聚合语义消息、Tool Call/Result 配对和恢复事实写入项目自有 append-only JSONL。
S07 要在不改写这份 Canonical Transcript 的前提下，为每次模型请求构造有界 Context Projection，
并增加可跨 Session 使用的本地文件记忆。按照 ADR-022，成熟 Context 与 Memory 机制在设计前完成了
仓库外只读研究；历史 ADR-019 仍为 `Superseded`，本 ADR 不恢复其具体表达、阈值或实现布局。

本变更冻结 S07 G0-G2：G0 来源与授权边界通过，G1 Feature、目标等级和可证伪实验通过，G2
采纳 ADR、独立契约与安全边界通过。G3-G6 保持 Open；没有生产实现、Capability Level 提升或
Stage Exit 声明。

## 公开一手资料（Documented）

本 ADR 只使用 `R2026.03` 固定 Manifest 中与 S07 直接相关的以下来源：

### REF-02

- URL：<https://code.claude.com/docs/en/how-claude-code-works>
- 访问日期：2026-08-04
- 页面标题：`How Claude Code works`
- 相关小节：`The context window`、`When context fills up`
- `Documented`：Context 会随对话历史、文件内容和 Tool 输出等输入增长；接近容量限制时，产品会先
  清理较旧 Tool 输出，并在需要时摘要对话；用户可以查看 Context 使用量并显式请求压缩。
- `Unknown`：`R2026.03` 建立时的历史页面内容、产品内部数据结构、准确触发阈值、摘要 Prompt、
  清理与摘要的调度算法，以及本项目 C1-C4 是否对应其内部实现。

### REF-05

- URL：<https://code.claude.com/docs/en/memory>
- 访问日期：2026-08-04
- 页面标题：`How Claude remembers your project`
- 相关小节：`CLAUDE.md vs auto memory`、`Auto memory`、`Storage location`、`How it works`
- `Documented`：Session 从新的 Context 开始，`CLAUDE.md` 与 auto memory 可跨 Session 提供信息；
  auto memory 使用本地文件，包含 `MEMORY.md` 入口和可选 topic 文件；启动时有界加载入口内容，
  topic 文件按需读取。
- `Unknown`：`R2026.03` 建立时的历史页面内容、内部存储格式、相关性评分、并行召回调度、
  ready-only 零等待预取，以及本项目 M1-M5 是否对应其内部实现。

Claude Agent Memory、Claude API Context Editing 与 Claude API Compaction 页面未登记在
`R2026.03` 固定 Manifest 中，本 ADR 不以这些页面形成 `Documented` 证据，也不据此修改 Manifest
或新增 Baseline。Secret 不得进入记忆是 cc-java 的独立安全约束，不归因于上述两项公开来源。
公开页面未归档内容指纹，因此以上最小结论只记为 `Documented`，不冒充 `Observed`；页面变化必须
通过后续 Baseline 或 ADR 重新评估。

## 受控研究范围

只读研究覆盖模型请求前的 Context 组装、容量判断、旧 Tool 输出清理、滚动记忆、全量摘要、文件
记忆存储、索引、目录扫描、相关记忆检索，以及查询启动时的并行准备。研究只提炼职责、状态转换、
失败边界和验证方法；未复制或逐行翻译函数体、Prompt、注释、错误文案、私有名称、文件布局、
实现常量或内部数据格式。参考字节未进入仓库、Fixture、Golden Output、依赖或发布物。

## Observed

1. 模型请求所见 Context 与持久会话事实分离；压缩结果服务后续请求，但不要求改写规范 Transcript。
2. 大载荷减压、旧 Tool 输出清理、滚动记忆和全量摘要由不同职责承担，并按压力与可用输入选择，
   不是每次请求都固定执行全部步骤。
3. Tool 历史缩减以完整协议关系为边界；清理结果不能留下孤立 Tool Call 或 Tool Result。
4. 文件持久记忆具有存储、入口索引、可发现文件集合、相关内容召回和请求投影等分离职责。
5. 查询准备可以与其他启动工作并行；相关记忆准备失败或迟到时，主模型请求仍可继续。
6. Context 使用量、压缩发生和恢复结果可被观察，但展示事件不等同于 Canonical Transcript。

## Inferred

1. S07 应让 Core 持有纯投影规划与条件式 Reducer，文件、模型摘要和计数能力通过 Port 注入；
   Domain/Core 不依赖 `Path`、JSON、FileLock、Spring AI、Reactor、Ink 或 Node 类型。
2. 每次 Projection 都从 S06 Canonical Transcript 和只读附加输入重新构建；失败或取消只丢弃候选
   Projection，不回写规范历史。
3. Context Reduction 应采用 C1-C4 条件图；仅在当前压力、收益估计和协议约束允许时选择一个或多个
  策略，并在预算满足后停止。
4. 文件记忆应是可重建、可裁剪的本地投影输入；索引或目录损坏可以降级为空，但不得使 Session
   journal 被改写或获得执行权限。
5. 并行预取必须是零等待消费：消费时只读取已经完成的结果；未完成、超时、取消、失败均立即按空
  结果继续，迟到结果不得注入已经发送的模型请求。

## Unknown

- 快照对应的准确产品 Revision、发行版本、许可证和权利人；
- 参考内部摘要 Prompt、阈值、评分公式、目录布局、文件格式和稳定兼容承诺；
- 参考实现对不同模型、超长会话和全部 Tool 类型的质量上界；
- 文件记忆的内部保留、迁移、冲突和多主机一致性策略；
- 预取在所有调度器、取消竞态和慢存储下的完整时序保证。

这些 Unknown 不作为 S07 常量、格式或测试 Oracle。本项目的 200 行、25KB、200 topic 文件等上限
均是独立安全预算，不声称来自参考实现。

## 采纳、偏离与延期

S07 采纳：Canonical Transcript/Projection 分离、条件式 C1-C4、协议成对、文件记忆 M1-M5、
零等待预取、可解释 Usage、一次 Overflow 恢复和防抖。独立契约分别由 ADR-043、ADR-044 固定。

有意偏离与延期：

- 项目指令继续使用根 `AGENTS.md`；S07 不复制其他产品的指令文件名或层级规则；
- 持久记忆入口使用 `MEMORY.md`，默认目录为
  `~/.cc-java/projects/<repository-id>/memory`，语义类型使用本项目自有
  `USER_PROFILE / WORKING_GUIDANCE / PROJECT_STATE / REFERENCE_POINTER`；
- S08 延期用户/项目/目录分层 Instructions、Settings 优先级、完整 `/compact`/`/context` Slash
  Command Surface 和持久配置；
- S12 延期 Sub-Agent Context 隔离、后台 Agent、并行 Tool、任务系统和 Worktree；零等待记忆预取
  不是 Sub-Agent 或后台 Agent；
- S14 延期稳定 Export/Retention/Migration、SQLite/大规模索引、Provider Cache Hint、原生 Context
  Editing 和跨版本兼容；
- S13 OS Sandbox 不由 Permission、Memory、Checkpoint、FileLock 或 Context Reduction 替代。

## 安全与隐私边界

- 用户输入、Workspace 文件、Tool/模型输出、记忆文件、索引和摘要均是不可信输入；记忆内容只影响
  模型 Context，不能提升权限、扩大 Workspace、注册 Tool 或绕过 `ToolExecutionPipeline`。
- 所有文件记忆访问必须约束到解析后的 repository memory root，拒绝绝对路径、Traversal、Symlink
  与 Windows Junction 逃逸，并实施普通文件、UTF-8、大小和数量上限。
- 不持久化 API Key、密码、Token、端点、完整 Prompt、完整源码文件、未经裁剪 Tool 输出或其他
  Secret；疑似 Secret 候选必须拒绝持久化而不是脱敏后猜测保存。
- 摘要失败、空摘要、取消、摘要模型返回 Tool Call 或协议不完整时，不提交压缩边界；模型只能提出
  内容，确定性应用代码决定是否采用。
- Context/Memory 不是授权来源、审计真相、Checkpoint、事务文件系统或 OS Sandbox。

## 可证伪验证

G3-G5 实现阶段至少以离线 Fake 和长会话回放证明：

1. 相同 Canonical Transcript 在 Resume 前后生成确定且兼容的 Projection，规范 JSONL 字节不变；
2. C1-C4 按条件选择而非固定串行，低压力不压缩，预算满足即停止；
3. 所有缩减后 Tool Call/Result 孤儿数为 0，多 Tool Call 批次顺序不变；
4. 空摘要、失败、取消、Tool Call 摘要和同次 Overflow 第二次恢复均 Fail Closed；
5. 重复压力受 Thrashing Guard 限制，并产生唯一、隐私安全的 reduction outcome；
6. 事实保持率、约束保持率、任务完成率和 Token 降幅达到 ADR-043 固定的退出阈值；
7. Memory 路径逃逸、链接、超限、损坏索引、重复 topic、Secret 候选和并发冲突被拒绝或安全降级；
8. 慢预取、失败、取消和迟到结果不增加模型请求关键路径等待，且迟到结果不进入已发送请求；
9. `/context` 所需 Usage 投影能解释各来源占用，但不泄漏完整 Prompt、源码或记忆正文。

## 停止条件

若授权范围被撤回、快照身份变化、研究结论无法与参考表达分离，或实现需要复制参考字节、内部格式、
Prompt、命名、常量或布局，立即停止使用该材料。本 ADR 只接受 G0-G2 设计边界，不表示 S07 已实现。
