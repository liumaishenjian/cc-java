# S02 隐私安全 Run Telemetry 证据

- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Feature IDs: `OBS-02`、`OBS-03`、`OBS-05`
- Current → Target: `OBS-02 L0 → L2`、`OBS-03 L1 → L2`、`OBS-05 L1 → L2`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制 `Observed`；本项目契约与实现 `Documented`
- Evidence Commit: `WORKTREE`

## 独立重现行为

1. Run、Model Turn 与 Tool Call 的耗时来自规范生命周期开始/结束边界；
2. 未完成操作在 Run 终态封口，并保持 `completed=false`；
3. 只有 Provider 明确返回 Usage 的回合才计数；
4. 任一完成回合缺失 Usage 时，不输出可能误导的部分总和；
5. stdio telemetry 不包含 Prompt、Completion、Tool 名称/参数/结果、模型名、
   Provider Endpoint 或 API Key。

## 可证伪测试

聚焦命令：

```text
./mvnw.cmd -pl cc-java-cli -am "-Dtest=RunTelemetryCollectorTest,HeadlessRuntimeSessionTest,RuntimeStdioCommandHandlerTest,StdioProtocolProcessTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：Core 3/3，CLI 8/8，通过。

完整回归：

```text
./mvnw.cmd verify
./mvnw.cmd "-DskipTests" javadoc:aggregate
cd cc-java-tui
npm.cmd run check
```

结果：Domain 1/1、Core 39/39、Provider 普通 21 项执行且真实网络 2 项默认跳过、
SSE Contract 4/4、CLI 29/29、TUI 22/22、聚合 Javadoc 全部通过。

关键反例：

- 两回合中一回合缺失 Usage，`totalUsage` 必须为空；
- 所有完成回合均有 Usage，长整型总和必须准确；
- 系统时钟回拨时耗时不得为负；
- 包含敏感哨兵的生命周期事件驱动采集后，Telemetry 文本不得出现哨兵；
- 真实 Runtime stdio Adapter 的终态中存在安全 telemetry，响应正文只保留在显式
  `finalText` 产品通道。

## 仍然缺失

- S02 不提供 Micrometer/OpenTelemetry Backend；
- 不维护模型价格表，不推算 Cost；
- 不提供稳定外部 Telemetry Schema、Retention 或 Export Policy；
- 上述生产级能力仍属于 S14。
