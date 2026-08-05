# ADR-044：S07 文件记忆与零等待相关记忆预取契约

- Status: Accepted
- Date: 2026-08-04
- Stage: S07 Context Engineering
- Capability IDs: `CTX-17`、`CTX-18`（并支撑 `CTX-06/09/10`）
- Current → S07 Exit Target: `L0 → L2`（G0-G2 冻结，不提升等级）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Documented / Observed / Inferred / Unknown` 见 ADR-042；本 ADR 为 cc-java 独立 `Documented` 设计

## 决策

S07 引入项目范围的本地文件记忆，用于跨 Session 提供可重建的 Context 输入。入口文件固定为
`MEMORY.md`，默认根目录为：

```text
~/.cc-java/projects/<repository-id>/memory
```

`repository-id` 必须由规范 Workspace 身份经过不泄漏绝对路径的稳定派生得到；原始绝对路径不得成为
目录名、普通日志或模型 Context。记忆不属于 S06 Canonical Transcript，不是权限、审计或执行事实；
删除或重建索引不能改变 Session 事实。

## 记忆语义

每条记忆使用本项目自有类型：

| 类型 | 内容边界 |
| --- | --- |
| `USER_PROFILE` | 与用户长期工作方式相关且经用户提供/确认的稳定信息 |
| `WORKING_GUIDANCE` | 对协作方式的纠正、偏好和确认方法，包含为什么及如何应用 |
| `PROJECT_STATE` | 不能仅从仓库或 Git 历史可靠推导的持续目标、约束与外部状态 |
| `REFERENCE_POINTER` | 外部文档、Issue、Dashboard 等指针及其用途，不复制大段内容 |

不得保存仓库已经记录的代码结构、提交历史、当前 diff、一次性对话细节或可重新读取的源码全文。新候选
与既有 topic 重复时更新既有文件；发现错误记忆时删除或替换，不能并列保留互相冲突的“事实”。

## M1-M5 分层

| 层 | 责任 | 独立安全预算 |
| --- | --- | --- |
| M1 Storage | 一个 topic 一个 UTF-8 Markdown 文件，带受限 frontmatter 和正文 | topic 文件总数最多 200；单文件使用独立字节上限 |
| M2 Index | `MEMORY.md` 一行一个可读链接与 hook，作为默认投影入口 | 最多 200 行且最多 25KB，先达到者生效 |
| M3 Catalog | 有界扫描 topic 元数据，形成可重建目录 | 最多 200 个 topic；超限、重复、损坏均显式诊断 |
| M4 Recall | 根据当前任务从 Catalog 选择少量相关 topic | 只读、有界、可取消；失败降级为空 |
| M5 Projection | 把已经完成且通过校验的记忆片段注入模型请求 | 有独立 token 预算、来源标签和去重 |

上述 `200/25KB/200` 是 cc-java 的防滥用和可解释性上限，不来自参考实现常量。M2/M3 都是派生数据；
损坏时可以从 M1 重建，不能把损坏内容写入 Canonical Transcript。

## 文件格式与持久边界

Topic 文件使用项目自有 frontmatter：

```text
---
kind: USER_PROFILE | WORKING_GUIDANCE | PROJECT_STATE | REFERENCE_POINTER
name: <stable-kebab-case-topic>
description: <bounded one-line recall hook>
updated-at: <ISO-8601 date>
---

<bounded Markdown body>
```

- `name` 不是任意路径，只允许小写 ASCII 字母、数字与单个连字符组成的 kebab-case slug，最多 64 个字符；实际文件名由应用代码生成并在根目录内解析。
- G3-A 的 M1/M3 独立保守上限为：单 topic UTF-8 最多 64KB、最多 2,000 行，frontmatter 结束标记必须在前 16 行内，`description` 最多 512 个 Unicode Code Point 且必须单行，`updated-at` 使用 ISO-8601 date；这些是 cc-java 自有防滥用常量，不来自参考实现。
- M1 写入采用同目录暂存与提交前重检；创建将已 `force(true)` 的最终暂存内容通过同目录硬链接一次性 create-only 发布，不再执行后续 Move，更新才使用原子 Move 且必须带读取时 digest。硬链接不支持或竞态目标已存在时 Fail Closed，不回退复制或覆盖。
- M1 删除先把目录项原子 claim 到同目录 tombstone 并复验 identity/digest；不匹配时恢复，恢复碰撞或失败时保留可恢复对象，绝不删除未验证替换。
- M2 在 M1 成功提交后重建并原子替换；索引失败不回滚已验证 M1，但产生诊断并在下次启动重建。
- 自动候选只能保存用户明确提供/确认或可独立验证的高价值信息；模型输出本身不是可信事实来源。
- S07 只定义普通本地文件持久化，不承诺稳定 Export、Retention、Migration、SQLite、云同步或多主机一致性。

## 独立 Java 契约

截至 2026-08-05，G3-A/B/D1/D2 已实现下列 M1-M5 Domain/Core 离线契约、本地文件 Adapter、ready-only Prefetch、AgentRuntime/Core Projection seam 与真实 Headless 文件系统装配：

```text
MemoryKind
MemoryTopic(name, kind, description, body, contentDigest, updatedAt)
MemoryCatalog(entries, diagnostics, revision)
RecallQuery(repositoryId, userText, boundedKeywords, tokenBudget)
MemoryProjection(items, estimatedTokens, catalogRevision)

