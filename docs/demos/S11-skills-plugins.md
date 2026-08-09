# S11 Skills + Plugins Demo 计划

> 状态：Planned。S11 当前只完成 G0-G2；本文没有实际执行结果，不能作为 G5 通过证据。

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

## 计划验证命令

实现完成后补充实际聚焦测试、完整 Maven Reactor、TUI check、launcher self-test、Demo runner、Dashboard 三命令和 `git diff --check`。命令、环境、Commit、passed/total、skip 和真实观察结果必须写回本文与 S11 evidence；不得把本计划改写成已通过。

## 当前边界

- `SKILL-01..07`、`PLUGIN-01..04` 仍为 L0；
- 不加载任意 JAR；
- `PLUGIN-05/06`、`SEC-11`、`MCP-08`、`TOOL-16` 保持 L0；
- 不具备 Marketplace、签名、OS Sandbox、Sub-Agent Skill 或稳定安装/迁移协议。
