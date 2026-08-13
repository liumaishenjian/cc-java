# S15 MODEL-13 Provider/Auth 提交前 G0-G5 证据

- Date: 2026-08-14
- Stage: S15 Independent Innovation（OPEN）
- Status: 提交前工作树证据；未 commit
- Feature: `MODEL-13 L0 → L1`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`

## 来源分类与独立重实现

ADR-069 综合了官方公开文档（`Documented`）、授权参考快照及固定公开源码快照的受控机制观察（`Observed / Inferred / Unknown`），并记录授权、许可证、未知项和停止条件。ADR-070 将采纳结论冻结为本项目独立 Java/TypeScript 契约。

本实现只提炼职责、状态转换、安全边界、失败恢复与验证方法；未复制或逐行翻译参考函数体、Prompt、注释、错误文案、私有类型名、文件布局或常量，未将参考字节、Fixture 或 Golden Output 放入仓库。

## 实现架构与安全边界

Provider Definition、Credential Profile metadata、secret material、Run selection 与 live Gateway 保持分离。CLI/TUI 共用 `ProviderAuthApplicationService`，所选 Provider route 经 Spring AI 边缘 factory 组成单一 `ModelProviderRoute`，真实 Agent 请求仍只通过既有 `ProviderRouter` / `ModelGateway` 主链。

安全边界包括：

- 每个 Run 确定绑定一个 provider/profile/model/auth generation，不 silent rotation、fallback 或中途切换；
- secret 仅由 `STORE` 或 `ENV` 引用解析，不进入 Domain、Session、Canonical transcript、普通事件、日志、argv 或证据；
- 用户级 restricted file store 实施固定根、链接/reparse 与身份检查、权限校验、锁、generation、同目录原子发布和崩溃恢复；普通文件存储不宣称 OS vault；
- local list/status/models 保持零网络；只有显式 probe 才允许一次、有界、可取消且隐私安全的 Provider 网络验证；
- generation lease、logout fence、active Run 取消与资源 drain 防止删除 credential 后继续获取新 lease；本地 logout 不冒充 Provider 侧 remote revoke；
- Provider/Auth 应用层控制不等同于 Tool Permission 或 OS Sandbox。

## G0-G5

| Gate | 证据 | 状态 |
| --- | --- | --- |
| G0 | ADR-069 已固定公开来源、授权快照、来源分类、许可证/未知项、停止条件和独立重实现边界 | PASSED |
| G1 | `MODEL-13` 的 Provider/Auth、ENV/STORE、多 profile、选择、probe、logout 范围及 L2 在线门槛已固定 | PASSED |
| G2 | ADR-070 已固定模块职责、数据格式、优先级、状态机、竞态、隐私、错误和验收契约 | PASSED |
| G3 | restricted store、application service、CLI/TUI、Provider factories、单 route composition、probe、lease/logout 已生产接入 | PASSED |
| G4 | 完整 clean verify、TUI check、launcher、严格 aggregate Javadoc，以及 fault/security/loopback/race/隐私回归已通过 | PASSED |
| G5 | 离线 Demo 与负例完成；至少两个 distinct Provider 的真实 BYOK text stream、Tool call、cancel、auth-negative 在线 E2E 尚缺 | PARTIAL |
| G6 | 尚未绑定 implementation commit，也未完成 commit-scoped 最终对账 | OPEN |

## 最终验证

| 验证项 | 结果 |
| --- | --- |
| `./mvnw clean verify` | PASS；11/11 modules，1009 tests，30 skips，0 failure，0 error |
| `npm --prefix cc-java-tui run check` | PASS；11 files，147 tests |
| Dev launcher | PASS；60 assertions |
| strict aggregate Javadoc | PASS；0 warning |

此前临时安装形态 fake ENV E2E 全部 exit 0，只验证本地 metadata、list/status/models 与 logout；未执行 probe 或任何模型网络请求。该 E2E 不构成真实 Provider 在线证据。

本轮未启动 Docker，未读取真实本地 secret 配置，也未使用、记录或输出真实 key、endpoint、用户名或旧会话内容。

## 等级与 Stage 边界

`MODEL-13` 仅提升至 **L1**。双 Provider 真实在线证据缺失，因此不得提升至 L2，也不得把离线 Demo、loopback 或 fake ENV E2E 表述为在线 BYOK 证据。

同一工作树中的 `TOOL-18` 保持 **L2**，本证据不改变其等级。S15 Stage Exit 继续 **OPEN**；G6 留待 implementation commit-scoped 收尾。
