# S02 Provider / Runtime / TUI 集成证据（2026-07-29）

## 范围

- Stage：S02 Model + Streaming CLI
- Feature：`BOOT-01`、`BOOT-03`、`CLI-01`、`CLI-03`、`CLI-04`、`CLI-06`、
  `CLI-10`、`CLI-11`、`LOOP-04`、`LOOP-08`、`LOOP-10`、`MODEL-02`、
  `MODEL-04`、`MODEL-05`、`MODEL-06`、`CFG-02`、`OBS-03`、`OBS-05`
- Reference Behavior Baseline：`R2026.03`
- Authorized Snapshot ID：`N/A - Not Used`
- 分类：官方 API 为 `Documented`；真实 Provider 与端到端结果为 `Observed`；
  Core/Adapter 离线测试为 `Tested`

## 实现边界

- Domain/Core 不依赖 Spring AI、Reactor、Jackson、React 或 Ink；
- `SpringAiModelGateway` 直接消费 `ChatModel.stream`，发布文本增量并在返回前聚合
  完整 Assistant Message 与 Tool Call；
- Spring AI 只获得不可执行的 Tool Definition callback；若框架错误尝试执行会立即失败；
- Runtime 继续拥有 Tool Loop、Session 历史、事件顺序、取消与唯一终态；
- `RuntimeStdioCommandHandler` 只映射 stdio 命令/事件，不复制 Agent Loop；
- 当前 Tool Registry 为空，不提供文件、Shell 或其他 S03/S04 能力。

## 可复现验证

离线适配器与 Core：

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am test
.\mvnw.cmd -pl cc-java-core -am test
.\mvnw.cmd -pl cc-java-cli -am test
```

真实 Provider（显式 opt-in，普通 CI 默认 Skip）：

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am `
  '-Dtest=OpenAiProviderSpikeTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dccjava.real-provider=true' test
```

真实 TUI → stdio → Runtime → Provider：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\RunS02TuiSpike.ps1 `
  -Prompt "请用一句简短中文确认：cc-java S02 真实链路已连接。"
```

2026-07-29 本机结果：

- Provider Spike：1/1 通过；文本流、聚合文本、Usage、Finish Reason、原始 Tool Call
  的名称/ID/参数均满足结构断言；
- Core：28/28 通过；包含 Delta 顺序、精确 Run 取消、唯一取消终态；
- Model Adapter 普通测试：11 项通过，真实测试默认 1 项 Skip；
- CLI：20/20 通过；Fake 继续覆盖协议乱序、唯一终态、跨进程取消和 Java Print；
- 全量 `clean verify`、零警告聚合 Javadoc 与 TUI `npm run check` 通过；
- 真实非 TTY E2E 退出码 0，输出为
  `cc-java S02 真实链路已连接。`
- 从 `C:\Windows\System32` 使用脚本绝对路径启动同样退出码为 0；脚本通过根
  `pom.xml` 定位 Maven reactor，不依赖调用者当前目录。

真实响应只记录上述最小演示文本；未记录 API Key、完整端点、完整 Prompt 或模型内部响应。

## 已证实与未证实

已证实：

- Spring AI 2.0.0 / Spring Boot BOM 4.1.0 依赖在 Enforcer 下收敛；
- 真实文本流与 Tool Call 可通过项目 Adapter 返回；
- Reactor/Provider 类型没有进入 Domain/Core；
- Adapter 不执行 Tool；
- 本地配置被 Git 忽略，真实值不进入证据和输出；
- React/Ink 非 TTY 可以驱动真实 Java Runtime 并输出无 ANSI 文本。

仍未证实：

- 一个真实回合内多个 Tool Call 及跨 Chunk 参数拼接；
- Provider 限流、空响应、不完整流与输出长度上限的真实行为；
- 真实网络请求中途取消后的端点级事件静默；
- 完整 TTY 多轮交互、Resize/Paste/崩溃/无孤儿进程负例；
- Timing/Cost 的正式持久化或可观测输出。

因此该证据支持矩阵中的局部等级提升，但不支持 S02 Stage Exit。

Java Picocli `--print` 的后续实现与真实验证见
[S02 Picocli Java Print 证据](./S02-java-print-2026-07-29.md)。
