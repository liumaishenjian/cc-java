# ADR-032：S03 Read Tools 安全与结果契约

- Status: Accepted
- Date: 2026-07-30
- Stage: S03 Read Tools
- Capability IDs: `BOOT-02`、`BOOT-04`、`CLI-04`、`TOOL-04`、`TOOL-05`、`TOOL-06`、`TOOL-07`、`TOOL-12`、`TOOL-13`、`SEC-01`、`SEC-02`、`SEC-03`、`SEC-10`、`CTX-02`、`CTX-05`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: 公开能力为 `Documented`；安全与协议为本项目独立设计，完成实现和验证前为 `Inferred`

## 背景

S01 已建立统一的 `AgentTool → ToolRegistry → ToolExecutionPipeline → ToolResult`，S02 已把
真实 Provider、Java Headless、stdio v0 与 React/Ink TUI 接到同一个 Runtime。当前
`cc-java-tools-local` 仍没有生产工具，Headless 注册空 Registry，Pipeline 也没有强制
`ToolDefinition.maxOutputCharacters`。因此模型能够对话，却不能在真实 Workspace 中形成
“列举 → 搜索 → 分段读取 → 检查 Git 证据 → 解释代码”的只读调查闭环。

S03 只建立受控读取能力。它不实现写文件、Patch、通用 Shell、Git 写操作、完整 Permission
Mode、自动 Context 压缩或 OS Sandbox。

## 来源与独立重实现边界（G0）

本阶段使用：

1. `REF-02` Claude Code 官方工作原理，作为读取、搜索和 Tool 进度的候选公开能力；
2. `REF-04` 官方权限模式，作为“读取不等于无限制访问”的公开安全输入；
3. `REF-05` 官方项目指令文档，作为项目级指令能力输入；
4. JDK 21 `Path`、`Files`、Charset 与 `ProcessBuilder` 一手规范；
5. Git 官方命令语义；
6. 本项目 PRD、技术设计、安全规则和独立 Fixture。

本阶段不需要、也不读取 `AUTH-SRC-2026-07-29-A`。因此不采纳参考源码中的函数体、命名、
错误文案、Prompt、文件布局、常量或内部格式，参考源码也不作为 Fixture、Golden Output 或
测试 Oracle。实现只有通过本项目测试和 Demo 后才可标记为 `Verified in cc-java`。

## 范围与退出目标（G1）

| Capability | Current | S03 Exit Target | 可证伪结果 |
| --- | --- | --- | --- |
| `BOOT-02` | L0 | L2 | Session 固定真实 Workspace，并产生分支/脏状态摘要 |
| `BOOT-04` | L0 | L1 | 只加载根 `AGENTS.md`；分层加载延期到 S08 |
| `CLI-04` | L1 | L2 | stdio/TUI 展示有序、脱敏的 Tool 状态 |
| `TOOL-04` | L0 | L2 | `list_files` 在真实 Workspace 有界枚举 |
| `TOOL-05` | L0 | L2 | `search_text` 返回有界路径、行号和片段 |
| `TOOL-06` | L0 | L2 | `read_file` 分段读取受控 UTF-8 文本 |
| `TOOL-07` | L0 | L2 | `git_status` / `git_diff` 提供只读证据 |
| `TOOL-12` | L0 | L2 | Tool 语义裁剪加 Pipeline 最终硬上限 |
| `TOOL-13` | L1 | L2 | 路径、编码、Git 等错误可由模型纠正 |
| `SEC-01` | L0 | L1 | lexical 与 realpath containment 有独立负例 |
| `SEC-02` | L0 | L1 | Symlink/Junction 内外目标由统一判据控制 |
| `SEC-03` | L0 | L1 | 固定敏感路径策略和正负例 |
| `SEC-10` | L0 | L1 | Tool/Event/Error 不泄漏 Secret、绝对路径和原始异常 |
| `CTX-02` | L0 | L2 | 根 `AGENTS.md` 只影响模型上下文，不能提权 |
| `CTX-05` | L0 | L2 | 结果限制、截断原因和 continuation 类型化 |

最小可证伪闭环为：Fake Model 在临时仓库依次调用 `list_files → search_text → read_file →
git_status → git_diff → final`；越界、链接逃逸或敏感读取返回结构化错误，模型改用允许路径后
继续完成；运行前后 Workspace 内容不变。

## Tool 契约

所有 Tool 的未知字段、错误 JSON 类型和越界数值均在执行前拒绝。协议路径使用 `/`，排序
以 Unicode 字符串稳定升序为准；调用参数只接受 Workspace-relative 路径。

