# ADR-066：S14 Production Harness 独立契约

- Status: Accepted
- Date: 2026-08-10
- Stage: S14 Production Harness（G1-G2）
- Depends On: ADR-030/037/039/041/043/047/055/057/060/064/065
- Feature IDs: `CLI-11/12`、`LOOP-12`、`MODEL-07/09/10/11/12`、`SEC-10`、`CTX-16`、`CFG-07/10/11`、`SESSION-08/12/13/14`、`PLUGIN-04/05`、`OBS-04/06`、`EVAL-03`、`DIST-02..06`

## 决策摘要

S14 新增三个有当前用途的边缘模块：

- `cc-java-protocol`：stable v1 schema/envelope/codec/connection state；不拥有 Runtime；
- `cc-java-sdk`：嵌入式 Java Client/Application façade；复用同一 Application Service；
- `cc-java-observability-otel`：直接 OpenTelemetry SDK Adapter；Core 只见项目自有 telemetry port。

Daemon 留在 `cc-java-cli` composition。唯一 `AgentRuntime` 与 `ToolExecutionPipeline` 保持不变；JSON、OTel、Spring、Provider、Path 与 HTTP 类型不得进入 Domain/Core。

## Batch 1：Provider + Eval + Observability

### Provider capability/router

```text
configured capability
  + observed probe evidence
  → effective capability snapshot
  → route candidate ordering
  → shared deadline/cancel/retry/cost budget
  → primary attempt
  → fallback only before visible/durable intent
  → unique terminal
```

- 第二 Provider 使用 Spring AI Anthropic Adapter。真实测试显式启用，分别记录 text/stream/tool/multi-tool/usage/cancel/429/5xx/context-limit 的 PASS/SKIP/UNSUPPORTED。
- `ModelProviderCapabilitySnapshot` 分离 configured、observed、effective；未知能力不视为支持。
- 所有 attempt 共享 deadline、CancellationToken、最大请求数和可选保守成本单位。`ModelGatewayException` 现携带最长五分钟的 typed `Retry-After`，Core 采用 `max(policy delay, provider delay)` 且等待可取消；OpenAI/Anthropic Adapter 仍须在协议 mock 中证明 Header 解析后，才可宣称对应 Provider 端到端尊重该 header。
- Fallback 只在 `visibleDeltaCount=0` 且失败被 Adapter 明确分类为 retryable 时发生。现有 Gateway 只在成功返回时暴露 Assistant/Tool intent，Router 无法直接观察 Provider 内部“已 durable 但尚未返回”；Adapter 对这种不确定状态必须分类为不可重试。部分输出或成功返回的 Tool Call 绝不切换。
- 价格表只接受可信、明确版本的配置；未知价格返回 unknown，不伪造零成本。
- Cache hint 和 native context editing 只在 Adapter 能表达且 A/B 成功率不下降时启用；通用 S07 路径永远可用。

### Network access

Core 使用 `NetworkAccessPort` 表达目的、目标摘要、deadline、redirect policy 与审计结果；具体 `HttpClient`/Spring factory 位于 Adapter。第三方 SDK 无法受控创建/执行连接时必须报告 `UNSUPPORTED_CONTROL`，不得计作网络策略通过。

### OTel

`TelemetryExporter` 只接收 typed whitelist：run/turn/tool/retry/recovery/stop/token/cost-known/latency。默认 No-op；OTel Adapter 使用有界队列，flush/shutdown 有 timeout，exporter 故障只关闭观察面，不改变 Run。正文与 Secret sentinel 泄漏必须为 0。

### Eval

至少 12 public seeds × 每 Provider ≥5 重复才具备真实 L3 候选。离线 Deterministic Eval 报告完成率、stop/recovery、模型回合、Tool 次数、usage/cost-known、墙钟、违规和 cache A/B。L3 非劣界 5pp；cache 仅在成功率不降时要求适用样本 input token 中位数改善 ≥20%。条件不足时如实保持 L2。

## Batch 2：Protocol + SDK + Daemon + Session Lifecycle

### stable v1

v1 使用项目自有 envelope，不兼容复制任何参考 wire：

```text
schemaVersion, messageType, messageId, correlationId,
sessionId?, runId?, sequence, idempotencyKey?, payload
```

- 每连接 `initialize` 恰一次；服务端每次启动生成至少 256-bit capability token。
- initialize 协商 protocol major/minor、capabilities 与 feature gates；major 不兼容拒绝。
- typed request/response/event/error；未知必需类型、重复字段、乱序、超限和非法状态 Fail Closed。
- ingress/egress 均有界；背压不丢 terminal，不重排 correlation/sequence。
- cancel/disconnect fence 阻止迟到副作用和事件；每 Run 恰一个 terminal。
- graceful shutdown 先拒绝新请求，再有界等待，最后取消活动 Run。
- v0/v1 双栈至少保留一个 release；v0 不原地改义。

