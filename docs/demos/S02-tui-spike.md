# S02 React/Ink TUI Spike Demo

该 Demo 默认启动真实 Java Headless、`AgentRuntime` 与本地配置的
OpenAI-compatible Provider。测试专用 Java Fake Server 仍只用于自动化协议负例。

## 前置条件

- JDK 21；
- Node.js 22；
- 在 `cc-java-tui` 执行过 `npm.cmd ci`；
- 已填写 Git 忽略的 `config/provider.local.properties`。

## 非交互正例

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\RunS02TuiSpike.ps1 -Prompt "hello"
```

预期输出为真实模型回答，输出不含 ANSI，成功退出码为 0。
脚本会先输出 Java 构建阶段提示；本机首次构建可能需要 1～2 分钟，`-Timeout` 只约束
Agent Run，不约束 Maven 构建。

已经成功构建过且源码、POM 和依赖没有变化时，可以跳过 Maven：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\scripts\RunS02TuiSpike.ps1" `
  -Prompt "介绍一下你自己" -Timeout "30s" -SkipBuild
```

`-SkipBuild` 会验证主类和 classpath 文件确实存在；代码或依赖变化后不得继续使用，
应先重新执行一次不带该参数的命令。

## 交互正例

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\RunS02TuiSpike.ps1
```

开发脚本也可以把类型化 Override 传给 Java stdio：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\RunS02TuiSpike.ps1 `
  -Workspace "E:\Java\cc-java" `
  -Timeout "30s" `
  -Prompt "介绍一下你自己"
```

`-Model` 可选；API Key 和 Base URL 仍不接受脚本参数。

输入任意单行任务并回车。观察：

- 状态从 `ready → running → ready`；
- 真实模型文本按 Delta 显示；
- Run 终态显示 `[completed]`；
- 活动 Run 第一次 `Ctrl+C` 发送 `run.cancel`，第二次直接终止 Java；
- 空闲 `Ctrl+C` 先发送 `shutdown`，超时才强制终止，并等待 Java exit；
- Resize 后已有 Run 和未提交输入仍保留；
- Paste 最多进入 8192 个 Unicode Code Point。

## 自动负例

```powershell
Set-Location cc-java-tui
npm.cmd test
```

`StdioClient` 的负例覆盖乱序 sequence、活动 Run 崩溃、忽略取消和忽略 shutdown。
Client 必须报告固定失败、终止并等待子进程退出；测试会使用捕获的 PID 证明不存在，
而不是只断言调用过 `kill`。

## 当前边界

真实 Demo 需要本地 API Key 并访问维护者配置的端点，但不会输出密钥或完整地址。
当前不注册文件或 Shell Tool。普通自动测试仍不需要网络和密钥；真实 Provider 测试必须
通过 `-Dccjava.real-provider=true` 显式启用。详见
[ADR-024](../adr/ADR-024-s02-openai-compatible-first-provider.md)。
Windows 输入与进程边界见
[ADR-028](../adr/ADR-028-s02-windows-terminal-lifecycle.md)及其
[验证证据](../evidence/S02-windows-terminal-lifecycle-2026-07-29.md)。
