# ADR-049：S08 显式 Workspace 文件引用与附件投影

- Status: Proposed
- Date: 2026-08-07
- Stage: S08 Instructions + Settings（Supplementary / Reopened）
- Capability IDs: `CLI-13`、`CTX-19`；回归 `CLI-07`、`CLI-09`、`CTX-03/04/06/07/10`、`SESSION-02/03/04/05`、`TOOL-03`、`SEC-01/02/03/04`
- Current → Exit Target: `CLI-13` L1→L2、`CTX-19` L1→L2；其余只做不降级回归
- Reference Behavior Baseline: `R2026.03`，`REF-02`（2026-08-07 访问；公开交互只作为候选行为）
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Observed` 授权机制研究 + `Inferred` 独立 Java/TypeScript 契约；未提交实现已完成 G3-G5，等级仍待 Commit-scoped G6
- Amends: [ADR-043](./ADR-043-s07-context-projection-compaction.md)、[ADR-047](./ADR-047-s08-g2-architecture-contract.md)、[ADR-048](./ADR-048-s08-corrective-composer-model-diagnostics.md)

## 决策

S08 增加显式文件引用切片。用户在 Composer 中使用 Workspace-relative `@path` 或
`@"path with spaces"`，可附加 `#Lstart` 或 `#Lstart-end`。TUI 只负责请求和展示候选，Java
Headless 在提交边界重新解析、验证并读取；候选从不构成访问授权。成功解析的文件成为
`UserMessage` 的结构化、不可变附件快照，随 Canonical Transcript 和项目自有 Session JSONL
保存，并在每次模型请求中以固定的“不可信文件上下文” envelope 投影。不得由 TUI 直接读取
文件，不得把 `@path` 简单替换为无来源的大段字符串，也不得让附件扩大 Permission。

S08 在新实现 Commit 完成 G0-G6 前标记为 Supplementary / Reopened。此前 S08 Accepted
Commit 及证据保持有效历史，但不能证明 `CLI-13` 或 `CTX-19`。

## 1. 受控机制研究结论

对 `AUTH-SRC-2026-07-29-A` 的只读研究只保留以下抽象结论，不复制参考函数体、名称、布局、
文案、Prompt 或常量：

| 分类 | 可独立表达的结论 | 本项目采纳/偏离 |
| --- | --- | --- |
| Observed | `@` 输入触发异步文件候选，迟到候选不能覆盖较新的查询 | 采纳请求关联、去抖和 stale discard；候选由 Java 产生 |
| Observed | 提交阶段重新解析引用，并把文件内容与普通用户文字分开建模 | 采纳结构化 Domain 附件；拒绝只在 UI 拼接文本 |
| Observed | 文件读取复用既有只读安全/权限路径，并对过大或不可读输入降级 | 采纳 WorkspaceGuard 和有界读取；显式非法引用 Fail Closed，不静默丢失 |
| Observed | 重复文件可去重；行范围属于引用语义 | 采纳稳定首见顺序、`#Lx[-y]` 与确定性上限 |
| Inferred | 补全索引与权威读取必须是不同职责 | 采纳 suggestions-as-hint；提交时重新 realpath/identity 校验 |
| Unknown | 准确发行版本、内部附件格式、阈值、缓存和全部跨平台语义 | 不采纳；由本 ADR 独立定义并测试 |

## 2. 用户语法与解析边界

只有位于输入开头或空白之后的 `@` 才开始文件引用：

```text
@src/main/App.java
@"docs/design notes.md"
@src/main/App.java#L20
@src/main/App.java#L20-80
```

- 邮箱、标识符内部的 `@`、反斜杠转义的 `\@` 和 Slash 命令名字不触发引用；
- 未闭合引号只在编辑阶段用于候选查询，提交阶段拒绝；
- 路径必须相对 Workspace；绝对、UNC、drive-relative、Traversal、空路径和 NUL 拒绝；
- 只支持普通 UTF-8 文本文件；目录、设备、二进制、非法 UTF-8、Symlink/Junction 逃逸和敏感路径拒绝；
- 同一规范路径与行范围重复时只保留首个；不同范围是不同附件；
- 一次输入最多 8 个引用，超限整次提交拒绝且不创建 Run。

显式引用失败必须发生在 `AgentRuntime.run`、`Session.appendUser` 和任何模型请求之前，返回固定
安全 code；错误不得包含绝对路径、文件内容、原始异常或敏感设置。TUI 在 Java 接受前保留
pending Composer，协议拒绝时恢复原草稿。

## 3. 权威读取与 TOCTOU

Java Adapter 使用既有 `WorkspaceGuard.requireRegularFile`，并执行以下序列：

```text
解析相对路径/行范围
→ WorkspaceGuard + 敏感策略
→ NOFOLLOW 属性、realpath 与普通文件确认
→ 有界 UTF-8 读取
→ 再次读取属性、realpath 与 identity
→ 比较 before/after identity、size、mtime、realpath
→ 计算 SHA-256 并创建不可变附件
```

