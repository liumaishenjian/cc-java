# S11 Skills + Plugins G0-G6 Commit-scoped 验收证据

## 元数据

```text
Stage: S11 Skills + Plugins
Status: ACCEPTED（G0-G6 PASSED；Stage Exit Accepted）
Release / Commit: 71278431dd1e5c7c4e279b44f43e084755502a5d
Reference Behavior Baseline: R2026.03
Authorized Snapshot ID: AUTH-SRC-2026-07-29-A
Public Source Snapshot: OpenAI Codex rust-v0.147.0 / be6e8eac029b183056b7e4402879f15d2c85f61b
Feature IDs: SKILL-01..07, PLUGIN-01..06, CTX-14, TOOL-16, SEC-11, MCP-08
Owner: 项目维护者
Initial evidence: 2026-08-09 / final independent rerun: 2026-08-10
```

## Gate 状态

| Gate | 状态 | 证据/退出条件 |
| --- | --- | --- |
| G0 | PASSED | ADR-058 双源来源、固定 Codex tag/commit、授权快照指纹、Unknown 与非复制边界 |
| G1 | PASSED | ADR-059/060 冻结 Feature、等级、数值上限、延期与最小可证伪行为 |
| G2 | PASSED | 独立 Skill/Plugin 契约、模块所有权、权限/恢复/生命周期与被否决方案 |
| G3 | PASSED | 实现 Commit `7127843` 的 G3-A/B/C、production composition 与独立回修已验证 |
| G4 | PASSED | 量化、安全、故障与恢复矩阵达到冻结阈值；clean verify 813 tests/21 skips，0 failures/errors |
| G5 | PASSED | 可复现临时独立 Fixture Demo 在实现 Commit 上运行 67/67（Core 4、MCP 5、CLI 58），0 skip/failure/error |
| G6 | PASSED | 矩阵已对账为 SKILL-01..07/CTX-14/PLUGIN-01..03 L2、PLUGIN-04 L1；权威文档与 Dashboard 同步，Stage Exit Accepted |

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

## G3-C 工作树验证（2026-08-09）

- Skill 显式 `/skill-name` 与模型 `activate_skill` 共用 `SkillInvoker`；Projection 以激活顺序重建且不进入 canonical transcript。
- `ToolExecutionPipeline` 在 Registry/Hook/Permission 前强制 Run visibility Gate；隐藏 Tool 写入 `SKILL_SCOPE_DENIED` execute=0 durable result，Adapter/Permission 次数为 0；`activate_skill` 作为控制面仍允许同 Run 激活不同 Skill。
- Session JSONL 只写 `skill.invoked` / `skill.completed` 的 Skill ID、入口、catalog/content digest 与固定状态；正文、资源、参数、路径不持久化。Resume/Fork 只验证 identity，不恢复 Scope、不执行 Tool/Hook；未完成或 digest mismatch Fail Closed。
- `PluginRunCoordinator` 在 Run start 捕获可信 ACTIVE generation lease，QUIESCING 后新 Run 捕获为空，所有终态先解绑动态 Hook、再逆序幂等释放；`PluginToolContribution` 继续先逆序关底层 MCP 资源再释放 lease。
- 普通 Headless 从用户私有 `registry.v1` + 精确 `plugin-trust.v1` 装载 directory-only immutable snapshots；manifest 只能选择宿主内置 MCP-backed factory，Tool 进入唯一 Pipeline。Plugin Skill 正文与资源在 Session composition 冻结，当前 Session 不随安装目录漂移；新 Session 对 tree/manifest identity 变化失信。
- Skill 引用的受信 Hook template 只允许 strict loopback HTTP，并在成功 Projection 后追加独立 Run lease；失败/取消不泄漏，compaction 不重复绑定，唯一终态逆序 exactly-once 清理，Resume/Fork 不恢复活动 Hook。
- Session recovery journal 精确绑定 manifest/body/content/resources/effective-tools/hooks/Plugin tree/Plugin manifest/MCP config 摘要，不写 args、正文、资源、路径、endpoint 或 env；任一 mismatch Fail Closed 且不 replay。
- 普通 `HeadlessRuntimeSession` Fake MCP factory E2E 覆盖 explicit/model 激活、正文/资源 Projection、Plugin Tool visibility、deny remote=0、allow exact call、Hook activation 前后/终态 lease 数和新 Session 失信；设置 refresh 的 builtin registry 也保留 `activate_skill`。
- Java print/stdio 与 React/Ink TUI 已接入类型化 Skill command；stdio/TUI 只观察 privacy-safe lifecycle，不拥有 Agent 决策。
- G3-C 当时的 `.\\mvnw.cmd -pl cc-java-cli -am test` 基线为 804 tests / 21 skips，CLI 309 / 11；G4 新增 2 个确定性测试后的最终计数见下方 G4 实际验证。此前两次 Windows 时序/启动瞬态失败均由未改代码的完整复验排除。
- `npm --prefix cc-java-tui run check`：10 files / 129 tests，0 failures。
- Dashboard generate/check/self-test 与 `git diff --check` 通过；Gate/Capability 暂不提升，量化 Demo、Commit-scoped G4-G6 仍 Open。

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

