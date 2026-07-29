# S02 CLI Override 与墙钟超时证据（2026-07-29）

## 范围

- Stage: `S02 Model + Streaming CLI`
- Feature IDs: `CFG-01`、`CTX-01`、`LOOP-08`、`CLI-06`
- Current → Target: `CFG-01 L0 → L2`、`CTX-01 L1 → L2`；
  `LOOP-08/CLI-06` 保持 L1
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Classification: 本项目设计与测试为 `Observed`，真实 Provider 超时为 `Observed`

## 离线证据

```powershell
.\mvnw.cmd -pl cc-java-cli -am test
```

结果：

- Domain 1/1：默认/显式墙钟限制与非法 Duration；
- Core 29/29：50ms Deadline、取消传播、`TIME_LIMIT_REACHED`、唯一终态和迟到 Delta 抑制；
- Provider 普通测试 12 项通过、真实测试 1 项默认 Skip：模型覆盖保留 Secret 且重新校验；
- CLI 25/25：Override 解析、Duration 语法/范围、Workspace 失败、Session Metadata、
  超时/用户取消退出码和既有 stdio 回归。
- TUI 13/13：非 TTY 把 `time_limit_reached` 映射为固定诊断和退出码 1，
  不透传 Provider 错误文本。

普通测试不读取真实配置、不访问网络。

## 真实 Provider 负例

从 `C:\Windows\System32` 执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\cc-java.ps1" `
  --workspace "E:\Java\cc-java" `
  --timeout 10ms `
  --print "请回复一句中文"
```

结果：

- stderr：`cc-java: run timed out`
- 退出码：1
- 超时后没有 Assistant Text 或其他迟到事件；
- Workspace Override 被接受；
- API Key、Base URL、模型响应和底层异常没有出现在输出。

该负例证明本机真实 Spring AI/OpenAI-compatible 订阅能够响应 Runtime Deadline；
它不证明所有 Provider 或网络栈的跨平台取消语义。

同一参数也通过 React/Ink 非 TTY 入口验证：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\scripts\RunS02TuiSpike.ps1" `
  -Workspace "E:\Java\cc-java" `
  -Timeout 10ms `
  -Prompt "请回复一句中文"
```

结果同样输出 `cc-java: run timed out`、退出码为 1，且没有 Assistant Text。
这证明该失败终态已从 Java Runtime 经 stdio v0 到达 TypeScript 终端适配层；
非 TTY 层只按结构化 `stopReason` 映射固定文案，不展示底层异常。

## 等级结论

`CFG-01` 已有类型校验、真实 Workspace/Timeout 和模型覆盖离线证据，提升至 L2。
`CTX-01` 已把实际 Workspace/模型/Timeout 作为不可变 Runtime Metadata 组装，提升至 L2。
剩余多 Tool Call、限流/重试、不完整流、长度恢复及 TTY/进程负例继续阻塞 G4-G6。
