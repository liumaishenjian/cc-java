# ADR-033：S03 采用受控 ripgrep 搜索后端

- Status: Accepted
- Date: 2026-07-30
- Stage: S03 Read Tools 退出后机制校正
- Capability IDs: `TOOL-05`、`TOOL-12`、`TOOL-13`、`SEC-03`、`SEC-10`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed`；本项目契约与实现为 `Documented`

## 背景

S03 首版 `search_text` 只依据公开“可搜索仓库”行为，以 Java NIO 实现有界字面扫描。
它证明了 WorkspaceGuard、敏感路径和结果预算，但没有完成项目要求的“先理解成熟机制，
再独立重实现”闭环。维护者要求以已授权源码的成熟搜索机制重新校正，而不是逐项人工提醒。

## 受控参考机制

对授权快照的只读研究只提炼以下机制，不复制函数体、Prompt、私有命名、布局或常量：

1. 仓库内容检索是独立 Tool，主要执行引擎为 ripgrep，不是向量 RAG；
2. 应用把结构化 Tool 参数转换成固定进程参数，不让模型构造 Shell 字符串；
3. 搜索支持正则、路径/Glob/类型、大小写、上下文、输出模式、limit/offset 和多行；
4. 进程具备超时、取消、输出排空、资源不足降级和明确失败语义；
5. VCS/ignore/敏感目录在读取前排除，结果相对路径化并在进入模型上下文前裁剪；
6. 精确搜索负责提供可核验文件/行号，语义检索不是其替代品。

快照 Revision 和公开再使用权仍为 Unknown；上述机制不能作为代码或 Golden Output 来源。

## 决策

`search_text` 保持项目独立 Tool 名称与 Pipeline 边界，引入项目自有 `TextSearchBackend`：

```text
search_text
  → WorkspaceGuard
  → RipgrepTextSearchBackend（生产首选）
  → JavaLiteralTextSearchBackend（rg 不可用时的明确降级）
  → ToolResultMetadata / Pipeline ceiling
```

- ripgrep 只通过 `ProcessBuilder(List<String>)` 执行，固定 Workspace 工作目录，不经过 Shell；
- 首版从 PATH 解析 `rg`；打包内置二进制属于 S14 Distribution，不静默下载；
- 默认尊重 ignore 文件、搜索 hidden 文件，但在读取前以固定规则排除 VCS 和敏感路径；
- 不启用跟随链接；根路径必须先通过 WorkspaceGuard；
- stdout/stderr 并发有界读取，10 秒超时后销毁进程；原始 stderr、绝对路径和命令行不反馈模型；
- 退出码 0 表示匹配、1 表示无匹配，其他退出码映射为结构化安全错误；
- rg 不可用时只允许现有字面能力降级；请求正则等增强能力时明确返回能力不可用；
- S03 机制校正必须完成 content/files/count、context、type、offset/limit、多行、
  行号控制、类型化结果、取消传播和一次性资源不足重试，完成前 G6 保持 Open；
- Tool 后端收敛为不可变请求和类型化结果，不再让 Tool 解析
  `path:line:text`；content 使用 ripgrep JSON Lines 机器协议，files/count 使用不与路径
  冲突的机器格式或从类型化事件安全聚合；
- 授权快照本身主要使用文本行和冒号位置解析；机器协议是本项目为解决 Windows 盘符、
  含冒号文件名、上下文和多行歧义所作的独立健壮性改进，不表述为参考源码原有实现；
- rg JSON 输出先受 2 MiB、1 MiB/行和 10000 事件硬上限约束，再按类型化条目应用
  `offset/limit`；`limit=0` 只取消条目分页，不取消上述输出硬上限；
- 用户取消与墙钟超时使用不同错误码；分页仅产生 continuation，不伪装成取消或超时；
- Run 的 `CancellationToken` 经 `ToolInvocation` 传播到只读搜索子进程。ADR-032 原延期到
  S04 的是通用 Tool/Command 进程控制；本 ADR 仅把 `search_text` 的无副作用子进程取消
  提前到 S03，以完成已授权 Grep 恢复机制；
- 临时资源不足只对当前调用执行一次单线程重试；非法正则、非法 type、权限错误和其他
  确定性错误不得重试；
- S03 定义可替换的 rg executable resolver。PowerShell 启动器按
  `CC_JAVA_RIPGREP_PATH → PATH → 本机 Codex Desktop 既有 rg` 解析可信绝对路径并只向
  子进程补充 PATH；它不复制或下载二进制。各平台内置/嵌入 rg、签名、修复、发行和
  跨机器兼容仍属于 S14；
- RAG 若进入项目，必须是独立 `semantic_search` Capability 和 Eval，不混入精确搜索。

## 可证伪验证

1. Fake Backend 证明全部参数组合、三种输出模式、分页和错误映射；
2. 真实 rg Fixture 证明正则、Glob/type、context、多行、ignore、相对路径和三种输出；
3. JSON Lines 协议覆盖 Windows 盘符、含冒号/Unicode 路径、多行和损坏消息；
4. 高置信敏感文件与 `.git` 在 rg 启动参数中预排除；全部返回路径仍由
   WorkspaceGuard 复验，Symlink/Junction 逃逸与其他敏感文件不会进入 Tool Result，
   安全模板仍可搜索；
5. 假 rg 进程证明超时、Run 取消、一次性 EAGAIN 单线程重试、
   stdout/stderr 上限、进程树清理和无 Shell 插值；
6. rg 不可用时仅语义等价的字面 content 子集降级，其他能力确定性失败；
7. Scripted Model 证明三种模式、翻页和 Call ID 的完整 Agent Loop；Core Token 传播测试与
   假进程取消测试共同证明活动搜索取消链路。
8. Windows PowerShell 在移除 rg PATH 后仍能解析本机既有 Codex Desktop rg；显式无效
   `CC_JAVA_RIPGREP_PATH` 必须在启动前失败，不得静默执行其他文件。

本 ADR 不提升 Capability Level；只有实现、负例、Demo 和看板同时通过后，才能声明机制校正完成。
