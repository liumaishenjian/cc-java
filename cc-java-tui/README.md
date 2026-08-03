# cc-java-tui

S02 的 TypeScript + React + Ink 终端 Spike。它只通过内部 stdio v0 与 Java Headless
进程通信，不拥有 Agent Loop、Tool、Permission、Session 私有状态或终态判断。

当前包需要 Node.js 22。普通测试使用确定性 Fake 子进程，不需要网络、模型或 API Key。
真实 Java Fixture 的运行方式见仓库 `docs/demos/S02-tui-spike.md`。

日常开发不再需要直接进入本目录执行 `npm run dev`。从仓库安装用户级开发 shim 后，
可在任意目标项目目录运行 `codej`：调用目录成为 Workspace，启动器按内容摘要复用或重建
Java 产物，再拉起本 TUI 和 Java `--stdio` 子进程。`codej --print` 是非交互路径，
不是 TUI 预填首条消息。安装与边界见根 README 和 ADR-036。

Run 结束时，TUI 直接展示 Java 终态中的安全摘要，例如
`[failed: turn_limit_reached, modelTurns=16, toolCalls=12]`。单个 Tool 的成功状态不能
替代整个 Run 的终态。
