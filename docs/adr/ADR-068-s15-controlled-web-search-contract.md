# ADR-068：S15 内置受控 hosted MCP `web_search` Java 契约

- Status: Accepted
- Date: 2026-08-12
- Stage: S15 Independent Innovation（TOOL-18 G1-G2）
- Feature ID: `TOOL-18`
- Current → Target: `L0 → L2`
- Depends On: ADR-039、ADR-041、ADR-052、ADR-055、ADR-064、ADR-066、ADR-067

## 决策摘要

新增边缘模块 `cc-java-tools-web`，依赖 Domain/Core、JDK 21 HTTP 与仓库既有 Jackson 3。生产路径只提供一个 `ToolSource.BUILT_IN` 的 `AgentTool`：

```text
model ToolCall(query, result_limit?)
  → ToolRegistry → validate → Pre Hook
  → Permission/Approval (NETWORK_OR_REMOTE, default ASK)
  → durable tool.started
  → WebSearchTool
       → trusted local Provider gate (exa | parallel)
       → fixed hosted MCP URI + fixed remote Tool name
       → NetworkAccessPort.authorize(WEB_SEARCH)
       → JDK HttpClient redirect NEVER
       → JSON-RPC 2.0 tools/call
       → bounded JSON / SSE textual content
  → Pipeline normalize/durable completed/Post Hook
  → matching ToolResult Call ID
```

模型托管 server-tool 和任意 URL WebFetch 均不进入该链路。

## Tool 产品契约

名称固定为 `web_search`，Effect=`NETWORK_OR_REMOTE`，Source=`BUILT_IN`，支持取消，Tool 默认期限 10 秒，最终正文最多 64,000 code point。

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["query"],
  "properties": {
    "query": {"type": "string", "minLength": 1, "maxLength": 512},
    "result_limit": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5}
  }
}
```

query 同时受 512 code point、2 KiB UTF-8 和控制字符限制；不得进入普通 telemetry、错误或审批摘要。Provider、endpoint、Header、credential、remote Tool name、crawl/type/context 与任意 fetch URL 不在 schema 中。

Tool Result 固定包含：

```text
provenance: external-web-search
untrusted: true
contentFetched: false
providerHost: <fixed-host>
--- external untrusted content ---
<bounded hosted MCP textual content>
--- end external untrusted content ---
```

Hosted MCP 返回的是可能带引用的自由文本，因此不伪造 rank/title/url 结构，不连接或抓取其中链接。

## Provider gate、配置与 Secret

生产默认关闭。可信配置只来自环境或 Git ignored `config/provider.local.properties`：

```properties
web-search.enabled=true|false
web-search.provider=exa|parallel
web-search.api-key=
```

- Exa：固定授权目标 `https://mcp.exa.ai/mcp`，远端 Tool=`web_search_exa`，无 key 可工作；可选 `EXA_API_KEY` 经 UTF-8 百分号编码后仅形成 `exaApiKey` query，不发送 Authorization Header。
- Parallel：固定 `https://search.parallel.ai/mcp`，远端 Tool=`web_search`；可选 `PARALLEL_API_KEY` 仅形成 Bearer Header。
- 通用环境覆盖：`CC_JAVA_WEB_SEARCH_ENABLED`、`CC_JAVA_WEB_SEARCH_PROVIDER`、`CC_JAVA_WEB_SEARCH_API_KEY`。
- 生产不读取任意 endpoint 配置；`CC_JAVA_WEB_SEARCH_ENDPOINT` 即使存在也不能覆盖固定目标。
- loopback HTTP 只通过显式测试 seam 允许。
- credential 只进入 Provider 固定认证位置（Exa 的单一 query 参数或 Parallel Bearer），不进入 Definition、Session、事件、异常、`toString()` 或结果；tracked example 永远为空。

生产仓库不会静默向第三方发送 query：只有显式 `enabled=true` 且 provider 合法时才注册 Tool；Permission 仍在每次调用前生效。调用会把 query 发送给所选第三方 hosted MCP，维护者须接受其隐私和服务边界。

## JSON-RPC、HTTP 与网络边界

