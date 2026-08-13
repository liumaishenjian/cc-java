# S15 TOOL-18 Web 搜索差距报告

- Date: 2026-08-12
- Reference Baseline: R2026.03
- Authorized Snapshot: AUTH-SRC-2026-07-29-A
- Public Snapshot: OpenCode `0d927ba03f36d7f87e3cdb2b6c1f34c44913a099` / MIT
- Stage: S15 IN_PROGRESS / Exit OPEN
- Capability advanced: TOOL-18 L0 → L2

## 已验证

固定 Exa/Parallel hosted MCP Provider gate、显式本地启用、严格 query/result schema、Exa 无 key/精确百分号编码 query-key、Parallel Bearer、JSON-RPC 2.0 `tools/call`、严格 JSON/SSE media type、有界响应、覆盖 NetworkAccess→headers→完整 body→解析的总 wall deadline、timeout/cancel HTTP+stream+operation 收敛、Network Effect、唯一 Permission/Approval/Hook/Pipeline、逐次 NetworkAccessPort、redirect NEVER、credential/query 零失败输出、external untrusted provenance、Headless 注册与隐私边界。

专项 Fake/loopback 证明 Exa/Parallel 认证不会串线，unknown/missing Content-Type 不会回退 JSON，Permission/Network deny 零 HTTP，允许后 Call ID、durable/final lifecycle exactly-once，默认关闭不注册、显式启用才注册。2026-08-12 返修后 Exa no-key smoke 再次成功；此前真实安装版 `codej --print` 杭州天气 E2E 仍有效，因为无-key 实际请求 path/body/认证均未改变，产生 1 次成功 `web_search`，provider host=`mcp.exa.ai`，最终回答使用当前日期。

## 仍有差距

1. 不支持 Open Page、Find in Page、任意 URL WebFetch、图片或垂类 API；不会抓取引用页面。
2. Hosted MCP 返回自由文本；本项目不声称逐条 URL 结构化验证或域名过滤。外部内容仍可能错误、陈旧或含提示注入。
3. 无跨 redirect 每跳重授权、缓存、共享 retry/cost budget、多 backend 或搜索质量评测；单次 attempt 已有总 wall deadline，但没有跨重试预算（当前也不自动重试）。
4. `NetworkAccessPort` 是应用层授权，不提供 OS 强制 JVM egress、DNS rebinding、代理/TLS pinning 或 native Windows network sandbox。
5. 仅有一次本机 Exa 公网与天气 E2E；没有 Parallel 在线、多地区、多网络或长期 SLA 证据。
6. stdio 审批为保护 query 隐私只展示 tool/effect 和 unavailable preview；没有不泄露 query 的搜索专用摘要。
7. 真实第三方会接收 query；数据保留、索引和服务条款属于 Provider 信任边界，不由本项目控制。
8. 当前日期来自本机 zone runtime metadata；没有可信时间服务或时区歧义交互。

## S15 未完成项

`TOOL-18` 是参考差距补齐，不是独立创新。S15 Stage Exit 仍需要相对 S14 的明确创新假设、A/B Eval、收益/成本/安全阈值、L4 证据与 commit-scoped G6；不得因一个 L2 Tool 标记 Accepted。
