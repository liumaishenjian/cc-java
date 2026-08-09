# S11 Skills + Plugins 可复现离线 Demo

> 状态：WORKTREE VERIFIED / PENDING FINAL COMMIT。Demo 已在 dirty worktree 使用临时、公开、独立 Fixture 实际运行；G5 最终 Commit-scoped 复验与 Accepted 由协调者在实现 Commit 上完成。

## 目标 Fixture

临时 Workspace 包含一个小型 Java 项目、`fix-java` Skill、一个资源模板和一个本地 Plugin。Plugin 只打包 Skill、S09 Hook 与 loopback MCP-backed Tool；不含 JAR、Class、native library 或安装脚本。

## 计划场景

| 场景 | 操作 | 预期观察 |
| --- | --- | --- |
| Metadata-first | 启动含 20 个 ≥8 KiB Skill 的 Session | catalog 只投影名称/描述；正文读取字节为 0，较全正文降低 ≥90% |
| 显式调用 | 输入 `/fix-java` | 命中 snapshot 后加载目标正文/资源，其他 Skill 不加载 |
| 模型调用 | 模型调用 Skill Tool | 与显式入口共享 invocation identity 和 digest Gate |
| Tool 收窄 | Skill 只允许 read/search/test，运行中 Permission/Grant 变化后连续请求 Tool | 可见集只做 runtime∩skill；每个调用都重新执行 Permission/Approval/Pipeline，不缓存 Grant |
| 激活深度 | 同 Run 激活两个不同 Skill、重复第一个并尝试 nested Skill | 不同 Skill 稳定顺序各一次；重复/nested/reentrant 拒绝；模型 activate 成功前 Scope/Hook 为 0 |
| Resource 负例 | 模板含“绕过权限”文本或链接逃逸 | 只作为不可信 Context或加载前拒绝，副作用为 0 |
| Scoped Hook | Skill 调用期间触发 Hook，Run 结束后再次运行 | Hook 只在绑定 Run 生效，终态后存活数 0 |
| Recovery | 活动 Run compact，随后在无活动 Run 状态 resume；再修改 Skill | compact 可按 digest 重投影且不重复激活；resume 不恢复 Hook/Tool Scope；变更产生 mismatch 诊断且不自动重放 |
| Plugin install | staging → validate → fingerprint → trust → activate | 旧 Session 不热变；新 Session 获得 namespaced 组件 |
| Plugin Tool | 模型请求 MCP-backed Tool并申请 Session Allow | 宿主构造的 PLUGIN Network Tool 进入 ASK；Grant 绑定来源+完整 qualified name+selector，批准后经统一 Pipeline 返回匹配 Call ID |
| Provider lifecycle | named MCP Server 初始化部分失败或 Session 关闭 | Contribution 逆序关闭已创建 Tool、client/transport 和 snapshot lease，factory 不持有 close |
| Plugin 恶意输入 | archive、JAR/Class/native/script、伪造 ToolDefinition/ToolSource、未声明 Server、链接、冲突名、超限 tree | 全部 Fail Closed，不激活 Provider；不解包 archive |
| Uninstall | 活动 Session 时卸载 | 状态 QUIESCING，新 Session 不获得；引用归零前不删，之后删除或 tombstone |

## 实际运行环境与命令

```text
Initial evidence: 2026-08-09 / final independent rerun: 2026-08-10
Environment: Windows 10 Pro 10.0.19045 / Java 21 / Maven Wrapper 3.9.16 / Node.js 22
Commit: N/A-DESIGN-WORKTREE（禁止伪造 hash；未 commit/push）
```

Demo runner 使用 Maven Surefire 驱动真实 production composition 和临时目录 Fixture；不访问网络、不读取 API Key：

```powershell
.\mvnw.cmd -pl cc-java-cli -am `
  "-Dtest=FileSkillRepositoryTest,S11PluginSkillHeadlessE2ETest,SkillRunCoordinatorTest,PluginLocalAdapterTest,McpBackedPluginToolProviderFactoryTest,PluginMcpPipelineIntegrationTest,FileSessionStoreTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

最新结果：Core `4/4`、MCP `5/5`、CLI `58/58`，合计 `67/67` tests，`0` failure、`0` error、`0` skip，BUILD SUCCESS。早期 pre-review 运行曾为 `60/60`（CLI `51/51`），已由新增 7 个 `PluginLocalAdapter` 回归后的本次结果取代，不作为最终分母。

## 实际正负观察

| 场景 | 正向观察 | 负向/零值观察 |
| --- | --- | --- |
| Metadata-first | 20×8,192-byte fixture，eager `163,840` bytes，metadata materialization `800` bytes，降低 `99.51%` | body materialization `0`；完整文件仅流式参与 digest，不进入 catalog |
| 双入口与 Projection | explicit/model 均经 production `HeadlessRuntimeSession`，正文和资源 sentinel 出现在 transient `SkillContextMessage` | args/body/resource 不进入 JSONL；canonical journal 不被 Projection 改写 |
| Tool 收窄与 Pipeline | allowed Plugin MCP Tool 可见；allow 后 remote name=`search` 且结果 Call ID 匹配 | deny 时 remote call=`0`；隐藏 Tool 的 adapter/permission/approval bypass=`0` |
| Scoped Hook | activation 后当前 Run binding=`1`，terminal 后=`0` | activation 前、失败、取消、Resume/Fork 均无 Hook；重复 rebuild 不重复 bind |
| Compaction/rebuild | compacted canonical 首消息和两个 Skill Projection 顺序保持，effective tools 仍为交集 | 连续两次 rebuild 的 activated set/bind events 不增加，protocol orphan=`0` |
| Recovery | 全 digest 精确匹配可恢复 Session metadata，且不 replay Tool/Hook | manifest/body/content/resources/tools/hooks/tree/manifest/MCP 任一 mismatch Fail Closed |
| Plugin install/uninstall | 全 staged fault 后旧 generation/registry 保持；活动 lease 可继续读 snapshot | fault-point active snapshot=`0`、orphan staging=`0`；QUIESCING 拒绝新 lease，归零后删除 |
| Snapshot/Trust | 当前 Session 使用冻结 Skill bytes；新受信 Session装载 namespaced Skill/Tool | 安装目录任意 byte 变化使新 Session失信；registry/trust/config digest 错配贡献=`0` |
| Privacy | Journal 只含规范 ID、enum、digest 与固定状态 | args/body/resource/path/endpoint/env sentinel 泄漏=`0` |

## 补充完整验证

- 协调者最终独立 `.\mvnw.cmd clean verify`：Domain `53/0`、Core `226/0`、Spring `45/2`、Tools `158/8`、MCP `13/0`、CLI `318/11`，合计 `813 tests / 21 skips`，0 failures/errors，BUILD SUCCESS。历史 G4 工作树基线 `806/24`（CLI `311/12`）已被该结果取代。
- `npm --prefix cc-java-tui run check`：10 files / 129 tests，0 failures；launcher `59/59` assertions 通过。
- Dashboard generate/check/self-test 与 `git diff --check` 通过。


## 当前边界

- G6 工作树候选将 `SKILL-01..07`、`CTX-14`、`PLUGIN-01..03` 对账为 L2、`PLUGIN-04` 为 L1；最终 Accepted 仍等待实现 Commit 上的协调者复验；
- 不加载任意 JAR；
- `PLUGIN-05/06`、`SEC-11`、`MCP-08`、`TOOL-16` 保持 L0；
- 不具备 Marketplace、签名、OS Sandbox、Sub-Agent Skill 或稳定安装/迁移协议。
