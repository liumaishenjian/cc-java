# S11 Skills + Plugins G0-G2 启动证据与 G3-G6 计划

## 元数据

```text
Stage: S11 Skills + Plugins
Status: In Progress（仅 G0-G2）
Release / Commit: N/A - design freeze; implementation not started
Reference Behavior Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Public Source Snapshot: OpenAI Codex rust-v0.147.0 / be6e8eac029b183056b7e4402879f15d2c85f61b
Feature IDs: SKILL-01..07, PLUGIN-01..06, CTX-14, TOOL-16, SEC-11, MCP-08
Owner: 项目维护者
Date: 2026-08-09
```

## Gate 状态

| Gate | 状态 | 证据/退出条件 |
| --- | --- | --- |
| G0 | PASSED | ADR-058 双源来源、固定 Codex tag/commit、授权快照指纹、Unknown 与非复制边界 |
| G1 | PASSED | ADR-059/060 冻结 Feature、等级、数值上限、延期与最小可证伪行为 |
| G2 | PASSED | 独立 Skill/Plugin 契约、模块所有权、权限/恢复/生命周期与被否决方案 |
| G3 | OPEN | 生产与测试代码尚未开始 |
| G4 | OPEN | 下述测试/Eval 尚未运行 |
| G5 | OPEN | Demo 只有计划，无实际结果 |
| G6 | OPEN | Capability Level 未提升；Stage Exit 未接受 |

## G0：来源与授权

### AUTH-SRC-2026-07-29-A

仓库外只读路径为 `G:\AI Cloud\claude-code-main`；登记身份为 1,902 文件、30,382,832 bytes、Tree SHA-256 `5f820b7a05b704a5e49cfd7747189af265def28a73227889c3ff028aeab79301`。准确 Revision、版本、License、权利人和再发布权均为 `Unknown`。本轮只记录 metadata-first、调用、资源/Hook、Session 恢复、Plugin manifest/namespace/snapshot/install 等抽象机制。

### OpenAI Codex rust-v0.147.0

