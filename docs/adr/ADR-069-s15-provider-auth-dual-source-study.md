# ADR-069：S15 Provider/Auth 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-13
- Stage: S15 Independent Innovation（仅 G0-G2 设计输入）
- Feature ID: `MODEL-13`
- Current → Target: `L0 → L2`（本 ADR 不提升等级）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: Codex `0.147.0`，local commit `be6e8eac029b183056b7e4402879f15d2c85f61b`，Apache-2.0
- Official Documentation Inputs: OpenCode Providers/CLI、OpenClaw Gateway Authentication（均由维护者于 2026-08-13 核验并提供，不在本轮重新联网）
- Classification: 官方文档为 `Documented`；本地源码机制为 `Observed / Inferred / Unknown`；本项目采纳为独立 `Documented` 决策

## 1. 问题与来源边界

现有 cc-java 已有一个真实 OpenAI-compatible Adapter、Anthropic protocol mock、`ModelGateway`、
`ProviderRouter` 和仓库内 Git ignored `config/provider.local.properties`，但没有用户级 Provider
目录、多个 Credential Profile、认证状态、登录/登出生命周期或安全的 TUI/CLI 共用入口。
`MODEL-08` 只证明启动时已配置的单一模型切换；`CFG-07` 是 Managed Policy，二者都不能代表
Provider/Auth。因此新增独立 `MODEL-13`，不冒用既有 Feature ID。

本轮只读研究：

| 来源 | 身份与权利边界 | 聚焦范围 |
| --- | --- | --- |
| Claude 本地授权快照 | `AUTH-SRC-2026-07-29-A`；Revision/License/再发布权仍 `Unknown` | auth source、secure-storage/fallback、login/logout、cache invalidation、model selection |
| Codex 本地公开快照 | `0.147.0`；commit `be6e8eac...`；Apache-2.0 | CLI login/logout、file/keyring store、auth manager、provider config、model selection |
| OpenCode 官方文档 | <https://opencode.ai/docs/providers>、<https://opencode.ai/docs/cli/> | `/connect`、用户级 auth、Provider config 与 credential 分离、`/models`、headless auth |
| OpenClaw 官方文档 | <https://docs.openclaw.ai/gateway/authentication> | profile/status/probe/session pin、SecretRef、logout 与 revoke 区分 |

没有复制或逐行翻译参考函数体、注释、错误文案、私有类型名、文件布局或常量；没有把参考
字节、Fixture 或 Golden Output 放入仓库。Codex 公开源码的许可证不会被解释为要求本项目采用
其表达；本项目仍使用独立 Java 契约。OpenCode/OpenClaw 页面内容由维护者作为已核验
`Documented` 输入提供，本轮不声称保存了网页归档或内容指纹。

## 2. 官方公开行为：Documented

### 2.1 OpenCode

1. `/connect` 把 Provider API key 写入用户级认证存储；Provider 的 base URL、模型等配置与
   credential 分离。
2. custom Provider 可以配置兼容协议、URL 与模型；`/models` 负责模型选择。
3. `auth login`、`auth list`、`auth logout` 为无 TUI 环境提供对等入口。

### 2.2 OpenClaw

1. API key 与 OAuth 都是认证方法；Credential Profile 可以同一 Provider 多份并支持会话 pin。
2. endpoint/base URL、API、models、headers、timeout 属于 Provider 配置，不属于 credential。
3. SecretRef 可指向 env/file/exec/store；list/status 是本地观察，网络验证必须由显式有界 probe
   触发。
4. 删除本地认证不等同于 Provider 侧 revoke；删除时应停止同进程正在使用该 credential 的运行，
   并以 auth-revoked 语义结束。
5. API key 对常驻主机通常比交互 OAuth 更可预测。

本项目只采纳这些可独立表达的职责，不照搬 OpenClaw Gateway、per-agent SQLite、所有
SecretRef backend、silent profile rotation/failover 或 OAuth 实现。

## 3. 本地受控机制：Observed

### 3.1 Claude 授权快照

受控只读观察得到：

- 认证值解析会同时报告来源；环境、外部 helper、持久认证与特定运行模式有明确选择顺序。
- 登录获取 credential、持久化、账户 metadata 与运行态 cache 是不同职责；logout 同时删除
  持久材料并清理认证相关 cache，而不是只改一个 UI 标志。
- secure-storage 是可替换边界；部分平台可用 OS credential facility，其他平台或失败路径可能
  退回普通文件。名称中的 “secure” 不足以证明所有 backend 都是 OS vault。
- 模型选择与 credential 存储分离；认证改变会使依赖认证的能力/cache 失效。
- OAuth 存在刷新、并发抑制与 401 后恢复等状态，但准确商业账号策略、内部消息和发行身份不进入
  本项目。

### 3.2 Codex 0.147.0

公开本地快照观察得到：

- headless API key 登录从 stdin 接收秘密，避免把 key 放进 argv；登录方法受显式 policy 约束。
- credential store 具有 file、keyring 与自动选择等 backend；认证数据和 Model Provider 的 URL、
  protocol、environment key 等配置分离。
- store 的 load/save/delete、认证 snapshot/manager、token refresh/reload 与 CLI presentation 是不同
  边界；持久状态变化需要使运行态观察者看到新 snapshot。
- logout 可以包含远端 revoke，但本地删除与远端操作仍是可区分步骤；文件 backend 的权限与
  keyring backend 的保证不同。
- Provider/模型配置可以独立于当前认证存在，认证缺失不应让本地模型目录消失。

## 4. Inferred 与 Unknown

### 4.1 Inferred

1. cc-java 若要在 logout 与模型请求并发时证明“不再使用已删除 credential”，必须引入
   **credential lease/generation + active-run registry**；仅删除文件或清一个 map 存在检查后使用竞态。
