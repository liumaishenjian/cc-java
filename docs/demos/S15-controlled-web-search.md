# S15 TOOL-18 受控 hosted MCP Web 搜索 Demo

- Date: 2026-08-12
- Status: G5 Verified Working Tree
- Feature: `TOOL-18 L0 → L2`
- Stage: S15 `IN_PROGRESS`，Stage Exit `OPEN`

## 前置与边界

普通 CI 使用 JDK loopback fixture，无网络、无 API key；真实公网只在维护者显式本地启用后运行。生产 Provider endpoint 固定，模型不能提供 endpoint/Header/credential。查询会发送给所选第三方 hosted MCP；返回文本是 external/untrusted，Runtime 不抓取链接。应用层 `NetworkAccessPort` 不是 OS Sandbox。

本机 Git ignored 配置示例：

```properties
web-search.enabled=true
web-search.provider=exa
web-search.api-key=
```

若非交互 Print 需要真实执行，必须另有可信本地 ALLOW rule；默认 ASK 在 Print 中安全拒绝。

## 确定性命令

```powershell
.\mvnw.cmd -pl cc-java-tools-web,cc-java-cli -am `
  '-Dtest=WebSearchToolTest,WebSearchPipelineTest,WebSearchSettingsLoaderTest,S15WebSearchHeadlessE2ETest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

观察点：

1. Definition=`web_search / NETWORK_OR_REMOTE / BUILT_IN`；schema 只有 query/result_limit。
2. Exa wire 为 JSON-RPC 2.0 `tools/call` + `web_search_exa`：无 key 不带认证，有 key 只带精确百分号编码的 `exaApiKey` query；Parallel key 只使用 Bearer。
3. JSON 与 SSE（含 charset 参数）可解析；未知/缺失 Content-Type 严格拒绝；Permission/Network deny 时 HTTP hit=0。
4. 允许后 Call ID 匹配，durable started/completed、Permission final、AfterTool 各一次。
5. 3xx、429、4xx、5xx、unsupported media type、JSON-RPC error、malformed/duplicate/no-result/oversized 均 typed；服务端快速发 200 headers/部分 JSON 后 stall 仍在总 wall deadline 内 `TIMED_OUT`，慢正文取消为 `CANCELLED` 且 fixture 可立即释放。
6. Tool Result/失败/配置字符串包含固定安全分类，不回显 query/credential；成功结果包含 provenance/untrusted/providerHost，且不伪造 rank/url 结构。
7. Headless 默认关闭不注册，显式 provider gate 后才可见并执行。

2026-08-12 deadline 返修 focused 实际结果：22 tests（Web Adapter/Tool 13 + Pipeline 3 + Loader 4 + Headless 2），0 failure/error/skip。首次 deadline focused 因测试 helper 对空 sensitive 数组调用 AssertJ `doesNotContain` 导致 2 个测试 error；修正 helper 后 22/22 通过。此前 URI query 二次编码的 1 个 wire 断言失败历史仍保留在 Evidence。

## 真实 Exa hosted MCP smoke

显式公网 smoke 使用固定 endpoint、固定 JSON-RPC request 和 30 秒墙钟上限；只记录安全摘要，不保存完整 query/result：

```text
host=mcp.exa.ai
responseBytes=1130
jsonrpc=true
content=true
```

## 真实 `codej` 杭州天气 E2E

安装后的同一路径执行：

```powershell
codej --rebuild
codej --print "杭州今天的天气" --timeout 2m
```

首个 rebuild invocation 因 launcher 的 rebuild-only 入口没有 prompt 按契约返回“非交互模式必须提供 --prompt”；随后 `codej --print` 使用已验证构建成功。修复 current-date runtime metadata 后最终可审计摘要：

```text
weather_e2e toolStarted=1
weather_e2e toolCompleted=1
callIdMatched=true
status=SUCCESS
providerHost=mcp.exa.ai
runStop=COMPLETED
finalMentionsCurrentDate=true
```

最终回答使用 2026-08-12，并综合多个公开天气来源；证据不把完整 query、第三方正文或 credential 写入仓库。

## 完整验证

返修最终工作树已执行下列命令，数字以 Evidence 为准：

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -DskipTests -Dmaven.javadoc.failOnWarnings=true javadoc:aggregate
npm --prefix .\cc-java-tui test
pwsh -NoProfile -File .\scripts\TestCodejDevLauncher.ps1
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

## 事实边界

本切片不是 WebFetch，不支持任意 URL、页面正文、redirect follow、缓存、自动重试、OS 强制 JVM egress 或多环境 SLA。真实一次 E2E 证明生产路径可工作，不证明第三方持续可用或所有天气结果正确。`TOOL-18 L2` 不使 S15 Accepted。
