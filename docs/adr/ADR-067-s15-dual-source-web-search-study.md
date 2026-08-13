# ADR-067：S15 TOOL-18 公开 hosted MCP Web Search 机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-12
- Stage: S15 Independent Innovation（G0-G2）
- Feature ID: `TOOL-18`
- Current → Target: `L0 → L2`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenCode commit `0d927ba03f36d7f87e3cdb2b6c1f34c44913a099`，tree `e749e4c946cf0eca237143472882a104bbbbcdb8`
- Classification: 授权快照 `Observed / Inferred / Unknown`；OpenCode 固定公开源码 `Documented / Observed`；本项目采纳边界 `Documented`

## 背景与来源边界

S14 已建立 `NetworkAccessPort`、唯一 `ToolExecutionPipeline` 和生产 Headless composition，`TOOL-18` 仍缺少真实可调用的受控搜索。S15 必须让本项目真正执行 Tool，而不是把 Provider server-side search 当成已经经过参数校验、Permission、Approval、Hook、durable lifecycle 与结果上限。

本轮按 ADR-022 继续遵守授权快照只读边界，并固定研究 OpenCode 官方公开仓库上述 revision。核验根 LICENSE 为 MIT，`Copyright (c) 2025 opencode`；追踪文件为：

- `packages/opencode/src/tool/websearch.ts`
- `packages/opencode/src/tool/mcp-websearch.ts`
- `packages/opencode/src/tool/registry.ts`
- `packages/opencode/src/tool/webfetch.ts`
- `packages/opencode/src/effect/runtime-flags.ts`

只抽象职责、状态、边界、失败恢复和验证方法；没有复制函数体、Prompt、注释、错误文案、私有名称、文件布局或实现常量。本项目使用独立 Java 类型、上限、测试和错误分类。

## 机制结论

| 机制结论 | OpenCode 固定 revision | cc-java 采纳 |
| --- | --- | --- |
| Web search 是内置 Tool，可见性由 Provider/runtime gate 决定 | Observed | 显式本地 `enabled + provider` gate；关闭时不注册 |
| Exa/Parallel 通过 hosted MCP HTTP 调用 | Observed | 固定审核 URI，JSON-RPC 2.0 `tools/call` |
| Exa hosted MCP 可无 key 使用；有 key 时使用 `exaApiKey` query；Parallel 使用 Bearer | Observed | 按 Provider 分离认证：Exa 精确百分号编码固定 query 参数，Parallel 才使用 Bearer；credential 仅来自本地外部配置/环境 |
| 响应可为 JSON 或 SSE | Observed | 仅接受 `application/json` 与 `text/event-stream`（兼容参数），其他或缺失 media type 严格拒绝 |
| Tool registry、Provider gate、HTTP adapter、WebFetch 各自分责 | Observed | 配置、Client、AgentTool、Composition 分层；WebFetch 不进入本切片 |
| Provider 选择可以按 session 策略变化 | Observed | 有意偏离：不复制 session hash A/B 分流，维护者显式选择 provider |
| 外部搜索内容不能自动成为可信指令 | Inferred / Observed | Tool Result 固定标记 external/untrusted，不抓取结果 URL |

## Documented / Observed / Inferred / Unknown

- **Documented**：OpenCode 固定 revision、tree、MIT LICENSE 与五个研究文件身份可复验。
- **Observed**：hosted MCP 使用 JSON-RPC `tools/call`；Exa 目标为 `https://mcp.exa.ai/mcp`、远端 Tool `web_search_exa`，可选 key 使用固定 `exaApiKey` query；Parallel 目标为 `https://search.parallel.ai/mcp`、远端 Tool `web_search`，key 使用 Bearer；响应支持 JSON/SSE；WebFetch 是独立能力。
- **Inferred**：cc-java 必须把 hosted MCP adapter 放在 Pipeline 执行侧，并在每次 HTTP attempt 前调用 `NetworkAccessPort`，才能同时证明 Tool 与网络边界。
- **Unknown**：托管服务内部索引、排序、抓取、重试、区域稳定性、数据保留和所有结果质量；这些均不成为测试 Oracle 或能力声明。

## 采纳与有意偏离

### 采纳

1. `web_search` 是 `BUILT_IN + NETWORK_OR_REMOTE`，默认 ASK；唯一 Pipeline 继续权威管理 validate、Hook、Permission/Approval、durable started/completed、Call ID、裁剪和脱敏。
2. Provider gate 固定生产 endpoint 与远端 Tool；模型只能提供 query 和有界 result limit。
3. JSON-RPC/SSE、HTTP status、timeout、cancel、malformed、oversized、protocol error 和 no-result 均有 typed 失败。
4. 外部结果保持 hosted MCP textual content 的真实形状，不伪造结构化 hit；固定输出 untrusted provenance，不抓取链接。

### 有意偏离

1. 不复制 OpenCode 的 session hash A/B Provider 分流；本地显式选择 `exa|parallel`。
2. 不暴露 endpoint、Header、credential、remote Tool name、crawl/type/context 参数给模型。
3. 删除 SearXNG candidate 的 allowed/blocked domains：hosted MCP 返回自由文本，Runtime 无法对其中每条引用做完整二次 URL 证明，保留参数会形成虚假安全声明。
4. 不实现任意 URL WebFetch、页面正文、redirect follow、缓存或自动重试。
5. `NetworkAccessPort` 是应用层逐次授权，不是 OS Sandbox；JVM socket 不受 S13 process backend 强制。

## 可证伪验证与停止条件

ADR-068 与 G3-G5 必须覆盖：Provider 固定目标；Exa 无 key/百分号编码 query-key、Parallel Bearer 与 secret 零输出；精确 JSON-RPC wire；JSON/SSE 参数兼容、未知或缺失 media type 拒绝；Permission/Network deny 零 HTTP；redirect、429、4xx、5xx、protocol error、malformed、duplicate key、no-result、oversized、timeout/cancel；Call ID 与 lifecycle exactly-once；Headless 默认隐藏/启用可见；真实 Exa 公网；同一路径 `codej --print` 的实时天气 Tool call 大于零。

授权撤回、快照身份变化、公开 commit/LICENSE 无法复验，或实现需要把参考字节带入仓库时立即停止。本 ADR 固定研究边界；能力等级仍由实现、测试和证据共同决定。
