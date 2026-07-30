# ADR-035：S04 审批骨架与写入/命令启动 Gate

- Status: Accepted
- Date: 2026-07-30
- Stage: S04 Write + Command
- Capability IDs: `CLI-05`、`CLI-06`、`TOOL-08`、`TOOL-09`、`TOOL-10`、
  `TOOL-11`、`TOOL-14`、`PERM-01`、`PERM-02`、`PERM-03`、`PERM-07`、
  `SEC-01`、`SEC-04`、`SEC-05`、`EVAL-01`、`DIST-01`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`；本项目契约为 `Documented`

## 背景

S03 已使 Agent 能在受控 Workspace 中读取、搜索和解释代码，但生产装配仍拒绝所有
非读取 Tool。S04 的目标不是直接把文件写入和 Shell 暴露给模型，而是先建立一条可以
自动证伪的审批控制链，再依次接入 Patch 和 Command：

```text
模型提出 Tool Call
→ 参数与安全边界校验
→ 固定 Permission 决策
→ 必要时发布审批请求并等待用户
→ 用户对展示的单次操作 Allow Once / Deny
→ 执行或返回结构化拒绝
```

审批等待发生在 Runtime 的 Tool Pipeline 内，终端只显示请求并提交决定，不能绕过
Java 的最终决策。S04 仍不实现 S05 的持久规则、Session Allow、规则优先级和 Hard
Denial 配置。

## 受控参考研究结论

对授权快照中的 Permission 类型、Tool 使用判定、交互处理器、审批队列和终端审批组件
进行只读研究后，只提炼以下可独立表达的机制：

1. Permission 决策至少区分 `allow / ask / deny`；`ask` 不是最终决定，必须由审批
   Surface 或其他受信控制面收敛为允许或拒绝。
2. Tool 调用在等待审批期间保持挂起；审批请求携带稳定关联 ID，决定必须只作用于对应
   Tool Call，不能批准未展示的后续操作。
3. 取消、输入流关闭、Surface 崩溃和会话关闭必须释放等待者，并按拒绝语义 Fail
   Closed，不能留下永远等待的 Runtime 线程。
4. 交互式 Surface 可以提供多种批准范围，但批准范围与持久化属于独立策略；S04 只采用
   `Allow Once` 和 `Deny`。
5. 不同 Tool 可以拥有不同审批展示，但最终都回到统一 Permission/Approval 管线；
   终端组件不执行 Tool。
6. 非交互入口无法完成 `ask` 时必须拒绝，而不是默认允许。

上述结论不包含参考源码函数体、Prompt、错误文案、私有类型名、文件布局或实现常量。

## 本项目决策

### 1. S04 固定模式

首个切片使用不可配置的两种策略语义：

| 模式 | Read | Workspace Write | Process | Network / System |
| --- | --- | --- | --- | --- |
| DEFAULT | Allow | Ask | Ask | Deny |
| PLAN | Allow | Deny | Deny | Deny |

生产 CLI 当前固定使用 DEFAULT；PLAN 的用户入口和完整配置在 S05 前不对外承诺。
Print/Non-interactive 使用拒绝型 `ApprovalHandler`，因此任何 `Ask` 都安全收敛为
`Deny`。

### 2. 单次审批协议

内部 stdio v0 增加：

- Event `approval.requested`：只携带 `approvalId`、Tool 序号、Tool 名称和 Effect；
- Command `approval.resolve`：携带同一个 `approvalId` 和
  `allow_once | deny`；
- TUI 在 Run 仍为 running 时展示审批面板，明确按键允许一次或拒绝。

协议不传输完整 Prompt、任意 Tool 参数、文件正文、命令输出或绝对路径。Patch 和
Command 接入时只增加经过专用脱敏器形成的预览字段，不能直接序列化原始参数对象。

### 3. 等待与释放

Java CLI Adapter 保存至多一个待决审批，因为 S04 Tool Pipeline 仍顺序执行：

- 注册当前 Run 的 CancellationToken；
- 收到匹配的 `approval.resolve` 时完成等待；
- Run 取消、shutdown、stdin EOF、Handler close 或 Adapter 异常时完成为 Deny；
- 重复、过期或不匹配的 ID 返回结构化协议错误；
- 决定完成后移除待决项，后续重复决定不能影响新的 Tool Call。

### 4. 分步实现

1. 先用 Fake Write Tool 证明 `Ask → Event → Allow Once/Deny → Execute/Skip`；
2. 再实现 Apply Patch/Write 的路径、内容前置条件与原子替换；
3. 最后实现结构化 Command、流式输出、超时、取消和 Windows 进程树清理；
4. 用独立 Fixture 完成“修改 → 测试失败 → 再修改 → 成功”的编码闭环。

### 5. Patch/Write 切片契约

对授权快照中的文件 Edit、Write、审批 Diff 与文件状态检查进行第二轮只读研究后，
本项目只采纳以下可独立表达的机制：

- Edit 必须携带精确旧内容；找不到旧内容时拒绝，出现多个匹配且没有显式全量替换时
  拒绝，不能猜测模型想改哪一处；
- 从验证到落盘之间文件可能被用户、格式化器或其他进程改变，因此落盘前必须再次比较
  当前内容，冲突时不写入；
- 创建与覆盖是不同操作；审批 Surface 必须能区分目标路径、创建/修改和变更行数；
- 审批前展示的是有界、专用的变更摘要，不把任意 Tool 参数对象或完整文件内容直接放入
  stdio 事件；
- 修改成功后返回有界 Patch 摘要，供模型继续调用 `git_diff` 取得 Workspace 级证据。

本项目采用独立的两个 Tool 契约：

1. `apply_patch(path, oldText, newText, replaceAll=false)` 只修改已经存在的严格 UTF-8
   普通文件。`oldText` 必须非空并与 `newText` 不同；默认只能唯一匹配，`replaceAll`
   必须显式开启。该格式属于项目自有的“精确上下文替换 Patch”，不是统一 Diff 解析器。
2. `write_file(path, content)` 首版只创建不存在的新 UTF-8 文件，且直接父目录必须已经
   存在并通过 realpath containment；它不覆盖已有文件，也不递归创建目录。覆盖应使用
   带旧内容前置条件的 `apply_patch`。

两者在执行时都重新经过 `WorkspaceGuard`，限制输入和最终文件字节数，拒绝二进制 NUL、
敏感路径、绝对路径、Traversal、Symlink/Junction 逃逸。写入先在同一真实父目录创建
临时文件。替换已有文件时优先执行 `ATOMIC_MOVE + REPLACE_EXISTING`，平台不支持
`ATOMIC_MOVE` 时退化为同目录单次 Move；创建文件使用不带 `REPLACE_EXISTING` 的
同目录单次 Move，竞态目标存在时必须失败。两者均保持“失败不覆盖原文件”的契约。
`apply_patch` 在移动前再次核对真实路径与原始字节，`write_file` 在移动前再次确认
目标不存在。

该切片不会自动格式化、删除文件、创建父目录、清理脏工作区、Commit 或 Reset。精确旧
内容是保护用户已有修改的内容前置条件；S06 才增加 Checkpoint/Undo。

### 6. Command 切片契约

对授权快照中的 Shell Provider、PowerShell Tool、子进程环境过滤、输出预算和进程辅助
机制进行第三轮只读研究后，本项目只采纳以下可独立表达的机制：

- Shell 差异由独立 Adapter 隔离；模型只提供命令正文，不能选择可执行文件、启动参数、
  cwd 或环境；
- timeout 必须有默认值和硬上限，取消、timeout、正常退出和启动失败必须收敛到明确
  结果，并在所有路径释放管道与进程资源；
- stdout/stderr 必须并发消费、逐步发布且最终有界；Surface 事件不等于写入模型
  Context，最终 Tool Result 仍经过独立上限；
- 子进程环境需要显式过滤 Provider、云凭证和未知 Secret，而不是无条件继承父进程；
- 进程终止必须覆盖已启动的后代，并通过可观察的无孤儿进程测试证伪清理错误。

本项目独立定义 `run_command(command, timeoutSeconds=30)`：

1. Windows 优先使用固定安装路径的 PowerShell 7，缺失时回退系统 Windows PowerShell；
   其他平台固定 `/bin/sh`。模型不能传入 Shell 路径或额外启动参数；
2. Java 把已批准的完整 `command` 作为固定 Shell 的单个参数传入，不把用户、模型或
   文件文本再次拼接进命令；审批事件准确展示同一命令正文、Shell ID 和 cwd `.`；
3. 工作目录固定为启动 Workspace；stdin 启动后立即关闭，S04 不提供 TTY、后台任务、
   持久 Shell 或 cwd 继承；
4. timeout 默认 30 秒、最大 120 秒；Run 取消与 timeout 共用进程树终止路径。Windows
   先强制终止已经捕获的后代，避免等待外部清理程序期间继续产生副作用；再使用结构化
   `taskkill /PID <pid> /T /F` 清扫整棵树，并以 `ProcessHandle` 兜底。其他平台处理
   已观察后代与主进程。该能力是应用层清理，不是 Windows Job Object 或 OS Sandbox；
5. 环境采用固定 allowlist，只保留平台启动、PATH、临时目录和 Java/Maven/npm 本地工具
   所需位置；OpenAI/Provider Key 不进入子进程；
6. stdout/stderr 并发消费并发布最多 4096 字符的 `ToolOutput` 片段；TUI 单 Run 保留
   上限 64 KiB，模型结果合计保留 48 KiB并显式标记截断；
7. 非零退出码作为可恢复验证证据返回，timeout/cancelled 也在结果中明确标记。启动失败
   才属于 Adapter 执行失败。

该切片不自动识别命令语义，不声称 Shell 没有网络或 Workspace 外访问能力，也不允许
自动 Commit、Push、Publish 或 Deploy。上述高风险动作仍需用户逐次批准；S13 才提供
OS Sandbox，S12 才提供后台执行。

### 7. 公开 Fixture Coding Loop

S04 最后切片不引入新的生产 Tool，而是用独立公开 Fixture 证伪前三个切片无法组合：

1. Fixture 固定 PRD 的 `Calculator.divide` 任务、初始代码、`--self-test`、允许范围和
   `DO_NOT_EDIT.txt` 越权探针；
2. Scripted Model 只能提出 Tool Call，并必须在下一回合读取真实 Tool Result；
3. 确定性 Approval Handler 只允许展示过的 `src/Calculator.java` Patch 和固定测试
   命令，越权 Patch 返回匹配 Call ID 的 Denied Result；
4. 第一次实现故意遗漏零除数异常，自测产生非零退出；第二次 Patch 必须由该失败证据
   驱动，随后命令输出 `ACCEPTANCE_OK`；
5. 最终 `git_diff` 和文件断言证明只修改允许文件，源 Fixture 本身保持不变。

Fixture 是本项目独立测试材料，不来自授权源码快照。它把 `EVAL-01` 提升到单 Seed Task
的 L1，不代表已有真实模型任务集、成功率或成本 Eval。

## 被否决方案

- **先实现真实写文件，再补审批**：无法证明任何副作用都经过统一控制点。
- **由 React/Ink 直接执行 Patch 或 Command**：破坏 Java Runtime 权威和多 Surface
  共用引擎的不变量。
- **把 `ask` 在 Print 模式解释为允许**：无用户确认却扩大权限。
- **首版加入 Session Allow 和可配置规则**：提前进入 S05，扩大状态恢复和优先级范围。
- **把 Permission 描述成 OS Sandbox**：S04 只做应用层决策和进程控制；OS 隔离属于
  S13。

## 可证伪验证

1. Fake Write Tool 在 `allow_once` 前绝不执行，批准后只执行一次；
2. Deny 返回匹配 Tool Call ID 的拒绝结果，模型可以进入下一回合；
3. 错误、重复、过期审批 ID 不能唤醒其他请求；
4. Run Cancel、shutdown、EOF 和 Handler close 均释放审批等待；
5. Print 模式遇到 Ask 时拒绝；
6. TUI Reducer 和协议测试覆盖请求展示、Allow Once、Deny 和终态清理；
7. 文件事件只含固定安全字段；Command 审批只增加准确命令、Shell 和相对 cwd，
   Provider 配置不进入协议；
8. stdout/stderr 事件有界且保持序号；非零退出、输出截断、timeout、取消和 Windows
   后代清理均有确定性测试。

## 延后内容

- S05：Session Allow、声明性规则、规则来源/优先级、Hard Denial 和拒绝恢复；
- S06：审批决定持久化和未完成副作用恢复；
- S13：OS 文件、进程和网络 Sandbox；
- S14：多 Seed Task、真实模型重复运行、成功率、成本和跨平台 Eval。