MemoryRepository.loadIndex() / loadTopic() / saveTopic() / deleteTopic()
MemoryCatalogBuilder.rebuild()
RelevantMemoryRecall.start(RecallQuery, CancellationToken) -> MemoryPrefetch
MemoryPrefetch.consumeReady() -> MemoryProjection
MemoryProjector.validateAndProject(readyItems, budget) -> MemoryProjection
MemoryPrefetchFactory.start(UserMessage, CancellationToken) -> MemoryPrefetch
MemoryContextService.start()/consumeReady() -> short-lived MemoryContextMessage
```

- Core 只依赖 `MemoryRepository`/`RelevantMemoryRecall` Port 和不可变值；文件路径、frontmatter 解析、原子
  Move、锁与真实路径校验位于本地 Adapter。
- `MemoryPrefetch.consumeReady()` 必须是立即返回操作，不允许等待 Future、锁、文件 I/O 或模型调用。
- Prefetch 可以在请求准备早期启动，但 M5 只消费调用时已经成功完成、revision 仍有效的结果。
- D1 的 `AgentRuntime` 在当前有界 `UserMessage` 已进入 Canonical Session 后、调用非记忆 `ContextAssembler` 前创建每回合新句柄；在 `ContextPreparationService`/Gateway 前的唯一消费点只调用一次 `consumeReady()`，finally 仅传播非阻塞取消，不拥有或关闭 Adapter Executor。
- 非空 ready 投影以 `MemoryContextMessage` 紧邻插入当前 `UserMessage` 之前；System、历史、完整 Tool batch 与 `toolDefinitions` 保持原顺序。该消息和 Provider envelope 都不包含 Path，固定标记 `source=project-file-memory`、Catalog revision 和 `untrusted=true`，正文及标签使用 UTF-8 Base64。
- `CodePointContextTokenEstimator` 把该消息的来源、revision 和条目字段计入独立 `memoryTokens` 及 `totalTokens`；Memory Context 仍不得写回 Session/Journal 或改变 Permission Pipeline 决策。
- D2 的真实 Provider Headless Composition 通过 `MemoryStorageLayout` 固定使用 `<user.home>/.cc-java/projects/<sha256(canonical workspace)>/memory`；`user.home` 只在包级 Composition seam 内部读取，不进入公开 `HeadlessRuntimeOptions`，测试通过包级 seam 注入独立 home/root 和专属执行器，默认目录缺失时不创建。
- `FileMemoryPrefetchAdapter.start(...)` 只向保证非内联排队的执行器提交任务；Catalog 构造/重建、项目自有的 locale-independent 有界关键词提取（有序唯一、最多 32 项、每项最多 64 code point）、M4 选择、消费时 fresh Catalog/revision 检查、正文加载与 M5 投影均在 Adapter 专属虚拟线程任务内。上限固定为 20 topic 与 256 KiB，M4 选择后 topic 变化会由第二次 rebuild/revision Gate 拒绝旧计划。
- Memory Adapter 只在其他可失败 Headless 组件校验后创建，后续装配失败会立即关闭；Headless close 先对每 Session 专属 Executor 执行 `shutdownNow()` 且不等待，并以 `finally` 保持 Session Store 释放。缺失/非法 root、执行拒绝、取消、失败、零命中、stale/digest/corrupt/Secret candidate 均隐私安全降级为空。显式 Fake/no-provider Headless 构造器继续走 no-op，以保持既有离线调用边界。

## 零等待预取时序

```text
start recall ───────────────┐
assemble non-memory inputs ─┼─> consumeReady() ─> build Projection ─> send model request
                            └─ 未完成/失败/取消：立即返回 empty
