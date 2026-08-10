# S13 Sandbox + Security G0-G2 Evidence

- Stage: S13
- Status: In Progress（G0-G2 Frozen；G3-G6 Open）
- Release / Commit: Working tree documentation only；不以未提交状态作为实现证据
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source: OpenAI Codex `rust-v0.147.0` / `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Date: 2026-08-10

## G0：来源与授权 — PASS

- 按根 `AGENTS.md` 顺序完整阅读 README、参考架构、两个基线、矩阵、PRD、技术设计、列出的 ADR 与证据模板。
- 授权材料只在仓库外 `G:\AI Cloud\claude-code-main` 只读研究；准确 Revision/License/权利人/公开再使用权保持 `Unknown`。
- Codex tag 复验：annotated tag object `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d` peeled 为 commit `be6e8eac029b183056b7e4402879f15d2c85f61b`；仓库外 detached clone HEAD 匹配。
- 未把双源字节、Fixture、Golden Output、函数体、Prompt、私有命名、布局或常量带入仓库。
- 抽象结论、采纳/偏离/Unknown 见 ADR-063。

## G1：范围与目标 — PASS

冻结范围：

- L1→L2：`SEC-02/03/04/05`
- L0→L2：`SEC-06/07/12`、`EVAL-04`
- L0→L1：`SEC-08`、`PERM-05`、`CFG-07`
- 保持 L1：`HOOK-10`（JVM 内 HTTP 不受 ExecutionBackend 强制）
- 保持 L2 并做组合回归：`PERM-08`、`PERM-09`、`PERM-12`、`SEC-09`
- 保持 L0：`SEC-11`

最小可证伪行为是：同一个已获准 Command 在 Local backend 可触达的主机文件/网络，在 Sandbox backend 被 OS 强制拒绝；取消/timeout 后完整进程树消失；环境与所有投影无 Secret sentinel；backend 不可用或策略不可表达时 execute count 为 0，除非用户在执行前对当前 Call ID 显式批准一次 Local fallback。

## G2：研究与架构 — PASS

ADR-064 已冻结：

- `ExecutionBackend`、Local、Windows-hosted WSL2 Linux bwrap、可选 Docker Container；native Windows/macOS 只按真实 probe 报 B/C/U；
- platform capability probe 与 `ENFORCED/DEGRADED/UNAVAILABLE/UNKNOWN`，包括 WSL version、bwrap self-test、Docker daemon/image，而非 CLI-only；
- file/process/network/environment/secret policy，以及明确排除 JVM 内 HTTP；
- Managed deny-only baseline、fail-closed 与显式一次性 fallback；
- 唯一 `ToolExecutionPipeline` 与 Command/Sub-Agent/Plugin/MCP/Hook 一致入口；
- timeout/cancel/进程树清理；
- 攻击 Fixture、安全矩阵、跨平台 A/B/C/U 证据分级；
- 所有新增/修改核心公共契约必须有准确中文 Javadoc；
- 最多三个完整实现 Batch，而不是按 Gate 拆微任务。

## 现有接缝核验

| Stage/接缝 | 当前真实边界 | S13 改造点 |
| --- | --- | --- |
| S03 WorkspaceGuard | Tool 参数 realpath/敏感路径，非 OS 隔离 | 保留为第一层；OS file policy 为第二层 |
| S04 Command | `LocalCommandExecutor` 直接 ProcessBuilder、最小环境、cleanup | 收敛到 `ExecutionBackend`；Local 仅显式非隔离后端 |
| S05 Permission | Hard Denial/规则/审批在唯一 Pipeline | Permission 先于 backend，不能被 Sandbox 取代 |
| S08 Settings | user/project/local/session merge 与 provenance | 增加不可放宽 Managed security baseline |
| S09 Hook | Command fixed argv；HTTP 仅 loopback且在当前 JVM | Command 使用 backend；HTTP 不受其强制，`HOOK-10` 保持 L1 |
| S10 MCP | stdio 进程与 JVM 内 HTTP remote Adapter | stdio 使用 backend；remote 只保留既有应用层控制，不计 OS network 证据 |
| S11 Plugin | 宿主 SPI/MCP-backed、拒绝任意 JAR | Plugin 不选择 backend；SEC-11 仍 L0 |
| S12 Sub-Agent/Worktree | child 重装配与 Git cwd 隔离 | child Command 使用相同 backend；Worktree 不放宽 policy |

## G3-G6 — OPEN

- G3：未写生产或测试实现。
- G4：尚无 WSL2 Linux bwrap A、Docker Container B 或 native Windows B 级证据；当前 Ubuntu 缺 bwrap、Docker daemon 不可连接，不得提升 Capability。
- G5：Demo skeleton 已创建，尚无实际执行结果。
- G6：无 implementation Commit 或 commit-scoped 验收；Stage Exit Open。

## 第二阶段实现批次

1. Batch A：Contracts + Local refactor + truthful WSL2/bwrap/Docker/native probe。
2. Batch B：Windows-hosted WSL2 Ubuntu + bwrap Linux A、path identity 与显式 `LINUX_SH`；`HOOK-10` 不提升。
3. Batch C：Docker daemon + pinned image Container B、attack matrix、native Windows B/C/U、macOS C/U 与 G4-G6。

## 验证门槛

- 安全违规、旁路、静默 fallback、Secret 泄漏、orphan 全为 0。
- Windows-hosted WSL2 Ubuntu+bwrap 对核心隔离维度达到 Linux A；不得表述为 native Windows A。
- Docker daemon + pinned image 达到 Container B；当前仅 CLI 不满足，未关闭则 `SEC-08` 保持 L0 且 Stage Exit Open。
- native Windows 至少真实 B（process/env/cleanup），file/network 为 C/U 可接受但必须列 gap；macOS C/U。
- 标准 clean verify、TUI、launcher、Dashboard、`git diff --check` 与 commit-scoped 复验全部通过后才允许 G6。