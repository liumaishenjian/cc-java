# ADR-059：S11 Skill Catalog、调用、投影与恢复契约

- Status: Accepted
- Date: 2026-08-09
- Stage: S11 Skills + Plugins（G1-G2）
- Feature IDs: `SKILL-01`～`SKILL-07`、`CTX-14`、`TOOL-16`
- Current → S11 Exit Target: `SKILL-01..07 L0 → L2`；`CTX-14 L0 → L2`；`TOOL-16 L0 → L0`（不随 Skill catalog 自动提升）
- Depends on: ADR-039、ADR-043、ADR-052、ADR-058

## 决策

S11 使用“catalog metadata 常驻、正文/资源按调用加载”的独立路径：

```text
fixed Skill roots
  → metadata scan + validation
  → immutable SkillCatalogSnapshot
  → bounded metadata projection to model / slash catalog
  → explicit or model invocation
  → revalidate digest + load body/resources
  → narrow Tool set + register run-scoped hooks
  → transient Context Projection
  → unique Run terminal cleanup / Session digest
```

Skill 是可复用的 Markdown 工作流和受限资源包，不是 Tool executor、权限规则、Sub-Agent 或 Plugin Java 代码。

## 1. 目录、metadata 与 catalog

- 一个 Skill 固定为 `<skill-root>/<skill-name>/SKILL.md`；名称使用小写 ASCII 字母、数字和单连字符，1～64 字符。
- frontmatter v1 只接受：`name`、`description`、`invocation`（`explicit|model|both`）、`allowed-tools`、`resources`、`hooks`；未知/重复字段 Fail Closed。
- `description` 单行最多 512 code point；`allowed-tools` 最多 32 项；资源最多 32 项；Hook 绑定最多 16 项。
- 单 root 最多 128 个 Skill；所有 root 合计最多 256 个；单 `SKILL.md` 最多 128 KiB/4,000 行；catalog metadata 投影最多 64 KiB 或估算 16,384 token，先达到者生效。
- Catalog 只读取并严格解析 frontmatter、文件 identity、正文 byte range 和 SHA-256；启动时不 materialize 正文或资源。
- 同一规范调用名冲突时不采用“最后覆盖”，相关条目全部隔离并产生无正文诊断。
- Session 创建时固定不可变 `SkillCatalogSnapshot(snapshotId, orderedEntries)`；同 Session 中磁盘变化不改变已发布 catalog。

这些上限是 cc-java 的独立安全预算，不来自参考常量。

## 2. 显式与模型调用

- 显式入口为 `/skill-name [args]`，只允许 `invocation=explicit|both`；Slash parser 生成类型化 `SkillInvocationIntent`，不把字符串拼进 Shell。
- 模型入口为普通 `AgentTool`：只暴露 catalog 中 `invocation=model|both` 的名称/描述，调用仍经 `ToolRegistry → PermissionPolicy → Approval → ToolExecutionPipeline`。
- 两个入口最终都调用 `SkillInvoker.invoke(snapshotId, skillId, args, runId)`；必须命中当前 snapshot，重新验证 `SKILL.md` identity/digest 后才加载正文。
- S11 禁止 nested/reentrant Skill：Skill 正文、资源、Hook 或已激活 Skill 触发的模型回合均不能再次激活 Skill。单个 Run 可以按稳定请求顺序激活多个**不同** Skill，但每个规范 skillId 至多成功激活一次；重复或递归请求返回结构化拒绝且不改变既有 Scope。
- 模型入口的 Skill Tool 只有在调用成功、正文已通过 digest Gate 并完成 transient Projection 后才提交激活；Tool 成功之前不得收窄 Tool visibility、注册 Hook 或写入 `skill.invoked` durable 事实。
- 模型不能猜中未投影、disabled、冲突或 explicit-only Skill；显式入口也不能绕过 digest、资源、权限或 Recovery Gate。
- S11 不做 fuzzy/embedding/远程搜索，不提升 `TOOL-16` 或 `MCP-08`。

## 3. Context Projection 与 `allowed-tools`

- Skill 正文作为版本化、明确 `untrusted=true` 的 transient `SkillContextMessage` 投影到本次 Run 后续模型请求，不写成 System/Assistant/Tool Result。
- 投影包含 skillId、snapshotId、content digest、调用方式和有界 Markdown；不得携带绝对路径。
- `allowed-tools` 的语义为交集：

```text
effectiveVisibleTools = runtimeVisibleTools ∩ skillAllowedTools
```

  缺省表示不额外收窄；空列表表示当前 Skill Scope 不可见任何 Tool。多个已激活 Skill 的收窄按稳定激活顺序累积求交集；它不能新增 Tool、预计算 Permission、自动 Allow、创建或缓存 Grant，也不能覆盖 Deny/PLAN/Hard Denial。每个真实 Tool Call 都必须在调用当时重新执行 S05 `Permission → Approval → ToolExecutionPipeline`，不能把规则或 Session Grant 结果缓存成静态 Tool 集合。
