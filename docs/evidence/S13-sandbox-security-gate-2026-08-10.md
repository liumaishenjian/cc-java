# S13 Sandbox + Security Commit-scoped Stage Evidence

- Stage: S13
- Status: Accepted
- Release / Commit: `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3`
- Reference: `R2026.03` / `AUTH-SRC-2026-07-29-A` / Codex `rust-v0.147.0`
- Date: 2026-08-10
- Stage Exit: PASSED

## G0：来源与授权 — PASSED

ADR-063 固定授权快照、Codex 公开固定版本、采纳/偏离/Unknown 与停止条件；ADR-064 使用本项目独立 Java 契约、命名、策略和 Fixture。实现没有复制参考表达、引入参考字节或新增依赖。

## G1：范围与目标 — PASSED

| Feature | 验收前 | 验收后 | 结论 |
| --- | ---: | ---: | --- |
| `SEC-02/03/04/05` | L1 | L2 | native 应用层回归 + Linux Sandbox A 组合证据 |
| `SEC-06/07/12`、`EVAL-04` | L0 | L2 | Windows-hosted WSL2+bwrap Linux A 与攻击矩阵 |
| `SEC-08` | L0 | L1 | Docker daemon + pinned image B |
| `PERM-08/09/12`、`SEC-09` | L2 | L2 | 组合回归，不升 L3 |
| `PERM-05`、`CFG-07` | L0 | L0 | 只有未接线骨架，未生产接入 |
| `HOOK-10` | L1 | L1 | JVM 内 HTTP 不受进程 backend 强制 |
| `SEC-11` | L0 | L0 | 签名、SBOM、撤销与供应链隔离延期 |

在本证据记录的 S13 验收时点，S14 为 `NOT_STARTED`，且不因 S13 Accepted 自动开始；S14 后续已在实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 上 Accepted with documented deviations。

## G2：研究与 ADR — PASSED

ADR-063/064 已固定：

- Permission/Approval 与 OS 强制边界正交；
- `ExecutionBackend`、五维 policy、truthful capability probe 与 fail-closed selector；
- Local 明确为 `UNSANDBOXED_LOCAL`，fallback 不能静默发生；
- Windows-hosted WSL2 Ubuntu+bwrap、Docker optional backend、显式 `LINUX_SH` 与双向 path identity；
- Linux A / Container B / native Windows B-U / macOS C-U 的诚实证据分级；
- JVM 内 HTTP、MCP/Plugin remote 不计入 `SEC-07`；
- Permission、Checkpoint、Worktree、Job cleanup、最小环境与 Local backend 均不等于 Sandbox。

## G3：独立实现 — PASSED

Batch A-C 已在固定 implementation commit 中完成：

- Domain/Core：不可变 ExecutionBackend request/outcome/failure、selector/fallback、capability snapshot/status、五维 policy/report、Managed baseline/provenance；公共安全契约具有中文 Javadoc。
- `run_command`：通过唯一 backend seam 执行；结果明确报告 backend/enforcement/fallback，Call ID 进入 request。
- CLI：明确 `--execution-backend local|sandbox|container` 与 `--execution-shell platform|linux-sh`；PowerShell/cmd 不隐式转换。
- WSL2：固定系统 `wsl.exe`、Ubuntu、`/usr/bin/bwrap`，实施 path identity、namespace、只读 host、显式 writable Workspace/tmp、控制面保护、空环境和统一 cleanup。
- Docker：fixed CLI、pinned nginx digest、`--network none`、read-only、非 root、cap-drop、no-new-privileges、PID limit 与清理。
- 进程入口：Command Hook 与 MCP stdio 复用 `ManagedProcessLauncher` 的 fixed-argv/minimal-env seam；Sub-Agent root/child execution composition 显式继承 backend/shell。
- native Windows：只报告实际 process/env/secret B，file/network U；macOS C/U。

## G4：验证 — PASSED

### 标准离线验证

| 模块 | Tests | Skips | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| domain | 53 | 0 | 0 | 0 |
| core | 238 | 0 | 0 | 0 |
| model-spring-ai | 45 | 2 | 0 | 0 |
| tools-local | 175 | 16 | 0 | 0 |
| mcp | 13 | 0 | 0 | 0 |
| cli | 327 | 11 | 0 | 0 |
| **合计** | **851** | **29** | **0** | **0** |

命令：

```powershell
.\mvnw.cmd clean verify
npm --prefix cc-java-tui test
pwsh -NoProfile -File scripts/TestCodejDevLauncher.ps1
```

结果：Maven 851 tests/29 skips，TUI 133/133，launcher 59 assertions，均通过。

### 真实后端验证与环境恢复

真实测试为 selector 5/5 + attack 8/8，共 13/13，0 skip/failure/error：

```powershell
$env:CC_JAVA_S13_REAL_BACKENDS='true'
$env:CC_JAVA_S13_DOCKER_IMAGE='nginx@sha256:0d17b565c37bcbd895e9d92315a05c1c3c9a29f762b011a10c54a66cd53c9b31'
.\mvnw.cmd -pl cc-java-tools-local -am `
  '-Dtest=ExecutionBackendSelectorTest,S13RealBackendAttackTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

首次运行时 Docker daemon 未启动，5 个 Docker 用例失败。该失败不计入通过证据，也没有被隐藏；启动 Docker Desktop并确认 daemon 26.1.4 后，完整 13/13 通过。真实环境为 Windows 10 host、Ubuntu WSL2、bubblewrap 0.4.0、Docker Engine 26.1.4 linux/amd64 与 pinned nginx digest。

真实攻击覆盖 disposable fixture、仅 loopback network namespace、Workspace 外隔离、WSL timeout/cancel orphan、Docker readonly mask、PID 限制、生产 Bootstrap→RunCommandTool composition。测试后 `label=cc-java.s13=true` 的 Docker residue 为 0。

安全结果：Workspace 外写、deny-read、保护路径修改、直接网络绕过、Secret sentinel 泄漏、静默 fallback、Permission/Pipeline 旁路与 orphan process 均为 0。

证据分级：Linux A（hosted on Windows）、Container B、native Windows process/env B（file/network U）、macOS C/U。

## G5：可复现 Demo — PASSED

[`docs/demos/S13-sandbox-security.md`](../demos/S13-sandbox-security.md)记录了环境前置条件、标准命令、真实测试、Docker daemon 首次失败与恢复、正负例、residue 检查和 A/B/C/U 边界。

## G6：退出对账 — PASSED

- implementation commit 已固定为 `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3`；
- README、根 AGENTS、矩阵、PRD、技术设计、ADR、Evidence、Demo、Gap 与 progress state 已对账；
- Dashboard generate/check/self-test、`git diff --check` 与 secret scan 通过；
- secret scan 唯一命中是 `FileMemoryRepositoryTest` 中故意使用的私钥哨兵，不是真实凭证；
- S13 G0-G6 全部 PASSED，Stage Exit Accepted；
- 本次 S13 验收时 S14 为 NOT_STARTED；S14 后续已在实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 上 Accepted with documented deviations。

## 剩余边界

1. `PERM-05` Auto Mode、`CFG-07` Managed Policy 未生产接入，保持 L0。
2. JVM 内 HTTP、MCP/Plugin remote 不受 `ExecutionBackend` 强制；`HOOK-10` 保持 L1。
3. native Windows file/network 为 U，WSL2 Linux A 不等于 native Windows A；macOS 无真实主机，仅 C/U。
4. `SEC-11` 保持 L0；Docker image 签名/SBOM/更新、Marketplace、OAuth、稳定协议与迁移属于 S14/S15 后续范围。
