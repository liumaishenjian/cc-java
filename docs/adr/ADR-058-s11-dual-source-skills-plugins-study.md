# ADR-058：S11 Skills + Plugins 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-09
- Stage: S11 Skills + Plugins（G0-G2）
- Feature IDs: `SKILL-01`～`SKILL-07`、`PLUGIN-01`～`PLUGIN-06`、`CTX-14`、`TOOL-16`、`SEC-11`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenAI Codex `rust-v0.147.0`，Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Classification: 授权快照为 `Observed / Inferred / Unknown`；Codex 固定公开源码为 `Documented / Observed`；采纳边界为本项目 `Documented`

## 背景

S09/S10 已建立可信 Hook、MCP Adapter 与统一 Tool Pipeline。S11 要在其上提供可复用工作流和扩展打包，但不能把 Markdown 当成权限、把 Plugin 当成任意 Java Classpath，或把 S14 市场/签名/稳定兼容提前实现。

本轮遵循 ADR-022，在仓库外只读研究 `AUTH-SRC-2026-07-29-A`；同时以官方 OpenAI Codex `rust-v0.147.0` 固定 tag/commit 的本地只读 clone 交叉验证公开机制。两类来源均只用于提炼职责、状态、不变量、失败恢复与验证方法；未复制函数体、Prompt、注释、错误文案、私有名称、文件布局或实现常量。

## 双源研究结论

| 机制结论 | 授权快照 | Codex 0.147.0 | 本项目采纳 |
| --- | --- | --- | --- |
| Skill 先发现轻量 metadata，正文只在明确调用后进入请求 | Observed | Observed | `SkillCatalogSnapshot` 与按需 `SkillContentLoader` 分离 |
| 显式调用与模型选择是两条入口，最终都收敛到同一 Skill 解析和执行边界 | Observed | Observed | `/skill-name` 与模型 Skill Tool 共享 `SkillInvoker` |
| Skill 可以携带资源、工具约束和运行期 Hook，但这些声明不能扩大宿主权限 | Observed | Inferred / Observed | 资源只读且有界；`allowed-tools` 只做交集；Hook 仅 Run scope |
| 已调用 Skill 需要在压缩/恢复后维持足够语义，否则长会话会丢失工作流约束 | Observed | Observed | Session 只持久化 digest/身份/调用事实；Resume 重验内容，Projection 重建有界正文 |
| Plugin 把 Skill、Hook、MCP 等组件映射到命名空间，组件失败需要隔离 | Observed | Observed | Manifest 严格列举组件；`pluginId:component` 稳定命名 |
| 运行 Session 使用启动时插件/Skill 快照；后台更新不应改变正在运行的语义 | Observed | Observed | immutable snapshot；更新只影响新 Session |
| Plugin Tool 最终仍须映射为宿主 Tool，并经过相同权限、审批、Hook 和裁剪 | Inferred | Observed | Host-side `PluginToolProvider`，首个 Adapter 为 MCP-backed |
| 安装与激活应分离，卸载需等待引用归零，不能删除正在使用的字节 | Observed / Inferred | Inferred | staged install + atomic activate；quiescing uninstall |

## Unknown

- `AUTH-SRC-2026-07-29-A` 的准确 Revision、发行版本、许可证、权利人和公开再使用权；
- 两个参考实现的完整 Skill 选择算法、内部 Prompt、排名阈值、持久格式和跨版本兼容；
- Plugin 签名根、可信市场治理、撤销/透明日志、自动更新和恶意依赖隔离的成熟端到端保证；
- Codex 插件公开实现中仍可能演进的远程目录、安装 UX 与跨平台并发卸载语义；
- 参考产品是否对所有资源类型、Skill Hook 和 Session 恢复提供相同原子性保证。

Unknown 不进入本项目常量、格式或测试 Oracle。

## 采纳与有意偏离

### 采纳

1. Metadata-first catalog、显式/模型双入口、正文/资源懒加载与有界 Projection。
2. Skill `allowed-tools` 只能收窄当前真实 Tool set；Skill 不能自动 Allow、创建 Session Grant 或改变 Hard Denial。
3. Skill Hook 复用 S09 `HookCoordinator`；S11 禁止 nested/reentrant Skill，单 Run 可稳定激活多个不同 Skill但每项至多一次。Hook/Tool Scope 只在正文成功投影后启用并持续到当前 Run 唯一终态；无活动 Run 的 Resume 不恢复。
4. Plugin 使用严格 manifest、命名空间、不可变启动快照、内容 fingerprint 与显式激活。
5. Plugin Tool 由宿主 SPI 描述，首版只允许项目内置的 MCP-backed Provider Adapter；所有 Tool 进入统一 Pipeline。
6. 安装采用隔离 staging、完整验证、同文件系统原子发布；卸载先 quiesce，拒绝新 Session，再在引用归零后删除。

### 有意偏离与延期

- 不兼容参考产品私有 Skill/Plugin schema、目录、命名、错误文案或市场协议。
- **S11 不加载任意 JAR、Class、ServiceLoader、反射入口、native library、脚本 Tool Provider 或插件自带 Java 字节码。** `PLUGIN-03` 的 L2 指宿主实现并注册受限 Provider SPI/Adapter，不是第三方代码执行。
- `PLUGIN-04` 在 S11 只达到 L1：本地 staged install/activate/quiesce/uninstall 学习骨架；S14 才达到 L2 的恢复、迁移和跨平台管理。
- `PLUGIN-05/06` 与 `SEC-11` 保持 L0；签名/市场/供应链隔离分别留给 S13/S14/S15。
- S12 Sub-Agent/forked Skill、S13 OS Sandbox、S14 稳定协议/发行/迁移均不进入 S11。
- `MCP-08` 不随 Plugin MCP-backed Adapter 自动升级；S11 不实现通用 MCP Tool Search/Lazy Schema。

## 安全与停止条件

- Skill/Plugin/资源/manifest 均是不可信输入；每次读取实施固定 root、realpath、普通文件、NOFOLLOW、严格 UTF-8、大小/数量和内容 digest 校验。
- Plugin fingerprint 是内容身份和 Trust 输入，不是签名，也不是 Sandbox。
- Plugin 不能改变 S05 权限优先级、S06 Recovery Gate、S07 Canonical/Projection 边界或 S09/S10 的安全契约。
- 授权范围撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要复制参考字节时立即停止。

## 可证伪验证方向

ADR-059/060 和 S11 证据计划必须证明：metadata-only 启动不读正文；显式/模型调用一致；`allowed-tools` 只能缩小；资源逃逸/超限拒绝；Hook 不跨 Run；Resume digest 不匹配 Fail Closed；插件命名冲突拒绝；快照在磁盘更新后不漂移；Provider Tool 不绕过 Pipeline；安装失败不激活；quiescing 卸载不删除在用字节；任意 JAR/类声明拒绝。

本 ADR 只通过 G0 与参考结论采纳边界，不提升 Capability Level。
