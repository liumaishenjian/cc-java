# ADR-030：S02 隐私安全的 Run Telemetry

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `OBS-02`、`OBS-03`、`OBS-05`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed`，本项目契约为 `Documented`

## 背景

模型调用成功并不等于 Harness 可诊断。S02 需要知道 Run、Model Turn 和 Tool Call
实际耗时，也需要区分 Provider 明确返回的 Token Usage 与本地猜测。同时，观测出口
不能默认复制 Prompt、Completion、Tool 参数、Tool Result 或 API Key。

## 受控参考机制

授权快照把事件驱动观测、Usage 汇总和敏感内容控制视为 Harness 的独立职责。本项目只采用
“由生命周期边界派生指标、缺失 Usage 不估算、默认最小化导出字段”的机制，不复制其类型名、
函数体、日志格式、错误文案、字段布局或常量。

## 决策

1. Core 新增框架无关的 `RunTelemetryCollector`，只消费规范
   `AgentEventEnvelope`，不引入 Micrometer 或 Provider SDK 类型；
2. Run、Model Turn 和 Tool Call 耗时分别由对应开始/结束事件的 `occurredAt`
   计算；终止时仍未闭合的操作标记为未完成，并以 Run 结束时间封口；
3. 每个 Model Turn 只读取 `ModelTurnMetadata.usage()`。只有所有已完成回合都包含
   Provider Usage 且至少有一个已完成回合时，才发布总 Token；缺失回合单独计数，
   不补零、不按字符估算；
4. S02 不维护价格表，也不从 Token 推算金额；可信 Cost 仍为 S14 `MODEL-11`
   的评测与计费治理范围；
5. 对外投影只包含 ID、序号、耗时、完成标记、Finish Reason 和 Token 计数。
   类型中不提供 Prompt、Completion、Tool 名称/参数/结果、System Instructions、
   Provider Endpoint 或 Secret 字段；
6. stdio 终态事件只增加上述安全 telemetry 摘要；面向当前 TUI 的
   `model.text.delta` 与 `finalText` 是显式产品响应通道，不属于观测导出。

## 可证伪验证

- 使用可控 Clock 输入固定事件时间，断言 Run、多个 Turn 和多个 Tool 的耗时；
- 混合“有 Usage/无 Usage”回合时，总 Token 必须为空，且缺失数准确；
- 所有完成回合均有 Usage 时，使用长整型安全汇总并输出；
- 以包含哨兵 Prompt、Completion、Tool 参数/结果和 Secret 的事件流驱动采集器，
  序列化后的 telemetry 不得包含任何哨兵；
- stdio 终态测试断言安全摘要存在，且不新增观测内容字段。