### SDK/Application service/Daemon

Java SDK、stdio v1 与 loopback daemon 共用 `AgentApplicationService`，由 CLI composition 注入同一 Runtime factory。生产入口现包含 `--stdio-v1` 与 `--daemon`；二者均使用 `StableProtocolHandler`、高熵 token、strict v1 codec/state、同一 Runtime/Pipeline。Daemon 只绑定 loopback、使用单实例 ownership 文件，面向本机长生命周期；不实现远程监听、TLS、账户认证或多租户。历史独立 JSON application prototype 仅保留兼容测试，不再作为 stable 能力证据。

### Session lifecycle

- Export v1 默认 metadata-only；正文只能在 `includeContent=true + redaction policy + explicit confirmation` 时产生。
- Retention 默认 `plan → archive`；permanent delete 要求二次确认，且 active/uncertain/incomplete-side-effect/migrating 一律拒绝。
- Migration 使用 exclusive lock/fence、journal、staging、逐记录 verify、atomic publish；每个崩溃点重启可恢复或保留旧 canonical，不产生双事实源。
- Canonical JSONL 仍是事实源；`SessionIndex` 是可重建 projection。10k list/search p95≤250ms、rebuild≤30s、额外内存≤256MiB 为冻结门槛；未达才提议 SQLite，不机械引入。
- `SESSION-08` 增加 ownership metadata、heartbeat/stale 判断与保守 reclaim；网络文件系统/多主机不宣称支持。

## Batch 3：Governance + Plugin Recovery + Distribution

### Managed Policy / Feature Gate

本机管理员固定 root 提供 strict schema、provenance、digest 与 LKG。Managed 只能收窄 user/project/session。安全项声明存在但当前无可信值/LKG 时 Fail Closed；非安全项可保留可信 LKG并诊断。Feature gates 显式分 `STABLE/EXPERIMENTAL`，并投影 protocol capabilities；实验 gate 不改变稳定 schema 语义。

### Plugin

`PLUGIN-04` 在现有 staged/quiescing 上增加 transaction journal、install/uninstall/registry migration recovery、staging/orphan/tombstone 对账，达到 L2。`PLUGIN-05` 只增加 `PluginSignatureEnvelope` 和 `PluginSignatureVerifier` Port：验证签名字节、算法 ID、payload digest 与 key reference；不宣称 publisher identity、revocation、root rotation、透明日志或 Marketplace。

### Distribution

- 生成 Runnable JAR/app-dir、Windows/Linux launcher、release manifest、SHA-256 checksums、CycloneDX-compatible minimal SBOM 与 compatibility policy；SBOM 坐标来自 JAR 唯一 Maven metadata，或缺失时来自 Maven resolver 且以 JAR digest 绑定，缺失/歧义 Fail Closed，禁止从文件名猜测。
- install/upgrade 使用 staging、完整 checksum/manifest verify、原子 current pointer 或可恢复替换；失败 rollback 到 LKG。
- N/N-1 compatibility 只有真实已发布 artifacts 才可 L3；当前本地/CI candidate 最多 L2。
- License 未决，仅生成本地/CI artifact，不公开 Release；Native Image、macOS installer、公开更新渠道延期。

## 验证与三 Batch

1. **Provider + Eval + Observability**：Fake/fault、第二 Adapter、router、NetworkAccessPort、OTel privacy/queue/failure、统一 Eval。
2. **Protocol + SDK + Daemon + Session Lifecycle**：v1 codec/state/backpressure/semantic idempotency/cancel/disconnect、SDK/stdio/stable loopback handler 目标，以及独立 application prototype 的诚实边界、Export/Retention/Migration/Index 10k。
3. **Governance + Plugin Recovery + Distribution**：Managed/LKG/feature gates、plugin transaction recovery/signature port、packaging/install/upgrade/rollback。

最终运行标准 clean Maven、TUI、launcher、Dashboard generate/check/self-test、secret/restricted-expression scan，以及条件满足时的 Win/Linux/loopback/Anthropic/OTel/packaging real suites。真实凭证或平台缺失必须记录 SKIP/UNSUPPORTED，不计 PASS。

## 被否决方案

- 在 SDK、协议或 Daemon 建第二套 Agent Loop；
- 让 Provider/OTel/JSON 类型进入 Domain/Core；
- 已发布 delta 或 durable intent 后 fallback/replay；
- 未知价格按零或估算金额；
- 让 OTel exporter 故障终止 Run；
- 修改 stdio v0 语义冒充 v1；
- 只绑定 loopback却不验证 token/ownership；
- 直接删除 active/uncertain Session；
- SQLite 先于 10k benchmark；
- fingerprint/signature envelope 冒充作者身份或撤销；
- License 未决时公开发布 artifact。
