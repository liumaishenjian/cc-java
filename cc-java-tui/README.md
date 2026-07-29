# cc-java-tui

S02 的 TypeScript + React + Ink 终端 Spike。它只通过内部 stdio v0 与 Java Headless
进程通信，不拥有 Agent Loop、Tool、Permission、Session 私有状态或终态判断。

当前包需要 Node.js 22。普通测试使用确定性 Fake 子进程，不需要网络、模型或 API Key。
真实 Java Fixture 的运行方式见仓库 `docs/demos/S02-tui-spike.md`。
