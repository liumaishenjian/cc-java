# ADR-060：S11 Plugin Manifest、宿主 SPI 与生命周期契约

- Status: Accepted
- Date: 2026-08-09
- Stage: S11 Skills + Plugins（G1-G2）
- Feature IDs: `PLUGIN-01`～`PLUGIN-06`、`SEC-11`、`MCP-08`
- Current → S11 Exit Target: `PLUGIN-01..03 L0 → L2`；`PLUGIN-04 L0 → L1`（S14 → L2）；`PLUGIN-05/06`、`SEC-11` 保持 L0；`MCP-08` 保持 L0
- Depends on: ADR-039、ADR-054、ADR-057～059

## 决策

S11 Plugin 只是一份经过验证、带命名空间和不可变内容身份的组件包。它可以打包 Skill、S09 Hook 配置和 S10 MCP Server 配置，并通过宿主内置 Provider SPI 映射 Tool；它不获得任意 Java 代码执行能力。

```text
local candidate directory
  → isolated staging copy
  → strict manifest + tree validation
  → canonical tree fingerprint
  → explicit trust/activation
  → immutable PluginSnapshot for new Session
  → Skills / Hooks / MCP-backed Host Tool Providers
  → unified ToolExecutionPipeline

uninstall request → QUIESCING → reject new snapshots → reference count = 0 → delete
```

## 1. Manifest 与命名空间

- manifest 固定为 plugin root 下项目自有 `plugin.json` v1；严格拒绝重复/未知字段。
- 必需字段：`schemaVersion=1`、`id`、`version`、`components`；可选 `description`、`requiresHost`。
- `id` 和组件名使用小写 ASCII kebab-case，1～64 字符；`version` 最多 64 字符；组件总数最多 128。
- 组件类型只允许 `skills`、`hooks`、`mcpServers`、`toolProviders`。路径必须是 plugin-root-relative、普通文件/目录且不含链接。
- 全局发布名固定为 `plugin__<plugin-id>__<kind>__<component-name>`；任何与内置、MCP、其他插件或同包组件冲突都使该组件隔离，Tool 冲突使整个 Provider 不发布。
- Plugin metadata 和 component descriptors 总投影最多 64 KiB；单 plugin tree 最多 1,024 个普通文件、32 MiB，单文件上限按组件契约另行收窄。

## 2. Immutable snapshot 与 fingerprint trust

- staging 完成后，按规范相对路径、文件类型、长度和 SHA-256 构造 canonical tree digest；不跟随 Symlink/Junction，不包含 mtime/绝对路径。
- `PluginFingerprint(pluginId, version, treeDigest, manifestDigest)` 必须与用户私有 trust store 中的精确批准值一致才能激活。项目来源默认不可信；内容变化使批准失效。
- 指纹只能证明“当前字节与批准字节相同”，不能证明作者身份、签名、无恶意或 OS 隔离；`PLUGIN-05`/`SEC-11` 因此保持 L0。
- 每个 Session 在启动时取得 `PluginSnapshotSet`；背景安装/更新只改变 disk registry，新版本仅供新 Session 使用。当前 Session 不热切换组件。

## 3. Host-side Tool Provider SPI

项目定义受限宿主契约：

```text
PluginToolProviderDescriptor(providerType, componentId, referencedComponentNames, configDigest)
PluginToolProviderFactory.create(descriptor, trustedSnapshot) -> PluginToolContribution
PluginToolContribution(tools, lease, close)
```

- `PluginToolProviderFactory` 是宿主预注册、无每次创建资源所有权的 factory；`close/lease` 属于其返回的 `PluginToolContribution`，不能错误地放在共享 factory 上。Contribution 持有本次 immutable Plugin snapshot lease、创建的 `AgentTool` 集合和底层 MCP client/transport 的关闭所有权。
- Factory 只能由 cc-java 生产代码预注册；manifest 的 `providerType` 只能选择已注册 ID。manifest **不得直接构造 `ToolDefinition`、`AgentTool` 或可信 `ToolSource`**，也不能提供类名、JAR、module path、native library 或可执行脚本。
- S11 唯一生产 Provider 类型为 `mcp-backed`：`toolProviders` 组件只能通过稳定名称引用同一已验证 manifest 中的一个或多个 named `mcpServers` 组件；解析时要求引用存在、类型正确、无重复/循环/跨 Plugin 指向。Factory 只能消费这些已验证 descriptor，不能读取 manifest 中未声明的 Server。
- MCP-backed Contribution 先取得 Plugin snapshot lease，再按 manifest 稳定顺序创建 MCP clients、初始化并映射为 `ToolSource.PLUGIN` 且保留远端来源摘要的 `AgentTool`。关闭顺序固定为：从 Registry 停止发布/等待调用 lease → 关闭 AgentTool contribution → 逆序关闭 MCP client/transport → 释放 Plugin snapshot lease；异常路径也按已成功创建资源的逆序关闭。
- Provider Tool 必须注册到同一 `ToolRegistry`，经过参数校验、Permission、Approval、S09 Hooks、执行、裁剪、脱敏、durable Tool 状态和结果转换。Provider 无旁路 execute API。
- Plugin MCP config 受 ADR-057 的 absolute executable/structured argv、环境 allowlist、HTTPS/loopback、Bearer env-name、连接恢复和输出上限约束；Plugin trust 不替代 MCP runtime trust。
- G3 必须受控修改现有 `DefaultHardDenialPolicy`：`NETWORK_OR_REMOTE` 只有可信 `ToolDefinition` 的 `ToolSource.MCP` 或 `ToolSource.PLUGIN` 可以越过该 Effect 的通用 Hard Denial，进入后续 S05 规则评估并默认 `ASK`；其他来源继续 Hard Deny，`SYSTEM_OR_DESTRUCTIVE` 永远 Hard Deny。该例外不能按 manifest 文本或模型参数判断，只能依据 Registry 中由宿主 Adapter/factory 构造的可信 Definition。
- `ALLOW_SESSION` 对 Plugin Tool 必须绑定 `ToolSource.PLUGIN`、完整 qualified Tool name 和既有规范化 selector；同名 Tool 改来源、改 Plugin namespace、改 Server/Tool 或新 Session 均不命中。每次调用仍重新执行 Permission/Approval，不能把一次 ASK/Grant 缓存为可见 Tool 集合。
- 该 Adapter 不实现 Tool Search/Lazy Schema，`MCP-08` 保持 L0。