2. TUI 与 CLI 应共享 application service，但秘密输入必须通过专用 edge channel 进入短生命周期
   secret holder，不能伪装成 Domain command、Agent event 或 Session record。
3. Provider Definition、Credential Profile、Session Selection 和 live Model Gateway 是四个不同
   生命周期；把 API key 放进 `ProviderDefinition` 会使 list/model UX、迁移和脱敏不可证明。
4. 普通文件 backend 即使具有 owner-only ACL，也只能称“权限受限的用户级文件存储”，不能称
   OS vault、keychain 或硬件保护。
5. 多 profile 不应自动形成 fallback 列表。每个 Run 必须解析为一个确定 profile；缺失、revoke 或
   probe 失败应显式失败或由用户重新选择。

### 4.2 Unknown

- `AUTH-SRC-2026-07-29-A` 的准确版本、许可证、权利人和完整跨平台存储保证。
- 参考产品在崩溃、NFS/漫游用户目录、Windows ACL 继承异常、不可取消 SDK 请求下的完整原子性。
- OpenCode/OpenClaw 文档在 2026-08-13 后的变化、其 hosted service 数据保留与内部 profile
  选择算法。
- 各 Provider 对 `/models`、认证 probe、rate limit、OAuth client registration 和 revoke 的一致性。
- Java SDK 内部复制 API key 后能否物理清零所有字符串；本项目只能清除自己拥有的 buffer、引用和
  cache，并在 logout 前取消/关闭使用它的运行资源。

## 5. cc-java 采纳

1. 产品为本地直连 BYOK，不建设官方模型中转 Gateway。
2. 新增非秘密 `ProviderDefinition` 与多份 `CredentialProfile`；API key 只由 `STORE` 或 `ENV`
   SecretRef 解析。
3. 提供 OpenCode 风格 `/connect`、`/auth list`、`/auth logout`、`/models`，并提供 headless
   `auth login/list/status/probe/logout` 与 `models list/use`。
4. OpenAI-compatible custom URL/model、Anthropic 官方直连和 OpenRouter 官方直连进入 L2 目标。
5. list/status 不联网；probe 必须显式、单 profile、单 endpoint、共享 deadline、可取消且有界。
6. profile precedence 固定为：显式 profile → Provider default profile → env ephemeral profile →
   legacy properties ephemeral profile。不得 silent rotation/failover。
7. logout 先阻止新 lease、取消同进程 active runs、清 application-owned gateway/secret cache，再删除
   本地 profile secret；明确提示“不等于 Provider revoke”。无法收敛 active run 时不得谎报成功。
8. 旧 `config/provider.local.properties` 保持可读；迁移必须显式、非破坏、可验证，绝不自动删除旧值。
9. OAuth 只保留由 Provider 官方授权、固定 issuer/client/redirect 的未来 extension gate；本切片不
   接受任意 OAuth URL/client，不实现浏览器/device flow。
10. secret 不进入 Domain、Canonical/Session、log、telemetry、Agent event、普通 error、argv、
    evidence、`toString()` 或 Provider Definition。

## 6. 有意偏离与否决方案

| 方案 | 决策 | 原因 |
| --- | --- | --- |
| 官方模型中转 Gateway | 否决 | 改变本地直连 BYOK 的信任与数据路径 |
| per-agent SQLite auth database | 否决 | S15 L2 不需要数据库；profile 是用户级，不绑定单 Agent |
| 把 API key 写回 provider properties/definition | 否决 | 配置与秘密耦合，难以安全 list、选择与迁移 |
| 普通文件命名为 OS vault | 否决 | ACL 文件不等于 Keychain/Credential Manager/libsecret |
| file/exec SecretRef | 延期 | 路径、进程、输出与 refresh 扩大攻击面；L2 只做 env/store |
| silent rotation/failover | 否决 | 隐藏身份、费用与数据目的地变化；与确定性 Run 不符 |
| generic OAuth | 否决 | 任意 issuer/client/redirect 无法证明合法性和供应链边界 |
| login 自动 probe | 否决 | 保存本地 credential 不应隐式联网或产生费用 |
| logout 总是远端 revoke | 否决 | API key 通常无通用 revoke；本地删除和 Provider revoke 必须分开陈述 |
| TUI 自建 auth runtime | 否决 | 会绕过 Java application service、active-run registry 与唯一真实 ModelGateway 链路 |

## 7. 可证伪要求与停止条件

ADR-070 的实现必须用独立 Fake/loopback 证明：

- Provider config 与 secret 字节分离；definition/list/model/status 的输出零 secret；
- stdin/masked input 或 env reference，不存在 secret argv；
- profile precedence、同 Provider 多 profile、session pin 与绝无 silent fallback；
- list/status 零网络，probe 单次有界且可取消；
- logout 与新 Run/active Run 竞态下，新 lease 为零、旧 Run 被取消、cache/store 被清、终态唯一；
- symlink/Junction、ACL/mode、oversize、corrupt JSON、lock contention、atomic-move/crash points fail closed；
- legacy 显式迁移成功/冲突/崩溃均不修改旧文件；
- OpenAI-compatible、Anthropic、OpenRouter 都通过 protocol loopback，至少两个用户明确 opt-in 的真实
  BYOK E2E 才能把 `MODEL-13` 提升到 L2。

若授权撤回、快照身份变化、实现需要复制参考表达、无法避免 secret 进入既有 durable/event 协议，
或产品改为官方中转 Gateway，应停止并新建 ADR。ADR-070 固定独立实现契约；本 ADR 自身不证明
G3-G6，不提升 `MODEL-13` 或 S15 Gate。