## G4 工作树独立量化、安全与恢复结果（2026-08-09）

### Metadata-first 原始量化

- Fixture 分母：20 个互相独立的本地 Skill，每个完整 `SKILL.md` 精确 `8,192 bytes`，正文均超过 8 KiB frontmatter 之外的主要占比；合计真实 eager full-file baseline 为 `163,840 bytes`，由测试逐文件 `Files.size` 求和，不使用估算或伪造基线。
- metadata materialization 分子：同一次生产 `FileSkillRepository.load` 的 `frontmatterMaterializedBytes + bodyMaterializedBytes = 800 + 0 = 800 bytes`。scanner 为 identity digest 实际顺序读取 `163,840 bytes`，但正文 materialization 为 `0`；指标区分“为 SHA-256 流式读取”与“保留/解码进 metadata projection 的 bytes”。
- 聚合公式：`reduction = 1 - sum(metadata materialized bytes) / sum(eager full-file bytes)`；结果 `1 - 800 / 163840 = 0.9951171875`，即 `99.51%`，超过冻结的 `90%` 阈值。
- 确定性断言：`FileSkillRepositoryTest.twentyLargeSkillsQuantifyMetadataMaterializationReductionAgainstEagerBaseline` 同时固定 fixture 数、逐项大小、eager 总量、digest read 总量、frontmatter/body 分量和阈值。

### Compaction、故障与恢复

- `SkillRunCoordinatorTest.hookLeasesAppendAfterProjectionAndCloseExactlyOnceInReverseOrder` 使用 compacted canonical request 连续两次真实调用生产 `project()` rebuild seam，断言 canonical 首消息保持、两个 Skill Projection 稳定重建、effective Tool scope 仍为交集、activated set 不变、Hook bind 事件不增加，终态逆序且 exactly-once close。
- normal/failure/cancel/recovery 关键路径均有确定性测试：正常多 Skill追加、binder failure、bind 后 cancel、重复 projection、终态清理、JSONL roundtrip/mismatch fail closed、Resume no replay/no active Hook，以及新 Session tree 变化失信；该矩阵命中用例通过率为 `100%`。
- 恶意/边界矩阵覆盖资源 traversal/absolute/symlink-reparse/oversize/invalid UTF-8、unknown/duplicate metadata、非 Plugin 与跨 Plugin Hook template 引用、tree/manifest/trust/MCP config digest drift、全部 installer fault points、activation rollback、orphan staging、活动 lease quiescing uninstall 与新 Session distrust mutation。

### 零值安全指标及分母

| 指标 | 结果 | 确定性分母/证据 |
| --- | ---: | --- |
| Permission/Tool/Hook bypass | 0 | hidden Skill Tool、Plugin deny、untrusted Provider/Hook 引用均断言 adapter/remote/permission 或 lease 创建次数为 0 |
| Protocol mismatch/orphan | 0 | Context reducer batch protocol、Plugin Tool/deny Tool Result 均逐 Call ID 匹配 |
| Orphan staging | 0 | `DirectoryPluginInstaller.FaultPoint.values()` 全故障点逐项断言 active snapshot 为空且 store 仅可剩稳定 `registry.v1` |
| Lease/resource/Hook leak | 0 | contribution、client、Plugin generation、Skill Hook 在 normal/failure/cancel/terminal 后计数归零并验证幂等 close |
| Privacy sentinel leak | 0 | JSONL/异常/诊断断言不含正文、资源、args、path、endpoint、env sentinel；只持久化规范 ID、枚举与 digest |