### `read_file`

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["path"],
  "properties": {
    "path": {"type": "string", "minLength": 1},
    "startLine": {"type": "integer", "minimum": 1, "default": 1},
    "maxLines": {"type": "integer", "minimum": 1, "maximum": 500, "default": 200}
  }
}
```

只读取大小受限的普通 UTF-8 文件；允许 UTF-8 BOM，拒绝非法编码和二进制内容。正文带稳定的
1-based 行号。存在后续内容时，metadata 的 continuation 给出下一 `startLine`。

### `list_files`

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "path": {"type": "string", "default": "."},
    "glob": {"type": "string"},
    "maxDepth": {"type": "integer", "minimum": 0, "maximum": 20, "default": 8},
    "maxResults": {"type": "integer", "minimum": 1, "maximum": 1000, "default": 200}
  }
}
```

只返回 Guard 允许的普通文件和目录，不跟随目录链接，不进入 `.git` 或敏感目录。结果稳定排序；
达到深度、条目或字符预算时显式标记截断。Glob 只匹配协议相对路径，不改变遍历根或安全策略。

### `search_text`

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["query"],
  "properties": {
    "query": {"type": "string", "minLength": 1, "maxLength": 1024},
    "path": {"type": "string", "default": "."},
    "glob": {"type": "string"},
    "caseSensitive": {"type": "boolean", "default": true},
    "maxResults": {"type": "integer", "minimum": 1, "maximum": 500, "default": 100}
  }
}
```

S03 只支持字面搜索，不开放任意正则。搜索限制访问文件数、单文件字节和累计扫描字节，只处理
Guard 允许的普通 UTF-8 文本；结果包含相对路径、1-based 行号和有界单行片段。

### `git_status`

```json
{"type":"object","additionalProperties":false,"properties":{}}
```

返回当前分支以及 staged、unstaged、untracked 的稳定摘要，不返回 `.git` 内部内容。

### `git_diff`

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "mode": {"type": "string", "enum": ["unstaged", "staged"], "default": "unstaged"},
    "path": {"type": "string"}
  }
}
```

可选路径先经 Guard 校验，再以 `--` 与 Git 选项分隔。二进制 Diff 只返回摘要，不把原始字节
送入模型。

## WorkspaceGuard

Workspace 在启动时解析为真实、存在的目录。每次路径访问按以下顺序执行：

1. 拒绝空值（明确允许 `.` 的参数除外）、绝对路径、UNC、Windows drive-relative 路径和
   lexical `..` 越界；
2. 以启动时固定的真实 Workspace 解析并 normalize；
3. 对现存目标执行 `toRealPath()`，要求真实目标仍被真实 Workspace 包含；
4. 同时对逻辑相对路径和真实相对路径执行敏感策略；
5. 根据 Tool 契约验证目标类型、文件大小、编码和读取预算。

Symlink 与 Windows Junction 使用同一个统一判据：**真实目标是否仍位于真实 Workspace**。
指向 Workspace 内的目标可以按 Tool 类型使用；指向外部、多跳逃逸、断链和环均拒绝。S03
不创建文件，因此“最近存在父目录”校验保留给 S04。

`WorkspaceGuard` 是应用层路径边界，不是 OS Sandbox。它不能约束本阶段不存在的任意进程，
也不代表用户账户对 Workspace 外没有操作系统权限。

## 敏感路径策略

默认拒绝（大小写按当前平台文件系统语义处理，并同时比较逻辑与真实目标）：

- `.git` 及其全部后代；
- `config/provider.local.properties`；
- `.env`、`.env.local`、`.env.*.local` 等真实环境配置；
- 常见私钥、证书私钥和凭证文件名或扩展名；
- 名称明确表示 token、credential、secret 的本地文件。

明确允许 `.env.example`、`.env.sample`、`config/provider.local.properties.example` 等无真实
凭证的模板。模板内容仍是不可信仓库数据，且仍受大小和输出限制。S08 才引入可配置策略；
S03 的固定策略宁可拒绝，也不根据 Prompt 放宽。

## 结果信封与硬上限

Tool 返回正文和语义裁剪 metadata；Pipeline 负责绑定 Call ID、Tool 名称、状态并执行最终
规范化。metadata 至少表达：

- 是否截断及稳定原因；
- 最终返回字符数；
- 已知时的原始字符数；
- 返回条目数；
- 过滤/跳过/脱敏数；
- 用 `JsonObject` 表达的 continuation。

Tool 先按行、条目、文件数和扫描字节做语义裁剪；Pipeline 再按
`min(ToolDefinition.maxOutputCharacters, ABSOLUTE_TOOL_OUTPUT_CHARACTERS)` 防御性裁剪。
截断提示计入上限，不拆分 Unicode code point。`AfterTool`、Session History 和下一 Model
Turn 只能看到最终结果，不能保留裁剪前旁路副本。

S03 固定实现上限，不引入 S08 Settings。实现常量由测试锁定，但不得超过以下 ADR ceiling：

| 预算 | ADR ceiling |
| --- | ---: |
| 普通文本文件 | 2 MiB |
| `AGENTS.md` | 64 KiB |
| 单次 read 行数 | 500 |
| list 深度 | 20 |
| list 条目 | 1,000 |
| search 访问文件 | 5,000 |
| search 累计扫描 | 32 MiB |
| search 匹配 | 500 |
| 单 Tool 最终正文 | 64,000 Unicode 字符 |
| Git stdout / stderr | 2 MiB / 16 KiB |
| Git 墙钟时间 | 10 秒 |