```

1. Prefetch 启动不意味着请求必须等待；消费时未完成即视为空。
2. 已完成结果必须经过路径、Schema、大小、digest、Catalog revision、去重和 token budget 校验。
3. 超时或取消只终止召回工作，不终止主模型请求；失败以隐私安全分类观测。
4. 模型请求发送后，迟到结果只能供下一次请求重新评估，绝不能注入已发送请求或修改其历史。
5. 零等待只描述相关记忆准备，不实现 S12 Sub-Agent、后台 Agent、任务系统或并行 Tool Call。

## 安全与隐私

- 记忆、索引和 frontmatter 均为不可信输入；拒绝绝对路径、`..`、分隔符 slug、Symlink、Junction、
  非普通文件、非法 UTF-8、未知 kind、重复 name、超限字段和 topic 洪泛。
- 在每次读写和最终 Move 前解析真实路径并确认仍在 memory root；Windows 大小写、重解析点和竞态均
  纳入负例。
- API Key、密码、Token、凭证、Provider 端点、完整 Prompt、完整源码或未经裁剪 Tool 输出不得进入
  M1-M5；疑似 Secret 候选 Fail Closed，且诊断不回显原值。
- 记忆文本不能提升 S05 Permission、扩大 Workspace、注册 Tool、批准副作用、解除 S06 Recovery Gate
  或改变 Hard Denial。
- 用户可以检查、修正和删除普通记忆文件；S14 才定义稳定保留、导出、迁移和删除保证。

## 可证伪退出测试

1. repository-id 对同一规范 Workspace 稳定、不同 Workspace 隔离，且目录/日志不含原始绝对路径；
2. M1 创建/更新/删除与 M2 重建满足 digest 冲突和原子性；中途失败不产生半文件或错误索引指针；
3. M2 超过 200 行或 25KB、M3 超过 200 topic、单文件超限均确定性拒绝或有界裁剪并诊断；
4. traversal、绝对路径、非法 slug、Symlink、Junction、竞态替换、非法 UTF-8 和未知 frontmatter 被拒绝；
5. 四类 kind 往返、重复 topic 合并、错误记忆删除和损坏 Index/Catalog 重建可离线复现；
6. Secret Fixture（仅假值）不被保存，诊断和事件不回显候选正文；
7. 慢召回在主请求到达消费点时仍未完成，`consumeReady()` 立即返回空；模型请求启动时间不晚于无
   prefetch 对照允许的确定性调度误差；
8. 召回已完成时只注入相关、有界、去重且来源可解释的片段；失败、取消和 stale revision 降级为空；
9. 请求发送后的迟到结果不出现在该次请求，下一次请求必须重新按任务和 revision 判断；
10. Memory 文本中的“允许写入/绕过权限”等恶意内容只作为不可信 Context，执行决策仍由 Permission
    Pipeline 和 Tool Adapter 拒绝。

## 被否决方案与延期

- **把所有记忆正文常驻 Context**：不可扩展且放大不可信输入，否决。
- **为每次请求等待召回完成**：增加关键路径延迟并形成故障耦合，否决。
- **把记忆写入 Session JSONL**：混淆规范历史与可修正投影，否决。
- **使用绝对 Workspace 路径作目录名**：泄漏本机信息，否决。
- **S07 引入 SQLite/向量数据库/云服务**：不符合最小依赖与当前规模，延期 S14。
- **在 S07 实现分层 Instructions 或 Sub-Agent Memory**：分别延期 S08、S12。

G3-B/D1/D2 当前形成 M1-M5 的离线基础、安全回归、Core Runtime seam 与真实 Headless 文件系统生产装配：M4 确定性 manifest 相关选择、M5 revision/digest/预算 Gate、安全正文加载、ready-only 一次消费、零等待慢 Future、迟到结果隔离、每回合 fresh prefetch、短生命周期消息/Provider envelope、Canonical Session/Journal 排除、Permission denial 保持、memory token 分类、默认 hashed Workspace 布局与 Executor 无等待关闭均已有确定性证据；`CTX-17/18` 已有内部 Context View、deterministic Fake Demo/Eval 与 G3-G5 证据，但 Capability Level 仍为 L0，待 Commit-scoped G6 对账。本 ADR 不表示 S07 Stage Exit 或完整文件记忆 UX 已完成。

2026-08-05 新增的 C3/C4 Summary Foundation 不改变本 ADR 的文件记忆边界：摘要候选不得把 Memory
文本提升为权限、审计或执行事实，也不得把 ready-only 预取改成摘要关键路径上的等待。C3/C4 归
ADR-043 管理，M1-M5 与摘要候选分别维持独立 revision、digest、byte/token budget 和失败降级。
