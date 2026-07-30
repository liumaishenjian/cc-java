# S04 Patch/Write 工作区证据

- Date: 2026-07-30
- Stage: S04 Write + Command
- Feature IDs: TOOL-08, TOOL-09, TOOL-14, CLI-05, SEC-01
- Reference Baseline: R2026.03
- Authorized Snapshot: AUTH-SRC-2026-07-29-A
- Classification: WORKTREE_VERIFIED
- Commit: WORKTREE

## 受控研究结论

授权快照只用于提炼 Edit/Write 的职责、前置条件、文件变化检测和审批 Diff 机制。
本项目没有复制函数体、Prompt、错误文案、私有类型名、文件布局或常量。独立契约固定在
ADR-035：精确旧内容替换、显式全替换、创建与覆盖分离、提交前冲突重检和有界审批摘要。

## 已验证行为

- `apply_patch` 唯一匹配、显式多匹配替换、BOM/CRLF 与无关脏内容保持；
- `write_file` 只创建新文件，不覆盖已有文件，不创建缺失父目录；
- 绝对路径、Traversal、敏感路径、外部 Symlink/Junction 逃逸拒绝；
- 同目录暂存、文件竞态冲突、取消和正常失败临时文件清理；
- Print/非交互 ASK 拒绝后文件不变；
- Allow Once 后真实 Patch 经统一 Pipeline 执行；
- stdio/TUI 仅展示相对路径、创建/修改与增删行数。

## 当前验证

当前工作区已经执行并通过：

- `./mvnw.cmd verify`：160 项 Java 测试，157 通过，3 项真实 Provider/Symlink 环境跳过；
- `./mvnw.cmd javadoc:aggregate`：通过；
- `npm.cmd --prefix cc-java-tui run check`：36 项 TUI 测试及 TypeScript 检查通过；
- `java scripts/ProgressDashboard.java --check` 与 `--self-test`：通过。

跳过项不属于 Patch/Write 功能失败：2 项真实 Provider Spike 默认需要显式启用，1 项
Symlink 测试受当前 Windows 环境能力限制；Windows Junction 专项测试已通过。

## 剩余差距

Command、输出流、超时、进程树、最小环境与公开 Fixture 编码闭环尚未实现，因此
TOOL-10、TOOL-11、SEC-04、SEC-05 和 EVAL-01 保持 L0，S04 G3-G6 与 Stage Exit 保持 Open。