请求固定为：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "<provider-fixed-remote-tool>",
    "arguments": {}
  }
}
```

Exa arguments 固定包含 query、`type=auto`、result limit、保守 live crawl 和 context ceiling；Parallel 固定把 query 映射为 objective 与单项 search query。模型不能覆盖这些字段。

每次 HTTP attempt 前，Adapter 用 `NetworkPurpose.WEB_SEARCH`、不含 credential 的固定 scheme/host/effective port、deadline 与 `redirectsAllowed=false` 调用 `NetworkAccessPort`。只有 `allowed && controlled` 才发送。发送前再次核对 scheme/host/effective port/path；Exa 只允许 Adapter 精确生成的单一 `exaApiKey` query，Parallel 禁止 query。JDK `HttpClient.Redirect.NEVER`，3xx typed 拒绝。

`NetworkAccessPort` 是应用层出站授权与对账，不是 OS Sandbox、DNS rebinding 全保证、代理/TLS pinning 或 native Windows 网络隔离。

## 响应、上限与恢复

| 项目 | ceiling / 语义 |
| --- | --- |
| query | 512 code point、2 KiB UTF-8 |
| result_limit | 默认 5，最大 20 |
| timeout | 默认 10 秒，最大 30 秒；从 Client 入口覆盖 NetworkAccess、headers、完整 body 与解析的单一 wall deadline |
| HTTP body | 512 KiB，流式读取硬停 |
| SSE | 最多 2,048 行 |
| MCP content | 最多 32 项，只投影 `type=text` |
| external context | 48,000 code point，本地清洗/截断 |
| ToolResult | 64,000 code point，Pipeline 最终硬上限 |

- 只接受 2xx；3xx、429、其他 4xx、5xx 分别 typed。
- 只接受 `application/json` 与 `text/event-stream`，media type 大小写及 `charset` 等参数可兼容；未知或缺失 Content-Type 在读取有界正文后以 `UNSUPPORTED_MEDIA_TYPE` 严格拒绝，不回退 JSON；JSON 使用严格 UTF-8 和 duplicate-key detection。
- 要求 JSON-RPC 2.0 object；`error`、malformed、oversized、没有 textual content 分别映射 `REMOTE_PROTOCOL_ERROR`、`MALFORMED_RESPONSE`、`RESPONSE_TOO_LARGE`、`NO_RESULTS`。
- Client 从 `search` 入口以 monotonic clock 建立单一 deadline；每次下游只消费 remaining duration。一次可关闭的虚拟线程任务覆盖 NetworkAccess、headers、完整有界 body 与 JSON/SSE 解析，解决 `BodyHandlers.ofInputStream` 只完成 headers 后同步正文可能永久阻塞的问题。
- timeout/cancellation 通过 first-wins terminal 控制同时取消 HTTP future、关闭 active stream 并中断 operation；Client close 使用 `shutdownNow` 收敛尚未完成的虚拟线程，不创建永久 scheduler。迟到完成不能形成第二 Tool result，失败只返回 `TIMED_OUT/CANCELLED` 固定分类。
- 外部 ISO control 与 ESC 被清洗；原始 body、endpoint、query、credential 和异常 message 不进入普通错误。
- 第一切片不自动重试，避免重复出站、费用与不可解释结果。

## Permission、Pipeline 与日期语义

只有名称精确 `web_search`、Source=`BUILT_IN`、Effect=`NETWORK_OR_REMOTE` 的宿主 Definition 进入默认 ASK；其他 BUILT_IN Network 继续 hard deny，PLAN 拒绝。可信 Startup/Settings ALLOW 可让非交互 Print 执行，但不能绕过参数、NetworkAccessPort 或 Adapter 目标复验。

系统指令只给通用规则：实时天气、新闻、价格、日程等在 Tool 可用时应搜索，不依赖训练知识；不得硬编码杭州。Session runtime metadata 增加本机当前日期，避免 Provider/model 用过期日期构造 query。

唯一 Pipeline 继续保证 validate、Pre/Post Hook、Permission final、durable started/completed、Call ID、取消、裁剪和脱敏 exactly-once。批准、NetworkAccess 或 Adapter 均不能旁路该链。

## 模块所有权

| 模块 | 职责 | 禁止职责 |
| --- | --- | --- |
| `cc-java-domain` | Tool/Result/Error 协议与 typed code | HTTP、URI/JSON、Secret |
| `cc-java-core` | `NetworkPurpose.WEB_SEARCH`、Permission 窄例外 | endpoint、HTTP、结果解析 |
| `cc-java-tools-web` | Provider/config、hosted MCP client、JSON/SSE、`AgentTool` | Agent Loop、Session、Permission 决策 |
| `cc-java-cli` | 外部配置加载、生产注册、资源关闭、current-date metadata | 旁路 Tool、模型托管搜索 |

## G3-G5 验证与事实边界

专项 Fake/loopback 覆盖 schema、Exa 无 key/百分号编码 query-key、Parallel Bearer、secret/query 不进入失败或输出、精确 JSON-RPC wire、JSON/SSE 参数兼容、未知/缺失 media type 严格拒绝、目标 gate、Permission/Network deny 零 HTTP、redirect/429/4xx/5xx、protocol/malformed/duplicate/no-result/oversized、控制字符、headers+部分正文 stall 后总 wall timeout、慢正文 cancel/释放、Call ID 和 lifecycle exactly-once，以及 Headless 默认隐藏/启用可见。

2026-08-12 已显式运行真实 Exa hosted MCP smoke，并使用安装后的 `codej --print "杭州今天的天气"` 完成真实 Provider→模型 ToolCall→唯一 Pipeline→Exa→ToolResult→最终回答：`web_search` started/completed 各 1、Call ID 匹配、状态 SUCCESS、provider host=`mcp.exa.ai`、Run COMPLETED、最终回答使用 8 月 12 日。

本切片不支持任意 URL WebFetch、页面正文、redirect follow、缓存、重试治理、多环境 SLA 或搜索质量 L3 评测。`TOOL-18 L2` 不代表 S15 Accepted，也不是 L4 创新收益证据。