G4 的量化和离线证伪要求已在实现 Commit `7127843` 上完成 Commit-scoped 独立验收，Gate 为 `PASSED`；本次验收不额外改变已对账的 Capability Level。

### G4 实际验证命令与环境

- 环境：Windows 10 Pro 10.0.19045、Java 21、Maven Wrapper 3.9.16、Node.js 22；`evidence.commit=71278431dd1e5c7c4e279b44f43e084755502a5d`。
- Focused：`.\\mvnw.cmd -pl cc-java-cli -am "-Dtest=FileSkillRepositoryTest,SkillRunCoordinatorTest,PluginLocalAdapterTest,FileSessionStoreTest,SkillFoundationTest,HookCoordinatorTest,S11PluginSkillHeadlessE2ETest,McpBackedPluginToolProviderFactoryTest,PluginMcpPipelineIntegrationTest,DeterministicContextReducerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=0" test`；Core 37、MCP 5、CLI 51，共 93 tests，0 failures/errors/skips。
- 历史 G4 完整基线：`.\\mvnw.cmd -pl cc-java-cli -am test`；模块汇总 `806 tests / 24 skips`，0 failures/errors；CLI 模块 `311 tests / 12 skips`。该时点计数已被下方最终独立验收取代。
- TUI：`npm --prefix cc-java-tui run check`；10 files / 129 tests，0 failures。
- 看板与 Diff：`java scripts/ProgressDashboard.java`、`--check`、`--self-test`、`git diff --check` 均通过；仅有 Git 的 LF/CRLF 工作区提示。

### 最终工作树独立验收（2026-08-10）

- 协调者运行 `.\\mvnw.cmd clean verify` 成功；Surefire XML 精确汇总 Domain `53/0`、Core `226/0`、Spring `45/2`、Tools `158/8`、MCP `13/0`、CLI `318/11`，合计 `813 tests / 21 skips`，0 failures/errors。
- 协调者按 Demo 命令重跑：Core `4/4`、MCP `5/5`、CLI `58/58`，合计 `67/67`，0 skip/failure/error。早期 pre-review `60/60` 已被该结果取代。

## G5 可复现 Demo 实际结果

`docs/demos/S11-skills-plugins.md` 已使用临时公开独立 Fixture 实际演示：

1. `fix-java` Skill 在 catalog 中只展示 metadata；显式与模型各调用一次；正文/模板按需注入。
2. Skill 声明只允许 read/search/test，模型请求写文件仍由 Permission/Pipeline 拒绝。
3. Skill 资源中的伪指令不能提权；Run-scoped Hook 在终态后不再触发。
4. 安装本地 Plugin staging，批准 fingerprint 后新 Session 获得 Skill/Hook/MCP-backed Tool；当前 Session 快照不热变。
5. MCP-backed Tool 经过 ASK；未批准时远端调用次数为 0。
6. 卸载进入 QUIESCING，活动 Session 结束前文件保留，新 Session 看不到 Plugin，归零后清理。
7. 负例：digest 变化、JAR 声明、链接逃逸、namespace 冲突均拒绝。

实际命令、环境、实现 Commit `7127843`、最新 67/67（Core 4、MCP 5、CLI 58，0 skip/failure/error）及正负观察已记录在 Demo 与 `S11-g5-g6-worktree-2026-08-09.md`；早期 pre-review 60/60 已被取代；G5 为 `PASSED`。

## G6 候选对账结果

`docs/gap-reports/S11.md` 已在实现 Commit `7127843` 上对账矩阵、README、PRD、技术设计、ADR、测试、Demo 与 Dashboard。`SKILL-01..07`、`CTX-14`、`PLUGIN-01..03` 为 L2，`PLUGIN-04` 为 L1；延期能力保持 L0。完整 Reactor、Demo、TUI、launcher、安全扫描与 Dashboard 复验通过，G6 与 Stage Exit Accepted。下一路线节点仅为尚未启动的 S12 G0。

## 当前未决问题

1. S14 `PLUGIN-04` L2 的跨崩溃 registry 修复、迁移与跨平台删除策略尚未设计。
2. `PLUGIN-05/06` 与 `SEC-11` 的签名根、市场治理、SBOM/漏洞扫描和撤销策略仍 Open。
3. S12 forked Skill/Sub-Agent、S13 OS Sandbox、S14 稳定外部协议均延期。
4. `MCP-08` 与通用 `TOOL-16` 仍 L0，需独立规模/质量 Eval 后启动。
