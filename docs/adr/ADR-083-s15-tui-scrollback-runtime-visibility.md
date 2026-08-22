# ADR-083：S15 TUI 历史滚动与 Runtime 可见性纠正

- Status: Accepted
- Date: 2026-08-22
- Stage: S15 Independent Innovation（Batch 8）
- Feature IDs: `CLI-01/03/04/09`、`MODEL-06`、`OBS-01/03/05`、`CTX-06/13`、`TOOL-10/11`（等级不变）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Additional Public Reference: `CODEX-0.147`
- Capability Change: 无；S15 Exit 保持 OPEN

## 1. 问题与边界

旧 Ink 根节点按当前终端行数固定高度，并对完整历史使用 `overflow=hidden`。这不是可滚动 viewport：
内容超过窗口后，较早 Run 被永久裁出 React 投影，终端鼠标滚轮也无法找回。Composer 下方同时显示
内部行列诊断“光标 row:column”，它不参与编辑且占用一行。模型请求期间只有笼统等待文案，Tool 行
也只有名称/计数，用户无法区分模型回合、具体受控动作和已经报告的 Token Usage。

本 ADR 不开放或伪造隐藏思维链。只有 Provider 未来提供可公开、可校验的 reasoning summary 契约时，
才能作为独立协议能力设计；当前只展示确定性的 Runtime lifecycle 阶段。

## 2. 受控参考研究

按 ADR-022 窄读授权快照的终端渲染、滚动、Tool loader、模型状态和 Token 展示职责，并对公开
`CODEX-0.147` 的 reasoning/token event 分层做机制对照。未复制或翻译函数体、Prompt、文案、私有名称、
布局、常量、Fixture 或字节。

| 分类 | 抽象结论 |
| --- | --- |
| Observed | 参考终端将普通主屏输出与全屏虚拟列表区分；全屏路径自行维护 viewport、滚动位置与鼠标事件，而不是仅裁掉历史。 |
| Observed | Tool 运行具有独立的进行中/完成展示，用户可看到动作对象，但权限与执行权仍在 Runtime。 |
| Observed | 模型状态、Token Usage、上下文占用和可公开 reasoning 类型是不同信号，不能混成一个“思考文本”。 |
| Observed | `CODEX-0.147` 区分累计 Usage、上下文窗口状态、reasoning summary 与 raw reasoning visibility。 |
| Inferred | codej 当前使用终端主屏，应保留完整 Ink 输出交给终端原生 scrollback；未来若切换 alternate screen，必须实现真正的虚拟列表和鼠标协议。 |
| Unknown | 授权快照准确发行版本、内部压缩阈值、全部终端兼容策略，以及各 Provider 是否稳定返回可公开 reasoning summary。 |

## 3. 决策

1. 当前主屏 TUI 不再给根历史容器固定终端高度，也不使用 `overflow=hidden` 裁剪 transcript。已经终态且
   不再等待审批/问题/Plan 验证更新的 Run 进入 Ink `Static`，永久写到动态区域上方的终端 scrollback；当前
   Run 和 picker 留在 live region。仅移除固定高度不够，因为 Ink 7 在 Windows 动态区域满屏时会整屏重绘。
   Slash/mention 候选仍按可用行数局部窗口化。alternate-screen 虚拟滚动是未来独立能力。
2. 删除 Composer 的用户可见行列诊断；实际 grapheme 光标、视觉多行 viewport 和编辑 reducer 保持不变。
3. Java 在 `BeforeTool` 后只为固定内置 Tool/字段生成最多 320 code point 的瞬时活动摘要。协议拒绝未知
   Tool、参数对象、文件正文、patch old/new body、绝对路径和穿越目标；活动摘要不写 Canonical Session，
   不参与 Permission、Approval 或执行决策。
4. stdio v0 增加 `model.turn.started/completed` 投影。started 只有回合号；completed 只有 finish reason、
   可选 Provider Usage 和可选 Context Usage。TUI 显示“正在分析/正在响应/准备工具”这种生命周期状态，
   不显示隐藏思维链。
5. Provider 返回的 Usage 才标记“实测”并按 Run 累加；缺失回合明确形成“部分实测”。累计行使用紧凑方向符号
   `↑` 表示输入、`↓` 表示输出，并保留累计总量；Context Pipeline 的 `estimateKind` 原样显示为“估算/实测”，
   当前 Context used/max 独立展示，不得与累计 Usage 混算，也不得将字符估算伪装成 Provider 计费 Token。
6. 新事件和活动字段使用 exact schema、数值上限、控制字符检查与 run/request/session 关联。旧 Java child
   不发布这些可选事件时，TUI 继续显示兼容的等待状态。
7. `tool.output` 在 TUI 内保持结构化 `stdout/stderr`、行终止、相邻重复次数和 64 Ki code point Surface
   预算。只合并相邻、同 stream、完整且文本完全相等的行；不同错误、不同 stream、非相邻行和尾部残片
   不合并。CRLF 只作为通用行终止归一化，不识别 Git 或任何英文诊断文案。
8. 默认 transcript 只显示 Tool 活动和有界详情摘要。运行中使用 `Ctrl+T` 选择有输出的 Tool、`Ctrl+O`
   展开或折叠；所有 Approval、Plan、Question、Undo、Checkpoint picker 先取得键盘所有权。Run 终态时
   inline 详情快照随 `RunView` 一起进入 Ink `Static`，后续不修改该历史节点。
