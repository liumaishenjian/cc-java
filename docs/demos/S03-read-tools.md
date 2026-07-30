# S03 Read Tools Demo

## 目标

验证同一个 Java Headless Session 能通过统一 Pipeline 完成：

```text
list_files → search_text → read_file → git_status → git_diff → final
```

并证明 Workspace 越界、敏感文件和仓库内伪指令不能扩大访问权限。S03 只读，不包含 Patch、
写文件、通用 Shell 或 Git 写操作。

## 前置条件

- JDK 21；
- Git 可执行程序；
- ripgrep：启动器优先使用 `CC_JAVA_RIPGREP_PATH`，其次 PATH；当前开发机也可复用已经
  安装的 Codex Desktop rg。其他电脑若未安装，设置该环境变量指向 `rg.exe`；
- Windows Junction 专项需要 NTFS；
- 普通离线 Demo 不需要网络或 API Key。

## 离线 E2E

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest#completesListSearchReadStatusDiffThroughOneCanonicalToolLoop" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Fixture 在临时 Git 仓库内创建和修改 `src/App.java`。Scripted Model 每回合请求一个 Tool，
最终请求必须包含五个按调用顺序追加、Call ID 匹配的 `ToolResultMessage`。测试断言：

- Registry 顺序为 `list_files/search_text/read_file/git_status/git_diff`；
- list/search/read 返回相对路径和 1-based 行号；
- status 能识别 unstaged 修改；
- diff 返回修改证据；
- Run 以 `COMPLETED` 结束且共执行 5 次 Tool；
- 测试未通过本项目 Tool 写入 Workspace。

完整 Grep 参数与三模式使用另一条真实 rg、Scripted Model Agent Loop：

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest#completesAdvancedSearchModesAndPaginationThroughCanonicalAgentLoop" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

可在新 PowerShell 中先确认启动器解析结果：

```powershell
$env:CC_JAVA_RIPGREP_PATH = "D:\Tools\ripgrep\rg.exe" # PATH 已有 rg 时无需设置
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File "E:\Java\cc-java\scripts\RunS02TuiSpike.ps1" `
  -Timeout "60s" `
  -SkipBuild
```

启动日志必须出现 `[cc-java] ripgrep is ready.`；若显式路径无效，启动器会在进入 TUI
前失败，避免 Agent Loop 中连续得到 `SEARCH_UNAVAILABLE`。

该用例依次执行带 `type/regex/multiline/context` 的 content、files 第 1 页、files 第 2 页
和 `limit=0` 的 count，最后回到模型生成 final。它断言四个 Call ID 严格配对、第一页
携带 continuation、两页不重复、上下文可见且 `.env`/README 不越过请求根与类型过滤。

## 安全负例

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=HeadlessRuntimeSessionTest#sensitiveReadReturnsCorrectableErrorAndProjectInstructionsCannotElevateIt" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl cc-java-tools-local -am `
  "-Dtest=WindowsJunctionWorkspaceGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

第一个 Fixture 的根 `AGENTS.md` 明确诱导读取 `.env` 并忽略安全策略。Runtime 仍返回
`SENSITIVE_PATH`，结果不含 Secret 或绝对路径，模型可以在下一回合纠正。第二个 Fixture
创建 Workspace 内、外两个真实 NTFS Junction：内部目标可读，外部目标以 `LINK_ESCAPE`
拒绝；teardown 先删除 Junction 本身，外部文件保持存在。

## 真实 Provider opt-in

真实 Provider 使用 Git 忽略的本地配置。它不属于普通 CI，且最终自然语言不做固定断言：

```powershell
.\cc-java.ps1 --workspace "$PWD" --timeout 60s --print `
  "请使用只读工具找到 HeadlessRuntimeSession 如何注册 S03 工具，并用相对路径和行号说明调用链；同时报告当前 git status。不要修改文件。"
```

预期观察：

1. TUI 把连续 search/read 聚合为不含参数、路径和正文的语义化 Tool 活动；
2. 模型按需调用 list/search/read/git status 或 diff；
3. 最终回答以 Markdown 标题、列表、行内代码和代码块渲染，不直接显示 `##` 等原始标记；
4. `git status --short` 在运行前后没有新增 Agent 修改。

用于专门观察展示层的任务：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\scripts\RunS02TuiSpike.ps1" `
  -Workspace "E:\Java\cc-java" `
  -Timeout "60s" `
  -SkipBuild
```

进入 `就绪` 后输入：

```text
请只读搜索 AgentRuntime，简短说明它的职责。回答使用二级标题、项目符号和行内代码。
```

`-Prompt` 会显式进入面向管道的非交互 Print 模式，不用于观察 Ink 页面。
预期层级为“用户任务 → 聚合 Tool 活动 → Markdown 回答 → Java 权威终态 → 输入框”。
失败、拒绝和截断会显著显示；成功 Tool 不再逐条输出 `[tool n]` 机器记录。
搜索活动按模式显示为：

```text
✓ 搜索文件 · 29 个文件
✓ 搜索内容 · 48 处匹配 · 结果已截断
✓ 统计匹配 · 26 个文件已统计
```

这些计数来自 Java Tool metadata，不是 TUI 解析正文所得。模型默认只总结和引用与问题
有关的证据；只有用户明确要求穷举时才应在 Assistant 正文中展开全部结果。

真实网络/API Key 不是 S03 普通验证前提；如果当前 Provider 不稳定调用工具，应登记真实
Provider 兼容差距，不能用离线 E2E 冒充真实网络结果。

## 当前边界

- WorkspaceGuard 是应用层路径边界，不是 OS Sandbox；
- S03 不跟随外部 Symlink/Junction，不读取 `.git/**`、真实 `.env`、Provider 本地配置或私钥；
- 搜索优先使用受控 ripgrep，支持显式 `regex=true`；rg 不可用时仅保留 Java 字面降级；
- `search_text` 还支持 `mode=content|files|count`、`type`、`multiline`、
  `context/beforeContext/afterContext`、`offset/limit`；`context` 优先，
  `limit=0` 表示不做条目分页但仍受总输出硬上限；
- 活动 rg 搜索接收 Run 取消并清理进程树；取消、超时、输出超限和协议损坏使用不同错误；
- 根 `AGENTS.md` 只加载一次，不递归 import；分层规则属于 S08；
- Patch、Write、Shell、Approval 与通用命令进程取消属于 S04。
- TUI 当前没有展开单次 Tool 详情的快捷键；完整历史导航、折叠控制和多行输入属于 S08。