本地只读 clone：`target/research/codex-0.147.0`；Tag `rust-v0.147.0`；Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`。研究覆盖公开 Skill metadata/injection/config、Plugin namespace/manifest、Plugin Skill snapshot 与 host-side extension seams；代码只作为公开机制交叉验证，不复制表达、不作为 Golden Output。

### 结论分类

- `Observed`：两源均呈现 metadata 与正文加载分离、显式/隐式调用、Plugin namespace、Session/turn snapshot 或内容身份职责。
- `Inferred`：allowed-tools 必须取交集、Skill Hook 仅 Run scope、quiescing uninstall、host-side SPI 是 cc-java 安全不变量推导。
- `Unknown`：内部选择排名、Prompt、全部更新/签名/市场/迁移/并发卸载保证。
- `Documented`：ADR-058～060 的 Java 契约和数值上限是 cc-java 独立设计。

## G1：范围与退出目标

| Feature | Current | S11 Exit | 退出证据 |
| --- | ---: | ---: | --- |
| `SKILL-01..07` | L0 | L2 | 真实小型 Workspace 中 metadata-first、双入口、资源、Hook、恢复均可用且有负例 |
| `CTX-14` | L0 | L2 | 启动不 materialize 正文；按调用投影，Context/Canonical 可证伪 |
| `PLUGIN-01..03` | L0 | L2 | 严格 manifest/namespace/snapshot；MCP-backed Provider Tool 经过统一 Pipeline |
| `PLUGIN-04` | L0 | L1 | 本地 staged install/quiescing uninstall 骨架；S14 再到 L2 |
| `PLUGIN-05/06` | L0 | L0 | 签名/市场延期 |
| `SEC-11` | L0 | L0 | fingerprint 不冒充供应链安全；S13/S14 |
| `TOOL-16` | L0 | L0 | catalog 不等于大 Tool set search/lazy schema |
| `MCP-08` | L0 | L0 | MCP-backed Adapter 不实现 MCP Lazy Tool |

本 Stage 不实现任意 JAR、Sub-Agent、远程市场、签名、OS Sandbox 或稳定迁移。

## 数值上限冻结

| 对象 | 上限 |
| --- | ---: |
| Skill 名称 / Plugin ID / 组件名 | 64 ASCII 字符 |
| 单 root / 合计 Skill | 128 / 256 |
| 单 `SKILL.md` | 128 KiB / 4,000 行 |
| description | 512 code point，单行 |
| allowed-tools / resources / Skill hooks | 32 / 32 / 16 |
| catalog metadata Projection | 64 KiB 或估算 16,384 token |
| 单资源 / 单调用资源合计 | 256 KiB / 1 MiB |
| 单 Plugin 组件 / 普通文件 / tree | 128 / 1,024 / 32 MiB |
| Plugin metadata+descriptor Projection | 64 KiB |

任何实现只能进一步收窄，放宽必须新 ADR 和攻击回归。

## G3 实现计划

1. Domain/Core：catalog/snapshot/invocation/projection/resource/hook lease/recovery 值对象与 Port，中文 Javadoc。
2. Local/CLI Adapter：严格 frontmatter、root guard、metadata scanner、lazy content/resource snapshot、Session digest。
3. Skill Runtime：Slash 与模型 Tool 双入口；`effectiveVisibleTools=runtimeVisibleTools∩skillAllowedTools`，每个真实调用仍逐次执行 Permission/Approval/Pipeline；禁止 nested/reentrant，单 Run 可稳定激活多个不同 Skill且每项至多一次；正文成功投影后 Scope/Hook 持续到 Run 唯一终态，无活动 Run 的 Resume 不恢复。
4. Plugin Runtime：严格 manifest、canonical tree fingerprint、registry/snapshot/lease、仅目录的 staged install 与 quiescing uninstall；按 flush/atomic rename/registry 顺序 Fail Closed。
5. Provider：仅宿主内置 `mcp-backed` factory，返回持有 Tool/底层资源/snapshot lease 与 close 的 Contribution；仅引用已验证 named MCP Server；拒绝 JAR/Class/ServiceLoader/native/script Tool Provider。
6. Permission：受控修改 `DefaultHardDenialPolicy`，仅可信 `ToolSource.MCP/PLUGIN + NETWORK_OR_REMOTE` 进入后续 ASK；manifest 不能构造 ToolDefinition/ToolSource，Plugin Session Grant 绑定来源、完整 qualified name 与 selector。
7. Production composition：Print/stdio/TUI 共用相同 Skill/Plugin snapshot 和 Pipeline，不新增旁路。

## G4 可证伪测试与指标

### Skill

- metadata scan 的正文读取字节数必须为 `0`；调用后只读取目标 Skill/声明资源。
- 256 项边界内稳定顺序；第 257 项、冲突名、重复/未知字段、非法 UTF-8 明确隔离。
- explicit/model 两入口生成相同 invocation identity；explicit-only/model-only 反向调用失败。
- nested/reentrant 全部拒绝；同 Run 多个不同 Skill 按稳定顺序激活且每个至多一次；模型 activate Tool 成功前 Scope/Hook/`skill.invoked` 均为 `0`。
- 对任意输入，`effectiveVisibleTools` 必为 runtime 与 Skill 两集合交集；新增 Tool 数为 `0`。每次真实调用重新执行 S05 Permission/Approval；规则/Grant 变化立即影响下一调用，未审批副作用次数为 `0`。
- 资源 traversal、绝对路径、Symlink/Junction、特殊文件、超 256 KiB/1 MiB、恶意“提权”内容均不越权。
- Hook 从正文成功投影持续到 Run 唯一终态；注册/注销配对，Run 后存活 Hook/Scope 为 `0`；timeout/cancel/fence 不泄漏。
- 活动 Run compaction 后 digest 匹配可重建 Projection 且不重复激活；无活动 Run 的 Resume/Fork 不恢复 Hook/Tool Scope，mismatch 时模型/Tool/Hook 调用数均为 `0`。

### Plugin

- Manifest/tree 1,024 文件、32 MiB 边界；只接受目录，所有 archive 输入、链接、设备、未知组件拒绝，不宣称 archive bomb 检测。
- 文件遍历顺序变化不改变 fingerprint；任一 byte 变化必须改变 fingerprint 并失信。
- Session snapshot 建立后替换磁盘 registry，当前 Session 组件集合与 digest 必须不变。
- 未信任/冲突 Provider 的 create/execute 次数为 `0`；manifest 直接构造 ToolDefinition/ToolSource 或引用未声明/跨 Plugin MCP Server 均拒绝。
- `NETWORK_OR_REMOTE + ToolSource.PLUGIN` 必须进入 ASK 而非被通用 Hard Denial 永久拒绝；BUILTIN/伪造来源继续 Hard Deny。Plugin Session Grant 只命中同一来源、完整 qualified name 与 selector。
- MCP-backed Contribution 持有唯一 lease/close；正常/部分失败按稳定逆序关闭 Tool、MCP client/transport、snapshot lease。Tool 必须产生准确 Call ID、S09 Hook 和 Pipeline ceiling；旁路执行次数为 `0`。
- 任意 `.jar/.class`、类名、ServiceLoader、native 或 Tool script 声明全部 Fail Closed。
- Install 在 copy、逐文件 flush、staging 目录 flush、atomic publish rename、发布父目录 flush、registry staged flush/atomic replace/父目录 flush 各故障点 active 新版本数为 `0`；平台缺少必需原子能力时 Fail Closed，旧版本保持可用。
- QUIESCING 后新 lease 数为 `0`；活动引用 >0 时删除次数为 `0`；归零后删除或进入 tombstone，不重新激活。

### Stage 指标

- Tool/Permission/Hook bypass：`0`；协议孤儿：`0`；未清理 Hook/Plugin lease：`0`；隐私 sentinel 泄漏：`0`。
- metadata-only 相对全正文启动读取字节降低至少 `90%`（在 20 个每个 ≥8 KiB 的独立 Fixture 上）。
- 正常、失败、取消、恢复、安全用例通过率 `100%`；任何 skip 必须按平台原因登记。

## G5 Demo 计划

创建 `docs/demos/S11-skills-plugins.md`，使用临时公开 Fixture 演示：

1. `fix-java` Skill 在 catalog 中只展示 metadata；显式与模型各调用一次；正文/模板按需注入。
2. Skill 声明只允许 read/search/test，模型请求写文件仍由 Permission/Pipeline 拒绝。
3. Skill 资源中的伪指令不能提权；Run-scoped Hook 在终态后不再触发。
4. 安装本地 Plugin staging，批准 fingerprint 后新 Session 获得 Skill/Hook/MCP-backed Tool；当前 Session 快照不热变。
5. MCP-backed Tool 经过 ASK；未批准时远端调用次数为 0。
6. 卸载进入 QUIESCING，活动 Session 结束前文件保留，新 Session 看不到 Plugin，归零后清理。
7. 负例：digest 变化、JAR 声明、链接逃逸、namespace 冲突均拒绝。

G5 必须记录实际命令、环境、Commit、通过数和观察结果；当前尚无执行结果。

## G6 Gap/Exit 计划

创建 `docs/gap-reports/S11.md`，对账矩阵、README、PRD、技术设计、ADR、测试、Demo、Dashboard。只有新实现 Commit 上完整 Reactor/TUI/launcher、专项 Demo、安全审查和 dashboard 三命令通过后，才能提升等级和接受 Stage Exit。

## 当前未决问题

1. S14 `PLUGIN-04` L2 的跨崩溃 registry 修复、迁移与跨平台删除策略尚未设计。
2. `PLUGIN-05/06` 与 `SEC-11` 的签名根、市场治理、SBOM/漏洞扫描和撤销策略仍 Open。
3. S12 forked Skill/Sub-Agent、S13 OS Sandbox、S14 稳定外部协议均延期。
4. `MCP-08` 与通用 `TOOL-16` 仍 L0，需独立规模/质量 Eval 后启动。
