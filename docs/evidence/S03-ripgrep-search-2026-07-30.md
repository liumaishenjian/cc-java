# S03 ripgrep 搜索机制校正证据

- Status: Worktree Verified
- Date: 2026-07-30
- Stage: S03 Read Tools 退出后维护
- Capability IDs: `TOOL-05`、`TOOL-12`、`TOOL-13`、`SEC-03`、`SEC-10`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Decision: [ADR-033](../adr/ADR-033-s03-ripgrep-search-backend.md)

## 机制与独立边界

授权快照只用于提炼“专用 Tool 驱动 ripgrep、结构化参数、ignore/敏感过滤、超时取消、
结果裁剪和失败恢复”的职责。本项目没有复制参考函数体、Prompt、命名、布局、常量、
Fixture 或 Golden Output。

## 当前实现

- `TextSearchBackend` 隔离 Tool 契约和执行引擎；
- `RipgrepSearchClient` 通过 `ProcessBuilder(List)` 执行 PATH 中的 rg；
- 默认尊重 ignore、搜索 hidden、排除 VCS/敏感路径、不跟随链接；
- stdout/stderr 并发有界读取，10 秒超时，原始 stderr 和绝对路径不外泄；
- 支持字面/正则、Glob/type、大小写、多行、before/after/context、行号控制、
  content/files/count 与 offset/limit；`context` 明确覆盖 before/after；
- 使用 rg JSON Lines 机器协议，Windows/冒号路径不靠拆分文本推断；所有返回路径再次
  经过 WorkspaceGuard，files 模式按 mtime 降序、路径稳定打破并列；
- Run 取消传播到搜索进程；超时与取消分码，资源不足只执行一次 `--threads 1` 重试，
  每次终止都会清理进程树并排空有界 stdout/stderr；
- `cc-java.ps1` 与 TUI 启动器共享 resolver：显式环境变量、PATH、本机 Codex Desktop
  既有 rg 依次解析，不下载或提交二进制；
- rg 不可用时字面搜索降级到 Java；正则请求明确失败；
- RAG 不进入精确代码搜索。

## 已完成验证

```text
./mvnw.cmd -pl cc-java-tools-local -am
  -Dtest=ListAndSearchToolTest,RipgrepJsonLinesParserTest,RipgrepSearchClientTest
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Windows 沙箱外结果：

- `./mvnw.cmd clean verify` 通过：Domain 1/1、Core 44/44、Provider 23 项中
  21 通过且 2 个显式真实网络 Spike 跳过、Tools Local 37 项中 36 通过且 1 个
  非 Windows Symlink 用例跳过、CLI 34/34；
- 搜索聚焦测试 23/23：`RipgrepSearchClientTest` 10/10（真实 rg 三模式、ignore/
  敏感预过滤、安全模板、超时、取消、进程树、输出上限、一次降线程重试和 stderr
  脱敏），`RipgrepJsonLinesParserTest` 5/5，`ListAndSearchToolTest` 8/8；
- `HeadlessRuntimeSessionTest` 9/9：真实五 Tool E2E 与 content/files 两页/count
  高级搜索 Agent Loop 均通过，Call ID、continuation、上下文和敏感隔离已断言；
- `npm.cmd run check` 通过：TypeScript 编译与 TUI 25/25；
- `./mvnw.cmd javadoc:aggregate` 通过；
- Windows Junction 专项包含在 Tools Local 全量回归中并通过 1/1；
- Windows PowerShell 清空 rg PATH 后，resolver 成功找到本机既有 Codex Desktop rg
  并执行 `ripgrep 15.1.0`；启动日志不输出绝对工具路径；
- 真实 Provider opt-in TUI 使用同一用户任务依次完成 files/content/count：
  26 个文件、content 48 处匹配、count 47 次匹配，并以相对路径和行号完成最终回答；
  60 秒首次运行在工具成功后因总 Run 墙钟到期，改用 3 分钟后完整结束，证明该现象不是
  `SEARCH_UNAVAILABLE` 或 rg 子进程卡死；
- 看板生成、`--check` 与 `--self-test` 全部通过。

## 待关闭

- 获得维护者单独授权后创建 Commit，并完成 Commit-scoped 复验；
- 内置/嵌入式 rg、签名和跨机器发行仍属于 S14，不计入 S03 退出目标。
