# S14 Production Harness Demo

- Verified Commit: `dff814c1bb5a659979e007061e6d10a0a9ff6e82`
- Stage Exit: Accepted with documented deviations

## 前置条件

JDK 21、PowerShell 7。普通 Demo 不需要 Provider 密钥或外网。真实 Anthropic/OpenAI 重复 Eval 仅在显式提供仓库外凭证时运行。

## 1. stable v1 / SDK / Daemon

```powershell
.\mvnw.cmd -pl cc-java-protocol,cc-java-sdk,cc-java-cli -am test
```

观察：v1 codec 拒绝重复/未知/非整数/超限字段，payload 不可回改；生产 `StableProtocolHandler` 同时使用 codec/state，覆盖 initialize token/feature negotiation、initialize 纳入 strict sequence、request/response/event/error、semantic idempotency、run/cancel、disconnect fence、唯一 terminal、bounded ingress/egress、terminal-preserving backpressure 与 drain。CLI 新增显式 `--stdio-v1`，保持 `--stdio` v0 原义；`ProductionHarnessFactory` 为 SDK/Application/stable handler 打开同一 Headless Runtime/Pipeline。独立 loopback HTTP JSON v0 仍保留为明确非 stable 原型，不计 stable wire 证据。

## 2. Provider Router / Eval / OTel

```powershell
.\mvnw.cmd -pl cc-java-core,cc-java-model-spring-ai,cc-java-observability-otel -am test
```

观察：有效 capability 取 configured/observed 保守结果；primary 在无可见 delta 的 retryable failure 后可 fallback，已有 delta 后禁止；未知价格没有费用值；OTel 只接受白名单并在关闭/故障时不改变调用方；Anthropic Factory/可选配置离线通过。Anthropic protocol mock 的 text/stream/tool/multi-tool/usage/cancel/429 Retry-After/5xx/context-limit 为 4/4 PASS；真实 Anthropic 因无凭证记 Accepted Deviation，不计通过。真实 OpenAI-compatible text/stream/single-tool/usage E2E 使用 gitignored 配置为 1 PASS，multi-tool 条件项 SKIP。

## 3. Session lifecycle

```powershell
.\mvnw.cmd -pl cc-java-cli -am -Dtest=S14InfrastructureTest test
```

观察：metadata-only export 不含正文 sentinel；正文导出要求脱敏+确认；migration 使用流式限额、realpath/link 防护、lock/staging/逐记录 verify/publish 且源 JSONL 不变；全部 7 个 crash point 均在重启后恢复发布或保留旧 canonical。普通文件 SessionIndex 重开 10k 往返不再截断，机器可读 artifact 记录 rebuild/list-search p95/额外 heap 并通过冻结 SLA，故接受继续使用普通文件 projection、不机械引入 SQLite。

## 4. Packaging

```powershell
.\mvnw.cmd -DskipTests package
pwsh -NoProfile -File .\scripts\BuildRelease.ps1 -SkipBuild
pwsh -NoProfile -File .\scripts\TestBuildRelease.ps1
.\target\release\codej.cmd --help
```

观察：生成 app-dir、Windows/Linux launcher、release manifest、SHA256SUMS、SBOM；SBOM component 从每个 JAR 唯一 Maven `pom.properties` 或 Maven resolver 的确定性 artifact metadata 提取 group/artifact/version 并生成 purl，resolver 坐标以 JAR SHA-256 绑定，缺失或歧义 Fail Closed，绝不猜文件名。自测明确断言 picocli 4.7.7、Spring AI Anthropic 2.0.0、Anthropic Java 2.40.1 与 cc-java 0.1.0-SNAPSHOT，并复验 component 非空、全部 checksum 与输出路径越界负例；manifest 明确 `publicReleaseAllowed=false`。升级使用 staging/rollback；本 Demo 不 commit、push 或公开发布。

## 5. Commit-scoped 退出复验

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -DskipTests javadoc:aggregate
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
```

实际结果：首次 clean verify 的历史 `AgentRuntime` cancellation 2 秒窗口偶发 timeout；同一用例立即隔离重跑 1/1 PASS，第二次完整 clean verify BUILD SUCCESS（911 tests/10 skips，0 failure/error）。严格 aggregate Javadoc BUILD SUCCESS、0 warning，Dashboard check/self-test PASS。该历史失败未从证据中删除，也未因此修改生产行为。

## 边界

统一 Eval artifact 为 12 个注册 seeds×5=60 个真实 production-harness 场景，覆盖 direct final、built-in Tool 多回合、Call/Result ID、permission deny/tool failure 后恢复、cancel、turn limit、context preparation、canonical Session create/continue/resume、SDK Tool loop 与 stable initialize/run/event/唯一 terminal/idempotency；指标只从 AgentRunResult、模型实际收到的 ToolResult、事件和 stable envelope 聚合。本轮 60/60 完成、0 violation，usage/cache/cost 仍如实 unknown；真实 OpenAI 和 Anthropic mock 继续作为独立 suite，不复制为 Eval route，也不声明 L3。Plugin registry migration 已以 global writer/journal/digest/create-only publish/restart recovery 达 L2；Marketplace、publisher identity/revocation/root rotation/transparency log 仍未实现。远程 Daemon/TLS/账户/多租户、SQLite、Native Image/macOS installer/公共更新渠道仍是 gap。
