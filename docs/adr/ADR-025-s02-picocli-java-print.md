# ADR-025：S02 固定 Picocli Java Print 与退出码

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Feature IDs: `BOOT-01`、`CLI-02`；`CFG-01` 仍 Deferred
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: Picocli API 为 `Documented`；本项目设计为 `Inferred`；测试和真实 Demo 为 `Observed`

## 背景

React/Ink 已承担交互式终端，但自动化、管道和最小故障诊断仍需要一个不渲染 TUI 的
Java Headless 入口。该入口不能复制 Agent Loop，也不能让模型 SDK 自动执行 Tool。

Picocli 官方 Quick Guide 4.7.7（2025-04-16）说明 `CommandLine.execute` 返回退出码，
`Callable<Integer>` 可以定义应用退出码；官方 `CommandLine.ExitCode` 文档定义成功
`0`、软件错误 `1` 和用法错误 `2`。这些来源只约束参数解析，不定义本项目 Agent 行为。

## 决策

1. 使用 `info.picocli:picocli:4.7.7`，只解析互斥的 `--print <prompt>`、`--stdio`
   以及标准 `--help`/`--version`。
2. Print 和 stdio 共用 `HeadlessRuntimeSession` 装配到同一个 `AgentRuntime`；
   Surface 不复制模型/工具循环。
3. Print stdout 只输出 Assistant 文本，不输出 ANSI、生命周期或诊断；stderr 只输出
   不含 Prompt、API Key、端点和 Provider 原始响应的固定分类。
4. 退出码固定为：成功 `0`、运行失败 `1`、参数/配置错误 `2`、用户取消 `130`。
5. `cc-java.ps1` 以自身目录定位根 POM 和配置，可从任意当前目录运行；参数通过数组传给
   Java，不拼接 Shell 字符串。
6. API Key 不提供 CLI 参数。本 ADR 当时尚未实现 Workspace、Model 等通用 Override，
   `CFG-01` 保持 L0；后续状态以 ADR-026 为准。

## 可证伪验证

- Fake Runner 证明 Picocli 模式互斥、帮助不启动 Runtime、退出码原样返回；
- Fake Model 经过真实 `AgentRuntime` 完成 Print，而不是旁路调用 Provider；
- Delta 输出不重复聚合文本，非流式 Fake 可回退一次最终文本；
- 从 `C:\Windows\System32` 以绝对脚本路径执行 `--help` 和真实 `--print`；
- 普通离线测试不读取本地配置、不访问网络，真实模型只显式用于 Demo。

## 结果与差距

上述测试与真实 Demo 已通过，`BOOT-01`、`CLI-02` 达到 S02 L2。真实网络请求的强制
超时和 CLI Override 已由后续
[ADR-026](./ADR-026-s02-cli-overrides-run-deadline.md)关闭；运行中 TTY Ctrl+C、
Provider 异常分类和其他 S02 负例仍是后续工作。本 ADR 不支持 S02 Stage Exit。

## 官方来源

- [Picocli Quick Guide 4.7.7](https://picocli.info/quick-guide.html)，访问日期 2026-07-29
- [Picocli CommandLine.ExitCode API](https://picocli.info/apidocs/picocli/CommandLine.ExitCode.html)，访问日期 2026-07-29
