# S10 MCP Demo

状态：MCP Tool 主链 Stage Exit Accepted；不包含 OAuth、Lazy Tool Loading 或 Resource 自动投影。

## 自动 E2E

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl cc-java-mcp,cc-java-cli -am `
  '-Dtest=McpClientManagerTest,OfficialMcpStdioE2ETest,OfficialMcpHttpE2ETest,S10McpHeadlessE2ETest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期 0 failures/errors，且不需要 API Key 或公网。测试分别启动真实 Java STDIO MCP 子进程和 JDK
loopback Streamable HTTP Server。Headless E2E 的观察顺序是：

```text
固定 user 配置 → initialize/discover → mcp__fixture__echo
→ Model Tool Call → Default Permission ASK → Allow Once
→ 同一 ToolExecutionPipeline → 匹配 Call ID 的 Tool Result → 下一模型回合
```

## 配置边界

- STDIO `command` 必须为绝对路径，`args` 为结构化数组，`env` 只列允许继承的环境变量名；
- HTTP `endpoint` 必须为 HTTPS 或 loopback HTTP；`bearerTokenEnv` 只写环境变量名；
- `allowTools` 与 `denyTools` 可过滤 Tool，deny 优先；发布名自动带 `mcp__<server>__` 前缀；
- Project Server 必须先执行 `codej --trust-project-extensions`，否则不创建 Transport。

## 负例

测试证明单 Server 失败不影响其他 Server、相对 executable/不安全 HTTP 被拒绝、未信任 Server
不创建 Client、断线最多重连一次、非 MCP Network Tool 仍被 Hard Denial。
