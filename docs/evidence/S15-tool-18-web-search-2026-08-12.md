# S15 TOOL-18 hosted MCP Web Search G0-G5 Evidence

- Stage: S15 Independent Innovation
- Status: In Progress；Stage Exit Open
- Release / Commit: 工作树实现，未 commit
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Snapshot: OpenCode `0d927ba03f36d7f87e3cdb2b6c1f34c44913a099` / tree `e749e4c946cf0eca237143472882a104bbbbcdb8` / MIT
- Feature: `TOOL-18 L0 → L2`
- Date: 2026-08-12

## G0-G2

ADR-067 记录授权快照边界与 OpenCode 固定公开 revision、五个研究文件、Documented/Observed/Inferred/Unknown、许可证、采纳/偏离和停止条件。ADR-068 冻结独立 Java hosted MCP Tool、Provider gate、Permission、NetworkAccess、JSON-RPC/JSON/SSE、取消、隐私与 ceiling。参考字节、函数体、Prompt、文案、私有名称、布局和常量未进入仓库。

## G3

新增 `cc-java-tools-web` 边缘模块和 Headless production composition。`web_search`=`BUILT_IN + NETWORK_OR_REMOTE`，schema 只有 query/result_limit。显式本地 `enabled + exa|parallel` 才注册；endpoint 与远端 Tool 固定，模型不能提供 endpoint/Header/credential。每次 attempt 先经过 NetworkAccessPort；JDK HttpClient redirect NEVER；JSON-RPC `tools/call` 接受严格有界 JSON/SSE，返回 external/untrusted textual content，不抓取引用页。Blocker 返修进一步从 `search` 入口建立 monotonic 总 wall deadline，用可关闭虚拟线程 operation 覆盖 NetworkAccess、headers、完整 body 与解析；timeout/cancel first-wins 地取消 HTTP future、关闭 active body 并中断 operation，Client close `shutdownNow`，不使用永久 scheduler。Core/Domain 不依赖 HttpClient、JSON、Spring 或文件系统；应用层控制不宣称 OS Sandbox。

## G4 focused 与真实 E2E

| Evidence | Result |
| --- | --- |
| Focused `WebSearchToolTest` | PASS 13/13：schema、Exa 无 key/编码 query-key、Parallel Bearer、JSON/SSE 参数、unknown media type、typed matrix、headers+partial-body stall 总 wall timeout、慢正文 cancel/释放、secret/query 零输出、清洗 |
| Focused `WebSearchPipelineTest` | PASS 3/3：默认/显式 deny 零 HTTP；allow 后 Call ID、durable/final exactly-once |
| Focused `WebSearchSettingsLoaderTest` | PASS 4/4：默认关闭、Provider gate、Exa no-key、provider key、任意 endpoint 不可覆盖 |
| Focused `S15WebSearchHeadlessE2ETest` | PASS 2/2：默认隐藏、启用注册并经真实 loopback Agent Loop |
| Focused aggregate | PASS 22/22，0 failure/error/skip；deadline 首次 focused 因 test helper 对空 sensitive 数组调用 AssertJ 导致 2 errors，修复后通过；此前 URI query 二次编码导致 1 个 wire 断言失败历史继续保留 |
| 返修后真实 Exa no-key hosted MCP smoke | PASS：host=`mcp.exa.ai`，HTTP 200，Content-Type=`text/event-stream`，1,130 bytes，JSON-RPC/content present |
| 安装版 `codej --print "杭州今天的天气"` | PASS：started=1、completed=1、Call ID match、SUCCESS、host=`mcp.exa.ai`、Run COMPLETED、最终回答使用 2026-08-12 |

真实 E2E 通过 Git ignored provider config 显式启用 Exa，并使用 Git ignored local ALLOW rule 使非交互 Print 可执行；二者均未提交。证据只保存安全摘要，不保存完整 query、第三方正文或 credential。初次真实 E2E 暴露模型使用 8 月 11 日，修复为 runtime metadata 提供本机当前日期后重跑通过，证明日期语义可证伪而非靠天气地点硬编码。

## G4 完整验证

完整验证最终结果如下。原 S15 实现/认证返修阶段已保留：真实 `codej` stdio 持有 JAR、历史 Worktree `CreateProcess error=5`/`FAILED_PRESERVED`、并发 Maven target 竞争和未引用 dotted `-D` 参数等失败及对应成功复跑。本轮 wall-deadline 返修的 focused 首次仅因测试 helper 对空 sensitive 数组调用 AssertJ 而出现 2 errors，修复 helper 后 22/22 通过；本轮第一次完整串行 clean verify 即通过，无 Windows flaky 或隔离复跑。

| Command | Result |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS；933 tests、0 failure/error、32 skips；11 modules BUILD SUCCESS（wall-deadline 返修首次完整串行运行即通过） |
| `.\mvnw.cmd -DskipTests -Dmaven.javadoc.failOnWarnings=true javadoc:aggregate` | PASS；0 warning，BUILD SUCCESS |
| `npm --prefix .\cc-java-tui test` | PASS；133/133 |
| `pwsh -NoProfile -File .\scripts\TestCodejDevLauncher.ps1` | PASS；59 assertions |
| Dashboard generate/check/self-test | PASS |
| `git diff --check` | PASS；仅 line-ending warning，无 whitespace error |

## G5

可复制配置、focused 命令、真实 smoke、真实 `codej` 路径、安全摘要和事实边界见 `docs/demos/S15-controlled-web-search.md`。

## 等级与 Stage 边界

生产 composition、确定性故障/安全矩阵、真实 hosted MCP 与实际 `codej` Agent Loop 支持 `TOOL-18` 达 L2。S15 仍为 IN_PROGRESS/OPEN；本项是参考工具基线，不是相对 S14 的 L4 创新证据。用户禁止 commit，因此 commit-scoped G6 留待维护者后续绑定实现 Commit。
