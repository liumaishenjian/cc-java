# ADR-057：S10 MCP Java Adapter 与统一执行契约

- Status: Accepted
- Date: 2026-08-09
- Stage: S10 MCP
- Features: `MCP-01`～`MCP-11`
- Depends on: ADR-039、ADR-045、ADR-054、ADR-056

## Decision

新增边缘模块 `cc-java-mcp`，依赖官方 MCP Java SDK 2.0.0，但只向 Core 暴露项目自己的
`AgentTool`。Domain/Core 不依赖 SDK、Reactor、Transport 或配置文件。

```text
fixed extension config + explicit project trust
  → McpClientManager (bounded parallel initialize / failure isolation)
  → OfficialMcpClientFactory (STDIO or Streamable HTTP)
  → prefixed filtered AgentTool
  → ToolRegistry → PermissionPolicy → Approval → ToolExecutionPipeline
```

## Transport 与认证

- STDIO executable 必须是绝对路径，argv 有数量/单项/总长上限。官方 SDK 默认继承父进程环境，
  因此项目子类在创建进程时先清空环境，再只注入配置点名且在父环境存在的变量。
- Streamable HTTP 只接受 HTTPS 或 loopback HTTP，不跟随重定向；Bearer 配置只保存环境变量名，
  值在建连时读取，不进入值对象、状态或错误摘要。
- 未信任 project Server 不创建 Transport。User 配置属于用户主动管理的私有来源；project 配置
  必须由 CLI 精确指纹批准。

## Tool 与故障语义

- 发布名称为 `mcp__<server>__<tool>`；Server/Tool 标识有界且冲突时整体安全失败。
- denylist 先于 allowlist；发现分页和总量有硬上限；单 Server 初始化失败只产生脱敏 FAILED 状态。
- MCP Tool 标为 `ToolSource.MCP` 与 `NETWORK_OR_REMOTE`。默认策略只允许这一组合进入 ASK；其他来源
  的 Network 仍 Hard Denial，System/Destructive 永远拒绝。
- Tool Call 失败后重建 Client、重新 initialize 并只重试一次；失败输出经过 AgentTool 与 Pipeline
  双层字符/字节上限。文本可投影，二进制只生成类型标记，不复制数据。

## Context primitive 与差距

Resource/Prompt 当前只显式发现有界元数据，单 primitive 失败隔离，不自动读资源或把 Prompt 注入模型，
所以 `MCP-09` 为 L1。Bearer 环境认证不等于 OAuth，`MCP-10` 为 L1。没有 Tool Search/Lazy Schema，
`MCP-08` 保持 L0；这些差距不阻塞本 Stage 的 Tool 主链退出。

## Verification

使用独立 Java Fixture 和 JDK loopback Server 验证两个真实 Transport；使用 Fake 验证多 Server、过滤、
信任、冲突、断线恢复；Headless E2E 验证 MCP Tool 由模型提出后必须经过 Allow Once 与同一个 Pipeline，
再以匹配 Call ID 的 Tool Result 进入下一模型回合。
