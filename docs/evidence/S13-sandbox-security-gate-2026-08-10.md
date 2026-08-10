# S13 Sandbox + Security G3-G5 Candidate Evidence

- Stage: S13
- Status: In Progress（G3-G5 Candidate；G6 waits implementation Commit）
- Release / Commit: working tree after `33086a7`; no commit created
- Reference: `R2026.03` / `AUTH-SRC-2026-07-29-A` / Codex `rust-v0.147.0`
- Date: 2026-08-10

## G0-G2

ADR-063/064 的双源、范围、独立契约与停止条件继续有效。实现没有复制参考表达或新增依赖。

## G3 Candidate

Batch A-C 已一次性落地：

- Domain/Core 新增不可变 ExecutionBackend request/outcome/failure、selector/fallback、capability snapshot/status、五维 policy/report、Managed baseline/provenance 和 PERM-05 deterministic auto skeleton；公共安全契约含中文 Javadoc。
- `LocalCommandExecutor` 不再直接启动 ProcessBuilder，而是适配唯一 backend seam；Local 明确报告 `UNSANDBOXED_LOCAL`。run_command 结果展示 backend/enforcement/fallback，Call ID 进入 request。
- CLI 新增明确 `--execution-backend local|sandbox|container` 和 `--execution-shell platform|linux-sh`；Sandbox/Container 若非 LINUX_SH 立即拒绝，PowerShell/cmd 不隐式转换。stdio/TUI 沿用参数透传与既有协议，无需第二套 UI/Loop/Pipeline。
- WSL2 backend 固定系统 `wsl.exe`、Ubuntu、`/usr/bin/bwrap`，双向 fixed-drive path identity、user/pid/net/ipc/uts namespace、只读 host、显式 writable Workspace/tmp、控制面 ro-bind、空环境与统一 cleanup。
- Docker backend 使用 fixed CLI、pinned `nginx@sha256:0d17...9b31`、network none、read-only、非 root、cap-drop all、no-new-privileges、PID limit 与 rm cleanup。
- native Windows 真实报告仅 process/env/secret B，file/network UNKNOWN；macOS C/U。JVM HTTP/MCP remote 不经过 backend，HOOK-10 保持 L1。

Command Hook 与 MCP stdio 已复用共享 `ManagedProcessLauncher` 的 fixed argv/minimal env seam，Sub-Agent run_command 显式继承父 backend/shell。宿主 Git 控制操作仍是可信 fixed-argv 端口；这些入口均不冒充 OS Sandbox，MCP HTTP 继续排除。

## G4 Candidate 实际验证

| 验证 | 结果 |
| --- | --- |
| `mvnw clean verify` | PASS；标准离线 Surefire XML 汇总 851 tests/10 skips/0 failure/error：Domain 53/0、Core 238/0、Spring 45/2、Tools 175/8、MCP 13/0、CLI 327/0 |
| focused real | 13/13 PASS、0 skip：`S13RealBackendAttackTest` 8/8 + `ExecutionBackendSelectorTest` 5/5 |
| `S13RealBackendAttackTest` | disposable fixture、`/proc/net/dev` 仅 lo、outside 隔离、WSL timeout/cancel orphan、Docker readonly mask、PIDS_BLOCKED 非超时终态及无 limit 对照、生产 Bootstrap→RunCommandTool 组合与零残留 |
| TUI | 133/133 PASS |
| launcher | 59/59 PASS |

真实环境：Windows 10 host；Ubuntu WSL2；bubblewrap 0.4.0；Docker Desktop 4.31.1 / Engine 26.1.4 linux amd64；pinned nginx digest already present。

安全观察：outside/control-plane write、direct network、Secret sentinel、静默 fallback、测试产生 orphan 均为 0。Linux 证据分类 A（hosted on Windows），Container B，native Windows B（file/network U），macOS C/U。

## Candidate levels

- L1→L2: SEC-02/03/04/05
- L0→L2: SEC-06/07/12, EVAL-04
- L0→L1: SEC-08；PERM-05、CFG-07 因未生产接入保持 L0
- unchanged: PERM-08/09/12, SEC-09 L2; HOOK-10 L1; SEC-11 L0

## G5 Candidate

`docs/demos/S13-sandbox-security.md` 已记录真实可复现命令、正负例与环境结果。

## G6 Open

没有 implementation Commit，故不做 commit-scoped 复验、不宣称 Stage Exit Accepted。Command Hook/MCP stdio 已收敛到共享 managed fixed-argv seam，Sub-Agent run_command 已继承父 execution 配置；MCP HTTP、Managed/Auto 生产接入与完整 OS 级覆盖仍是后续 gap。