9. 为避免 Static 历史上的失效提示，终态摘要不显示运行中快捷键。Ready live region 提供独立“最近历史
   Tool 详情”viewer：`Ctrl+O` 打开/关闭，`Ctrl+T` 在该 Run 的 Tool 间切换。viewer 只读取 reducer
   中的结构化快照，不重绘或替换已进入 native scrollback 的 Static 节点；关闭 viewer 不承诺擦除终端
   已产生的 scrollback 字节。
10. 连续同类活动仍按名称、模式和活动摘要聚合；若异构活动分组超过 8 行，只保留最近 7 行，并用
    1 行汇总较早分组/调用数以及失败、拒绝和截断次数。长 command/query/path 先归一为最多 120 code
    point 的单行，再由 Ink 做终端宽度截断。`run_command` 成功退出码由成功事实投影为 0；非零、timeout、
    cancel 只从类型化错误 details 投影实际 exit code，不解析正文、stdout/stderr 或异常 prose。

## 4. 可证伪验证

- Ink 小窗口渲染 12 个长 Run 时首尾历史都在输出；新增已完成 Run 进入 `Static` 后，后续 live rerender 仍保留
  旧输出。Composer 仍可见，且不存在行列诊断。
- Java stdio lifecycle 测试证明模型 started/completed、Usage 和 finish reason 来自真实 Core 事件；终态 Telemetry
  仍不包含 Prompt、Completion 或 Provider 原文。
- Tool 测试证明相对目标可见，而正文、未知 Tool、绝对路径和穿越目标不可见。
- TypeScript protocol/reducer/render 测试证明 Usage 累加、缺失覆盖率、Context estimate label、活动摘要聚合和
  lifecycle UI；额外 reasoning 字段 fail closed。
- 纯函数和真实 Ink 测试证明 chunk 跨事件组行、通道保序、通用相邻重复压缩、不同错误不合并、64 Ki
  code point 截断、失败/exit 保留、picker 键位优先、终态 Static 摘要无失效快捷键，以及 live 历史 viewer
  可在完成后打开、切换 Tool 和关闭而不改变封存 Run 快照。
- 30 个不同 activity 的渲染最多 8 行；较早 23 组的失败、拒绝和截断仍由汇总行保留。真实 NDJSON
  fixture 证明 stdout/stderr 与 terminal exit code 经 `StdioClient` 严格解码。
- 完整 TUI check、相关 Maven 测试、Dashboard generate/check/self-test 和 diff check 必须通过。

## 5. 后续纠正：Plan accepted handoff 与真实 E2E 隔离

真实 Java Plan E2E 的冷首次失败并非已证明的 executor 丢任务。旧诊断将 `run.started`、
`tool.started` 与失败事件折叠为 `other`，同时 Fixture 直接把巨大且 dirty 的项目仓库作为 Plan
Workspace；批准后 `runAcceptedPlan` 在 `run.started` 前重新计算完整 Workspace digest，进入 Run 后又由
真实 `git_status` 扫描同一仓库。Maven 冷编译后的文件缓存、持续变化的 `target/` 与 Windows 杀软会放大
两次扫描的延迟和漂移，因此该 Fixture 污染了被测生命周期。

纠正后，每个 Plan Java child 在系统临时父目录下创建并严格清理专用 `plan-runtime-*` root，内部使用最小
真实 Git Workspace 和隔离 Provider 目录；Workspace digest 与 `git_status` 仍经过生产 Tool Pipeline，
但不再扫描调用方仓库。Fixture 继续使用 ADR-081 的稳定模型 schema：`revise_plan_artifact` 只提交
Markdown，`request_plan_review` 使用空对象，revision/content digest CAS 由 trusted Runtime 内部维护。

同时，`plan.review.resolve` 在 executor 接受 task 后使用一次性 start gate：worker 必须等待
`plan.execution.accepted` 成功进入 stdio 事件出口，随后才可调用 `runAcceptedPlan` 并发布
`run.started`。enqueue 失败不发布 accepted；accepted 投影失败或连接关闭会完成 abort、释放尚未开始的
APPROVED acceptance，worker 无 timeout、sleep 或 retry 地退出等待。TUI 仍保留 ADR-082 的 early-event
防御性 correlation，以兼容旧 child 和其他事件乱序，但当前 Java Plan 交接已确定满足
`accepted → run.started → tool lifecycle → terminal`。

可证伪证据包括：Java latch 测试在 accepted emitter 被阻塞时证明 execution Model 尚未进入，释放后严格
accepted-first；传输失败测试证明 worker 不启动且 close 不死锁；隔离 Fixture 测试在非 Git 父目录中完成
真实 `git_status` 并清理 root；Maven 冷编译后的真实 Java Plan 整文件及连续三轮均为 3/3。未延长
Vitest、Run 或 Tool timeout，也未加入重试。

本切片不提升 Capability Level。真实鼠标事件的 alternate-screen 虚拟滚动、公开 reasoning summary Provider
契约、跨平台真实 TTY 自动化和稳定协议 v1 对等投影仍是后续差距。
