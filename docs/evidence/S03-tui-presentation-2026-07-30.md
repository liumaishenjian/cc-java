# S03 终端语义化展示证据

- Status: Workspace Verified
- Date: 2026-07-30
- Stage: S03 Read Tools 退出后体验维护
- Capability IDs: `CLI-03`、`CLI-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Decision: [ADR-034](../adr/ADR-034-s03-tui-presentation.md)

## 机制与独立边界

授权快照仅用于提炼 Assistant/Tool 分离、连续读取与搜索聚合、失败和截断显著显示、
Markdown 专用渲染和默认低噪声等机制。本项目没有复制参考函数体、Prompt、命名、
组件布局、错误文案、常量、Fixture 或 Golden Output。

## 当前实现

- `AssistantMarkdown` 使用 `marked 18.0.7` 解析 GFM Token，再映射为项目自有 Ink 组件；
- 标题、段落、强调、行内代码、代码块、列表、引用、链接、表格和分隔线具有终端层级；
- 未闭合流式片段和解析异常保持可显示，展示异常不改变 Java Run；
- `ToolActivityGroup` 聚合连续同类 Tool，显示进行中、成功、拒绝和失败；
- Tool 摘要仅使用名称、状态、返回字符数、返回条目数、过滤项、截断原因和安全错误码；
- Java stdio 只从 `search_text` 参数提取固定 `content/files/count` 枚举，不投影查询、
  路径或其他参数；TUI 分别显示“匹配/文件/已统计文件”，不同模式不混合累计；
- 成功终态降低噪声，失败终态继续显示 Java 权威 `stopReason`；
- 输入框、运行状态、取消提示和历史回答具有独立视觉区域。
- Headless 系统指令要求模型默认总结和引用相关证据，不复述完整 Tool Result；用户明确
  要求穷举时仍保留完整回答，TUI 不作有损截断。

本切片没有改变 Runtime、Tool Pipeline、Permission 或 stdio v0 的状态权威，也没有
增加 S04 写入/Command/Approval 或 S08 历史/补全/Slash Command。`CLI-03`、`CLI-04`
仍为 L2。

## 验证

```text
npm.cmd --prefix cc-java-tui run check
```

- TypeScript build 通过；
- TUI 7 个测试文件、31 项测试全部通过；
- Markdown 专项覆盖标题、列表、行内代码、代码块和未闭合流式片段；
- Tool 活动专项覆盖连续聚合、进行中、成功、截断、拒绝和失败；
- 真实交互 TTY 在 `就绪` 后提交只读 `AgentRuntime` 任务并完成 6 个模型回合、5 次
  Tool；Markdown 标题/列表/行内代码、Tool 失败后恢复、最终计数和优雅 shutdown
  均经同一 Java Runtime/stdio/Ink 链路展示；
- Maven `clean verify` 通过：Domain 1/1、Core 44/44、Provider 23 项中 21 项通过且
  2 个真实网络 Spike 跳过、Tools Local 37 项中 36 项通过且 1 个非 Windows Symlink
  用例跳过、CLI 34/34；
- 首次全量复验的 Java 断言已通过，但 Windows 清理临时 `.git` 目录发生一次
  `DirectoryNotEmptyException`；聚焦 E2E 随即 1/1 通过，第二次完整 `clean verify`
  全部通过，未把清理抖动当作成功；
- 看板生成、`--check`、`--self-test` 和最终代码摘要已复验。

紧凑摘要追加验证：

- `RuntimeStdioCommandHandlerTest` 与 `HeadlessRuntimeSessionTest` 共 12/12 通过；
- 当前完整 `mvnw test` 通过：Domain 1/1、Core 44/44、Provider 23 项中 21 项通过且
  2 个真实网络 Spike 跳过、Tools Local 37 项中 36 项通过且 1 个非 Windows Symlink
  用例跳过、CLI 35/35；
- stdio 事件证明 `returnedItems/mode/truncationReason` 可见，而查询、路径、参数和正文
  不可见；
- TUI 协议拒绝未知模式、负数条目数和非法截断原因；同类搜索按模式分别聚合。

紧凑摘要变更后的 `clean verify` 已尝试，但维护者仍打开的 `cc-java --stdio` Java
进程锁住 `cc-java-domain/target/*.jar`，Maven 在 clean 阶段退出；没有终止该用户进程。
该失败发生在编译和测试之前，不代表测试失败。关闭旧交互终端后仍需补一次
`mvnw.cmd clean verify`，因此本证据继续保持 Workspace Verified。

Commit-scoped 复验尚未执行；因此本证据只声明 Workspace Verified，不提升 Stage 或
Capability Level。