## Git 只读执行边界

Git 只能由私有 Adapter 使用 `ProcessBuilder(List<String>)` 直接启动，固定 `-C <workspace>`，
禁止 `cmd /c`、PowerShell、`sh -c` 和任何模型提供的任意选项。进程环境禁用 pager、color、
external diff、textconv 和可写 optional locks；固定工作目录、locale、超时以及 stdout/stderr
上限。允许的逻辑操作只有 status、staged diff 和 unstaged diff。

非 Git Workspace、Git 不可用、退出非零、超时和输出超限都转换成稳定结构化错误。错误不得
包含绝对路径、原始 Git stderr、环境变量、堆栈或 Secret。

## 根 `AGENTS.md`

Session 打开时通过同一个 Guard 最多加载一次 Workspace 根 `AGENTS.md`。不存在时正常启动；
外部链接、超大文件或非法 UTF-8 按固定安全错误处理。内容以明确分隔的 Project Instructions
进入 System Context，不进入日志或 Tool Event。

S03 不递归 import，不读父目录、用户目录或子目录规则，不兼容其他指令文件。项目指令只能
指导模型，不能注册工具、扩大 Workspace、修改 Permission、读取敏感路径或提高任何上限。
分层和 path-scoped instructions 延期到 S08。

## Tool 进度 Surface

stdio v0 增加 `tool.started`、`tool.completed`、`tool.failed` 安全投影，只包含 ordinal、Tool
名称、状态、耗时、稳定错误码、返回字符数和截断/过滤统计。禁止传输完整参数、搜索词、文件
正文、绝对路径、Secret、原始异常或 stderr。

Print stdout 继续只包含 Assistant 最终文本；需要 Tool 进度时只向 stderr 输出安全摘要。
TUI Reducer 只投影 Java 事件，不自行判断 Tool 或 Run 终态。

## 结构化错误

S03 增加模型可纠正的稳定分类：绝对/非法路径、Workspace 越界、目标不存在、类型不符、
链接逃逸、敏感路径、文件过大、编码不支持、非 Git 仓库、Git 不可用、Git 只读命令失败、
超时和结果超限。错误消息只描述相对目标和纠正方向，不泄漏绝对路径、正文、Secret、堆栈或
原始 stderr。可纠正 Tool 错误返回模型，不直接终止 Run；Runtime 的回合和 Tool 预算仍阻止
无限恢复。

## 明确延期

- S04：写文件、Patch、通用命令、Approval UI、Tool/进程取消和脏工作区写保护；
- S05：完整 Effect/Mode/Rule/Approval 优先级与拒绝恢复；
- S07：Tool Result 淘汰、摘要和自动 Context 压缩；
- S08：分层 Instructions、Settings 和可配置敏感路径策略；
- S12：只读 Tool 并行执行；
- S13：OS Sandbox、进程/文件/网络隔离和完整攻击回归；
- S14：稳定机器协议、外部遥测、跨平台参考可比和兼容承诺。

## 被否决方案

- 不允许模型使用通用 Shell 运行 `find`、`grep` 或任意 Git 命令；
- 不以字符串前缀检查代替 realpath containment；
- 不跟随目录链接后再尝试过滤结果；
- 不把敏感路径规则写进 Prompt 充当访问控制；
- 不先全量读取文件或 Git 输出再裁剪；
- 不把 Tool 参数和正文投影到 stdio/TUI 进度事件；
- 不为当前 Stage 增加第三方 glob、search、Git 或 JSON 依赖；
- 不使用授权参考源码来决定实现表达。

## 验证要求

G3/G4 至少覆盖正常、空、缺失、类型、UTF-8/BOM/非法编码/二进制/大文件；POSIX/Windows
绝对路径、UNC、drive-relative、混合分隔符和 `..`；Workspace 内外 Symlink、断链、多跳、
环及 Windows Junction；敏感路径正负例；各层预算和 Unicode 截断；Git clean/staged/
unstaged/untracked/non-repo/external-diff；Call ID、多 Tool 顺序、错误后恢复、事件脱敏和含伪
系统指令的注入 Fixture。

普通 CI 无法创建 Symlink 时可以按平台/权限明确 skip，但 Windows NTFS Junction 证据不能
全部 skip。Teardown 必须只删除重解析点本身，不能跟随删除 Workspace 外目标。

G5 必须包含 Scripted Model 离线 E2E 和显式 opt-in 的真实 Provider 公开仓库解释 Demo；网络
和 API Key 不作为普通 CI 前提，也不对自然语言作固定断言。G0-G4 未通过前不提升 Capability；
G5/G6 和 Commit-scoped 复验未完成前，S03 Stage Exit 保持 Open。
