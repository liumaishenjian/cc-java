# ADR-050：有界文本读取与跨平台精确编辑纠正

- Status: Proposed
- Date: 2026-08-08
- Stage: S03 Read Tools / S04 Write + Command（Corrective，能力等级不变）
- Capability IDs: `TOOL-06`、`TOOL-08`、`TOOL-12`；回归 `SEC-01/02/03/04`、`TOOL-13/14`
- Current → Target: `TOOL-06` L2→L2、`TOOL-08` L1→L1、`TOOL-12` L2→L2
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Observed` 机制研究 + `Inferred` 独立 Java 契约

## 决策

`read_file` 改为固定字节窗口的严格 UTF-8 行范围读取，不再为返回一页而加载整份文件。
它只保留被选中的行，并同时受扫描字节、页行数、单行字符、页字符、取消和 Tool deadline
约束。只有真正到达 EOF 才报告总行数/总字节；部分结果必须提供与实际已返回正文一致的
`nextStartLine`。相同 Session、路径、范围和文件身份的重复读取可以省略正文，但不得丢失
continuation。

`apply_patch` 使用双表示文本快照：模型匹配视图把 `CRLF` 和裸 `CR` 规范为 `LF`，提交视图保留
原始字节、UTF-8 BOM 和统一换行风格。匹配仍是空白敏感的精确匹配；写回只替换匹配区间，
LF/CRLF/BOM 必须 round-trip。混合换行或裸 CR 只有在不合成换行的单行替换中允许，否则
Fail Closed，不能顺手格式化无关行。

修改前要求同 Session 存在覆盖目标区域的可信 `read_file` 证据。证据按 Workspace 与 Session
隔离、总量有界并按 LRU 淘汰；写入后作废其他 Session 的旧证据。该缓存只减少模型在过期理解
上写入的概率，不替代 `WorkspaceGuard`、Permission、审批、提交前真实路径复验、原始字节冲突
比较或原子移动。

## 受控机制研究结论

只保留以下可独立表达的机制，不复制参考实现的函数体、名称、布局、Prompt、文案或常量：

| 分类 | 抽象结论 | 本项目处理 |
| --- | --- | --- |
| Observed | 模型看到的文本可以使用统一换行表示，编辑提交仍应保存原文件外观 | 采用独立 `WorkspaceTextSnapshot`，规范匹配与原始写回分离 |
| Observed | 编辑依赖先前读取状态，提交前必须再次确认文件没有变化 | 采用 Session-scoped 覆盖区间证据，并保留既有 raw-byte/path 冲突重检 |
| Observed | 大文件读取按范围、输出和 continuation 分页，不把整文件塞入上下文 | 采用增量 decoder 与确定性行范围结果；总量未知时不伪造 totals |
| Observed | 重复读取可返回轻量未变化结果，旧输出可在 Context 层独立治理 | 只在同 Session/同范围/同身份/同摘要时省略正文；不改变 Tool 协议配对 |
| Unknown | 参考产品的具体阈值、私有缓存结构、错误文本与精确跨平台实现 | 全部不采纳；使用项目已有 Tool 预算和独立测试 |

## 关键边界

- 整文件编辑快照最多读取配置 ceiling 加一个判定字节，不能信任先 `size` 后无界读取；
- 范围扫描不能越过声明 ceiling 一个缓冲区；文件恰好结束在 ceiling 时仍可形成完整结果；
- UTF-8 多字节序列和 `CRLF` 可以跨缓冲区，非法序列、NUL、取消均结构化失败；
- 单个超长逻辑行只保留固定前缀并显式计数，不允许无界 `StringBuilder`；
- 渲染层按最终 Tool 输出预算重新绑定 continuation，Pipeline 不应再截掉未计入 next offset 的正文；
- 本纠正不实现语义 Patch、AST/LSP、模糊匹配、多文件事务、OS Sandbox 或 S12 并行写入。

## 可证伪验证

离线测试至少覆盖 LF/CRLF/BOM round-trip、混合换行 Fail Closed、精确缩进、先读覆盖区域、
Session 隔离、并发修改、整文件读取 ceiling、范围扫描 ceiling、跨窗口 UTF-8/CRLF、超长行、
大于整文件 ceiling 的分页文件、重复范围未变化结果、continuation 和取消。完整 Reactor、TUI、
launcher、进度看板和 `git diff --check` 仍作为最终回归。