任一变化均丢弃候选并拒绝整次提交。单附件最多 500 行和 64 KiB UTF-8，总附件最多
192 KiB；读取不得先无界加载再裁剪。起始行超过文件末尾、反向范围拒绝；结束行超过末尾或
行/字节预算时只返回实际有界内容并标记 `truncated`。内容摘要只在
Domain、Session 与 Context 内使用；普通诊断最多显示相对协议路径和固定 code。

## 4. Domain、Canonical 与 Session

`UserMessage` 扩展为自然语言正文加不可变 `UserFileAttachment` 列表，同时保留 text-only
构造器以兼容已有调用。附件至少包含：

```text
protocolPath, content, sha256, startLine, endLine, truncated
```

附件是用户该次提交时的内容快照，而不是后续对磁盘的活引用。`run.started` JSONL 在同一记录
中保存有界附件数组；旧记录缺少该字段时按空列表恢复。Resume/Fork 使用持久快照，不重新读取
磁盘，避免历史语义漂移；新提交仍重新验证当前文件。Schema 读取严格拒绝未知类型、重复/缺失
字段、非法 digest、条目/文本超限和不一致行范围。

Context Token estimator 必须计入附件内容；C1-C4 可以在压力下把旧附件作为完整用户回合的一部分
摘要或移除，但不能产生 Tool 协议孤儿，也不能修改 Canonical。Spring AI Adapter 将正文和附件
编码为固定项目 envelope，明确 `untrusted=true`，保留相对路径、范围、digest 与正文；它不执行
文件读取，也不允许文件内容伪造 Assistant/Tool 消息。

## 5. stdio 与 TUI 补全

内部 stdio v0 增加：

```text
Command: file.suggest { query }
Event:   file.suggestions { query, candidates }
```

命令只在初始化后的 Session 接受，不创建 Run、不写 Canonical/JSONL、不刷新 Settings。`query`
最多 256 个 code point；候选最多 32 个原始安全 Workspace-relative 协议路径，完整事件编码最多
8,192 bytes。Java 有界扫描不跟随链接，过滤敏感路径，排序固定为前缀、包含、稳定路径字节序；
TUI 按空格或 `#L` 把协议路径格式化为引号/非引号 mention，避免 Java 与 UI 混用展示语法。

TUI 对查询去抖；每个请求具有唯一 request ID，只接受与最新 token、Session 和 request 匹配的一次
结果，迟到、重复、未知字段或超限结果 Fail Closed。Up/Down 选择，Tab/Enter 接受，Escape 关闭；
接受只替换光标处活动 token，保持多行文本、grapheme 光标、Paste payload、History 和 viewport。
接受后下一次 Enter 才提交。Run 活跃时同样生成 Steering；TUI 不调用 Node 文件系统。

## 6. 被否决方案

- **TUI 直接扫描/读取 Workspace**：形成第二套安全边界且破坏 Java Headless 权威，否决。
- **把 `@path` 原样交给模型期待其调用 `read_file`**：行为不确定且没有附件快照/恢复语义，否决。
- **提交时直接把内容拼入用户字符串**：丢失 provenance、digest、Session schema 和预算归属，否决。
- **非法附件静默忽略**：用户会误以为文件已进入 Context，否决。
- **Resume 时重新读取磁盘**：历史消息含义随工作树变化，否决。
- **目录递归附件、图片/PDF、多媒体和 Workspace 外路径**：扩大范围与预算，延期。

## 7. 可证伪验证

G3-G5 至少覆盖：

1. 普通/带空格/CJK 路径、行范围、重复引用和正文保留；
2. 绝对/Traversal、敏感路径、Symlink/Junction、目录、二进制、非法 UTF-8、读中替换；
3. 数量、行、单文件/总字节、query/candidate/event 上限；
4. 无效引用时零 Run、零模型请求、零 Session 追加，草稿恢复；
5. JSONL round-trip、旧记录兼容、Resume/Fork 不重新读取和损坏 schema Fail Closed；
6. Token 估算、C1-C4、摘要、Tool pairing 与 typed overflow 回归；
7. stdio 未初始化/未知字段/乱序/重复/stale 请求及零 Session 副作用；
8. TUI token replacement、引号、grapheme、多行、completion 优先级、Steering、pending ack；
9. Spring Adapter envelope 不允许附件正文形成 Provider Tool Result；
10. 完整 Reactor、TUI、launcher、真实 TTY Demo、进度看板和独立最终 Review。

## 8. 延期边界

本切片不实现目录附件、图片/PDF、IDE Selection、MCP Resource mention、跨 Workspace 文件、
glob mention、持久候选索引、附件编辑器、稳定外部 API 或 S14 Export/Migration。文件引用不等于
Permission Allow，更不是 OS Sandbox。