## 4. Staged install 与 quiescing uninstall

### Install

1. 只接受显式本地**目录**；ZIP/JAR/TAR 等 archive 输入一律拒绝，不下载、不解包，因此不设计或宣称 archive bomb 检测。S11 不联网、不解析 marketplace、不运行 package manager/lifecycle script。
2. 把候选逐个普通文件复制到用户私有 root 的随机 `CREATE_NEW` staging；拒绝链接、特殊文件和上限违规。
3. 严格解析 manifest、所有组件及跨引用；计算 fingerprint，并等待显式 trust/activate。
4. 发布顺序固定为：完整写入 staging → 对每个文件 flush/`force(true)` → 在支持时 flush staging 目录 → 同文件系统原子 rename 到 content-addressed immutable 目录 → flush 发布目录的父目录 → 原子写并 flush registry staged file → 原子替换 registry → flush registry 父目录。任何必需的原子 rename/replace 或 flush 语义不支持时 Fail Closed，不回退复制、非原子覆盖或“先写 active registry 后补文件”。任一步失败不出现 active 新记录，已激活旧版本保持不变；已发布但尚未登记的目录只作为可清理 orphan，不能被 Session 自动发现。

### Uninstall

1. 将 registry 状态从 `ACTIVE` CAS 为 `QUIESCING`；新 Session snapshot 不得获得它。
2. 已持有 snapshot 的 Session 继续使用同一不可变目录；等待引用计数归零，不强杀 Run/Tool/Hook/MCP。
3. 归零后原子移出 active namespace，再做有界、NOFOLLOW 删除；失败保留 `TOMBSTONED/QUARANTINED` 可诊断状态，不把残留重新激活。
4. S11 不提供跨崩溃自动恢复、跨平台安装器或稳定 migration，因此 `PLUGIN-04` 仅 L1；S14 才完成 L2。

## 5. 明确禁止任意 JAR

S11 及后续默认均不执行插件携带的任意 JVM 字节码。manifest 出现以下任一内容必须 Fail Closed：

- `.jar`、`.class`、JPMS module、Maven/Gradle 坐标；
- Java 类名、`ServiceLoader` provider、反射 factory；
- JNI/native library、agent、classpath/module-path 修改；
- 安装脚本或用于提供 Tool 的任意 executable。

如未来确有 Java 扩展需求，必须在 S13 Sandbox 与 S14 签名/兼容基线后另建 ADR，不得扩张本 SPI 语义。

## 6. 独立 Java 契约与 Javadoc

```text
PluginManifest / PluginComponentDescriptor / PluginNamespace
PluginFingerprint / PluginSnapshot / PluginSnapshotSet
PluginRegistry / PluginInstaller / PluginUninstaller / PluginLease
PluginToolProviderDescriptor / PluginToolProviderFactory / PluginToolContribution
```

核心公共类型必须使用中文 Javadoc 解释不可变快照、引用所有权、Trust 不等于签名、Provider 不得旁路 Pipeline、取消/关闭与失败状态。Domain/Core 不依赖 Path、JSON、MCP SDK、Spring、URLClassLoader 或 ServiceLoader。

## 7. 可证伪验收

至少覆盖严格 manifest、1,024 文件/32 MiB ceiling、目录输入与 archive 输入一律拒绝、路径/link/special-file、namespace 冲突、tree digest 顺序稳定与任一 byte 变化失信、Session snapshot 不漂移、未信任不创建 Provider、manifest 伪造 `ToolSource.PLUGIN`/ToolDefinition/未声明 Server 引用拒绝、MCP-backed named server 解析与逆序关闭、Contribution lease/close 唯一所有权、`NETWORK_OR_REMOTE + ToolSource.PLUGIN` 默认进入 ASK 而非永久 Hard Deny、其他来源仍 Hard Deny、Plugin Session Grant 绑定来源+完整 qualified name、Call-ID/Pipeline/输出 ceiling、任意 JAR/类/native/脚本声明拒绝、缺少原子发布支持 Fail Closed、flush/rename/registry 各故障点不激活、旧版保留、quiesce 拒绝新租约、活动 Run 完成前不删除、归零删除和失败 tombstone。

## 8. 延期

签名、证书链、透明日志、SBOM、漏洞扫描、Marketplace、联网安装、自动更新、撤销、稳定 schema migration、跨平台恢复和 OS Sandbox 分别属于 `PLUGIN-05/06`、`SEC-11`、S13-S15；S11 不以 fingerprint/staging 冒充这些能力。
