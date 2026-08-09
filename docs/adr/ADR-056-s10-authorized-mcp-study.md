# ADR-056：S10 授权 MCP 机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-09
- Stage: S10 MCP
- Features: `MCP-01`～`MCP-11`
- Sources: `AUTH-SRC-2026-07-29-A`、`REF-03`、MCP 2025-06-18 Specification、
  Spring AI MCP 2.0.0 官方文档与 MCP Java SDK 2.0.0

## Context

MCP 是成熟 Agent Harness 的外部 Tool/Context 边界。按仓库规则，设计前先对授权快照做只读机制
研究，再以公开协议和本项目测试定义独立 Java 契约。授权未知 Revision/License 不进入依赖、Fixture、
Golden Output，也不提供复制或分发权。

## 受控研究结论

| 结论 | 分类 | 本项目采纳 |
| --- | --- | --- |
| 每个 Server 有独立连接状态，单 Server 失败不应抹掉其他 Server | Observed / Documented | 有界并行初始化、稳定配置顺序、失败隔离 |
| 配置合并/信任、Transport、协议 Client 与 Tool Registry 是不同职责 | Observed / Inferred | CLI 配置边缘、独立 `cc-java-mcp` Adapter、Core 不依赖 SDK |
| STDIO 与 HTTP 的认证和生命周期不同 | Documented | 结构化进程参数；HTTPS/loopback HTTP 与环境变量 Bearer |
| Tool 过滤、命名空间和 Permission 必须发生在执行前 | Observed / Inferred | deny-first、Server 前缀、统一 `ToolExecutionPipeline` |
| 会话断开后需要重建 initialize，恢复必须有界 | Observed / Documented | 首次调用失败只重连并重试一次 |
| Tool、Resource、Prompt 是不同协议 primitive | Documented | Tool 生产接入；Resource/Prompt 先提供隔离的元数据目录 |
| OAuth、动态注册和 token cache 有独立安全状态 | Documented | 本 Stage 不伪装成已实现，保留 S13 差距 |

## 采纳边界

只采纳职责、状态、失败恢复和验证方法。项目使用自己的类型名、模块布局、错误收敛、配置 schema、
数值上限与测试文本。官方 MCP Java SDK 2.0.0 仅作为公开协议 Adapter 依赖；授权快照不作为依赖或
测试 Oracle。若授权身份或范围变化，立即停止使用授权材料，本 ADR 的公开协议设计仍可独立验证。

## 可证伪验证

- 真实 STDIO 子进程执行 initialize/listTools/callTool/close；
- 真实 loopback Streamable HTTP 验证 initialize/list/call 与 Bearer Header；
- 多 Server 失败/未信任隔离、过滤、前缀、重复名拒绝；
- 断线只恢复一次；外部 Tool 必须经历默认 ASK 与统一 Pipeline；
- Resource/Prompt 单 primitive 失败隔离；认证缺失和不安全 HTTP 配置安全失败。