- 第一个 Skill 正文成功投影后建立 Run Scope，后续成功激活的不同 Skill加入同一 Scope；Scope 持续到当前 Run 的唯一终态，而不是在单个 Skill 内容处理后提前恢复。异常、取消、fence 和唯一终态必须在 `finally` 清理 Tool visibility 与全部 Skill Hook lease。

## 4. Bundled resources

- `resources` 只允许声明 Skill root 内的相对普通 UTF-8 文件；拒绝绝对路径、Traversal、Symlink/Junction、目录、设备、二进制和嵌套 archive。
- 单资源最多 256 KiB；单次调用最多加载 1 MiB；只在正文明确引用或调用请求指定时加载，不随 metadata scan 常驻 Context。
- 资源形成带 relative logical name、digest 和 `untrusted=true` 的 transient message；不能执行脚本，也不能注册 Tool/Hook/MCP。
- 模型若需执行资源中的命令，仍必须主动提出已有 `run_command` Tool 并经过准确审批；资源文件本身从不自动执行。

## 5. Run-scoped Hooks

- Skill frontmatter 只能引用当前 snapshot 中已验证的 S09 Hook binding 模板；正文成功投影后复制为 `RUN` scope，并绑定 `sessionId/runId/skillInvocationId`。
- Hook 从正文成功 Projection 后持续到**当前 Run 唯一终态**；它不在 Skill Tool 返回成功时提前注销。Run 取消/失败、Session fence 或终态清理时统一注销，禁止泄漏到下一 Run。
- Skill Hook 仍受原来源 fingerprint/trust、timeout、failure policy、loopback/Command 安全和 S09 聚合语义；Skill 不能通过 frontmatter把未信任 Handler 标成 trusted。

## 6. Session digest 与恢复

- S06 journal 只追加聚合 `skill.invoked` / `skill.completed` 事实：skillId、snapshotId、manifest/body/resource digest、invocation kind、effective tool-name digest、hook-set digest 和固定终态；不保存完整正文/资源、绝对路径或参数自由文本。
- Canonical Transcript 仍保存用户任务与模型语义；Skill 正文是 Projection，不作为永久用户事实重复写入。
- Resume/Fork 加载 journal 后，从当前受信 Skill roots 构建恢复 snapshot，并验证历史 invocation digest；缺失/变更/冲突时标记 `SKILL_RECOVERY_MISMATCH`，不得悄悄使用新正文。
- S06 Resume/Fork 打开时不存在活动 Run，因此**不自动恢复**任何 Skill Hook lease、Tool visibility Scope 或未完成 Skill activation；新 Run 从默认 runtime visibility 开始。历史已完成 Skill 只通过有界 digest/结果摘要参与后续 Context；恢复绝不自动重放 Skill、Hook 或任何 Tool 副作用。
- 同一活动 Run 内发生 compaction 时，只有匹配当前 immutable snapshot 的已激活 Skill 才能重建 Projection，且不增加激活次数、不重复注册 Hook；Run 终态后不再恢复 Scope。

## 7. 独立 Java 契约

```text
SkillDescriptor / SkillCatalogSnapshot / SkillCatalogEntry
SkillInvocationIntent / SkillInvocationKind / SkillInvocationScope
SkillContentSnapshot / SkillResourceSnapshot / SkillProjection
SkillCatalogLoader / SkillContentLoader / SkillInvoker
SkillToolScopeNarrower / SkillHookLease / SkillRecoveryVerifier
```

Domain/Core 类型必须不可变、框架无关，并具有解释职责、非职责、权限、取消、失败和恢复语义的中文 Javadoc。Path、JSON/frontmatter parser 和文件 snapshot 位于 CLI/本地 Adapter；Core 不依赖文件系统。

## 8. 被否决方案

- 启动时把所有 Skill 正文放入 System Prompt；
- 允许 Skill 的 `allowed-tools` 添加或自动批准 Tool；
- 把 Markdown 内命令当作安装期/调用期脚本自动运行；
- 将 Skill 正文永久复制到 Canonical JSONL；
- digest 不匹配时自动升级到磁盘新版本；
- 在 S11 用 fork/sub-agent 执行 Skill；该能力属于 S12。

## 9. 可证伪验收

至少覆盖 metadata-only 文件读取计数、256 Skill catalog ceiling、名称冲突、正文 digest 竞态、explicit/model 双入口、explicit-only/model-only 负例、nested/reentrant 拒绝、单 Run 多个不同 Skill 的稳定顺序与每项至多一次、模型 Tool 成功前 Scope/Hook 为零、可见 Tool 两集合交集属性测试、每次 Tool Call 重新执行 S05 Permission/Approval、Deny/PLAN/Hard Denial、资源逃逸/超限/恶意内容、Hook 持续到 Run 唯一终态并仅清理一次、cancel/fence、活动 Run compaction 后 Projection、无活动 Run 的 Resume/Fork 不恢复 Scope/Hook、digest 匹配与不匹配、journal 隐私 sentinel。全部通过 G3/G4 前 Capability Level 保持 L0。
