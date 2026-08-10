# ADR-064：S13 ExecutionBackend 与纵深安全独立契约

- Status: Accepted
- Date: 2026-08-10
- Stage: S13 Sandbox + Security（Commit-scoped G0-G6 / Stage Exit Accepted）
- Feature IDs: `SEC-02..09/12`、`PERM-05/08/09/12`、`CFG-07`、`HOOK-10`、`EVAL-04`；审视但不提升 `SEC-11`
- Current → Exit Target:
  - `SEC-02/03/04/05`：L1 → L2（既有 native 应用层回归 + Linux Sandbox A 级组合证据）
  - `SEC-06/07/12`、`EVAL-04`：L0 → L2（以真实 Linux Sandbox A 为可用主路径；不声称 native Windows/macOS L2）
  - `SEC-08`：L0 → L1（Docker daemon 可用时的显式 opt-in B 级 smoke；仅有 CLI 不算）
  - `PERM-05`、`CFG-07`：L0 → L0（未生产接入）
  - `HOOK-10`：L1 → L1（当前 JVM 内 HTTP 不受 `ExecutionBackend` 强制；S14 或新增 `NetworkAccessPort` 后再提升）
  - `PERM-08`：L2 → L2、`PERM-09`：L2 → L2、`PERM-12`：L2 → L2、`SEC-09`：L2 → L2（只做组合回归，不升级）
  - `SEC-11`：L0 → L0（明确延期）
- Depends On: ADR-032/035/039/047/052/055/057/060/062/063

## 决策摘要

S13 在本地执行基础设施边缘引入唯一 `ExecutionBackend` seam。Tool Call 仍先经过 `ToolExecutionPipeline` 的参数校验、Hook、Permission、Approval、durable started；只有最终 Allow 后，Tool Adapter 才把结构化 `ExecutionRequest` 交给 backend。Sandbox 不建立第二套 Tool Pipeline，也不把审批、Checkpoint、Worktree 或 cleanup 重新包装成隔离。

```text
ToolExecutionPipeline
  → allowed Tool adapter
  → ExecutionRequest + EffectiveExecutionPolicy
  → PlatformCapabilityProbe
  → ExecutionBackendSelector
       → LocalExecutionBackend
       → PlatformSandboxBackend
       → optional ContainerExecutionBackend
  → ExecutionOutcome + EnforcementReport
  → normalize/redact/durable completed
```

## Feature 范围与独立行为

| Feature | S13 可证伪行为 | Exit |
| --- | --- | ---: |
| `SEC-02/03` | 既有 native Guard 回归，并在 Linux Sandbox 内证明链接/敏感路径不可越界 | L2 |
| `SEC-04` | Linux sandbox timeout/cancel/shutdown 杀死完整进程树，native Windows 保持既有 cleanup 回归 | L2 |
| `SEC-05` | 所有 backend 从空环境构造，仅显式非 Secret 值；Secret sentinel 泄漏为 0 | L2 |
| `SEC-06` | Linux OS 强制只读/可写根与保护子路径；native Windows 只报告实际 B/C/U | L2（Linux 主路径） |
| `SEC-07` | Linux/container 默认无网络且直接 socket 绕过失败；不把 JVM 内 HTTP 算入 | L2（Linux 主路径） |
| `SEC-08` | Docker daemon 可用时，Container Adapter 完成显式 opt-in B 级 smoke；CLI-only 为 UNAVAILABLE | L1 |
| `SEC-09` | 恶意仓库/Tool 输出不能改变 policy/backend/fallback | 保持 L2 |
| `SEC-12/EVAL-04` | Linux A + Container B + Windows native B/C/U + macOS C/U 的诚实安全矩阵 | L2 |
| `PERM-05` | 仅有未接线的确定性免询问骨架，生产装配不可用 | 保持 L0 |
| `PERM-08` | Protected Paths 组合回归 | 保持 L2 |
| `PERM-09` | Hard Denial 组合回归 | 保持 L2 |
| `PERM-12` | 既有 user/project/local Settings scope 组合回归；Managed 仍由保持 L0 的 `CFG-07` 单列 | 保持 L2 |
| `CFG-07` | baseline/provenance 仅为未接线骨架，尚无生产 Managed Policy | 保持 L0 |
| `HOOK-10` | 保持 loopback HTTP L1；远程 JVM HTTP 不因进程 backend 自动受控 | 保持 L1 |

## 独立 Java 契约

以下公共契约在 G3 创建/修改时必须提供标准中文 Javadoc，解释职责、非职责、信任边界、所有权、取消、失败、fallback 和平台证据：

```text
ExecutionBackend
ExecutionBackendId
ExecutionRequest
ExecutionOutcome
ExecutionFailure
ExecutionBackendSelector
ExecutionFallbackDecision

PlatformCapabilityProbe
PlatformCapabilitySnapshot
CapabilityStatus        // ENFORCED | DEGRADED | UNAVAILABLE | UNKNOWN
EnforcementDimension    // FILE | PROCESS | NETWORK | ENVIRONMENT | SECRET
EnforcementReport

ExecutionPolicy
FileAccessPolicy
ProcessPolicy
NetworkPolicy
EnvironmentPolicy
SecretPolicy
PolicyProvenance
ManagedSecurityBaseline

LocalExecutionBackend
PlatformSandboxBackend
ContainerExecutionBackend (optional)
```

`ExecutionBackend` 只强制其亲自启动并拥有的进程树。当前 JVM 内 `HttpClient`、Spring AI MCP HTTP Client 或其他 in-process socket **不经过它，也不受它的文件/网络 policy 约束**。S13 不新增半成品网络 broker，因此远程 `HOOK-10` 保持 L1；MCP/Plugin HTTP 继续受 ADR-057 的 URL/Trust/redirect/credential 应用层校验，但不得因 S13 自动放行或计入 `SEC-07` OS 隔离证据。若未来要使 JVM 内 remote 达到强制网络 L2，必须另建并接入所有 remote 入口的 `PolicyAwareNetworkAccessPort`/egress broker，禁止直接构造 `HttpClient`，并以直接 socket/redirect/DNS rebinding 负例证明；该工作延期 S14 或独立后续 ADR。

### 核心语义

- `ExecutionRequest` 只包含 executable/固定 argv、cwd identity、显式环境、stdin policy、deadline、output ceilings、cancellation identity 和不可变 policy；模型不能选择 backend、helper 或 fallback。
- `ExecutionBackend.execute` 必须返回实际 backend、已强制维度、能力降级、退出/信号、timeout/cancel、输出与隐私安全失败；不能仅返回 exit code 并让调用者猜测是否真的隔离。
- `PlatformCapabilityProbe` 在执行前实际检查依赖、平台原语和最小自测，结果绑定当前进程/主机 identity；配置文本或 OS 名不构成 `ENFORCED`。
- `ExecutionBackendSelector` 只选择能完整表达有效策略的 backend。任何维度 `UNKNOWN/UNAVAILABLE` 都不能被“尽力而为”视为成功。
- `LocalExecutionBackend` 是明确的非隔离后端，用于可信本地兼容或显式 fallback；它从不产生 `SANDBOX_ENFORCED`。
- Container backend 是边缘 Adapter，不进入 Domain/Core，也不成为平台 Sandbox 的隐藏依赖。

## Policy 与优先级

有效策略由不可放宽的 Managed baseline、Host baseline、User 明确收窄和当前 Tool 请求求交集：

```text
Managed deny/required isolation
→ Host hard baseline
→ User policy narrowing
→ Project/local/session narrowing
→ Tool-source/request narrowing
```

低优先级来源不能把 `deny` 改为 `allow`、把 `required sandbox` 改为 Local、注入环境 Secret、增加读写根或扩大网络。Managed 配置缺失时使用 Host baseline；若声明存在但损坏、未知版本或 provenance 不可信，则相关安全要求 Fail Closed。

### File policy

- 默认只读主机；明确可写 Workspace/Worktree 根；`.git`、`.cc-java` 控制面、Settings、Provider 配置、Session/checkpoint/memory/plugin store 与 Secret 路径是保护子路径。
- read/write roots、deny carveout 与临时目录必须基于 canonical identity；链接、junction、mount/reparse、hard-link 和创建时竞态进入攻击 Fixture。
- 应用层 WorkspaceGuard 继续用于 Tool 参数；OS policy 是第二道边界，两者缺一不算 `SEC-06 L2`。

### Process policy

- 禁止提权、新 session/detach 或后台逃逸；后代继承隔离和取消所有权。
- timeout/cancel/shutdown 进入同一幂等清理，Windows 优先 Job Object；Linux/macOS 使用进程组/namespace/平台能力。若无法证明后代约束，probe 不得报告 `ENFORCED`。
- 当前 S04 `ProcessHandle/taskkill` cleanup 保留为 Local fallback，不冒充 sandbox process containment。

### Network policy

- 对 backend 启动的进程默认 deny；允许策略由域名、端口、loopback/private ranges、Unix socket/命名管道和协议模式显式表达。
- 仅设置 `HTTP_PROXY` 不算隔离；Linux/container 证据必须结合 namespace/seccomp/容器网络隔离，使直接 socket 绕过失败。deny 优先，重定向与 DNS 解析每次重检。
- JVM 内 HTTP 不属于该边界。S13 不实现远程 Hook，因此 `HOOK-10` 保持 loopback L1；现有 MCP/Plugin HTTP 也不计入 OS network enforcement，不能借 Sandbox 结果进入 Auto Mode。
- `Sub-Agent` 自身不产生网络；其 `run_command` 进程使用同一 backend。Plugin/MCP stdio transport 的进程受 backend，remote HTTP transport 只保留现有应用层安全契约。

### Environment 与 Secret policy

- 从空环境构造；只注入 backend/平台启动所需和用户显式允许的非 Secret 项。
- Provider key、Bearer、云凭证、Git credential helper、SSH agent/socket、代理凭证与未知 Secret 默认排除；必须给受控组件使用的 Secret 通过独立 secret reference 注入到宿主 Adapter，不进入通用 Command。
- 环境名、值、命令输出、failure 和 EnforcementReport 均执行 sentinel 零泄漏测试。

## fail-closed 与显式 fallback

状态机：

```text
REQUESTED → PROBED → POLICY_COMPILED → BACKEND_SELECTED → STARTING → RUNNING → TERMINAL → CLEANED
                    ↘ UNSUPPORTED / UNAVAILABLE / POLICY_REJECTED
```

- 默认任何 sandbox 初始化/编译/启动失败都返回结构化拒绝，execute count 为 0。
- 可选 fallback 只能在 Tool 执行前出现独立精确审批，展示缺失维度、Local 风险、完整命令、cwd 和一次性范围；`PLAN`、Managed require-sandbox、Hard Denial、Print 无交互均禁止 fallback。
- fallback 只允许当前 Call ID；不能创建 Session-wide “所有命令 unsandboxed” Grant。失败后不得自动循环尝试 Local。
- backend 已启动后发生隔离违规/内部失败，不回退并重放命令，避免副作用重复。

## 所有入口一致

1. `run_command` 用 `ExecutionBackend` 替换当前直接 `LocalCommandExecutor` 启动路径。
2. Sub-Agent/Worktree 只改变 cwd/scope；child `run_command` 使用相同 backend/policy。Worktree 不是策略放宽依据。
3. Plugin/MCP STDIO transport 与 Command Hook 使用同一结构化进程 seam；外部来源不能自带可信 backend。JVM 内 HTTP 明确不在此列。
4. MCP/Plugin/Hook 的 in-process remote 调用不能因进程 Sandbox 自动获批、不能计入 `SEC-07`，继续受原有应用层 Trust/Permission/URL 约束。
5. 固定 argv Git 管理操作属于宿主控制面，不由模型直接生成；它们保持 native host 路径与命令语义，使用最小环境、timeout/cancel/cleanup 和明确 Host-operation policy，不被隐式送入 WSL/container，也不借 Sandbox 绕过 commit/push 授权。

## 实际后端、路径与命令语义

当前 Windows 主机已确认：WSL2 Ubuntu 可发现，Docker CLI 26.1.4 可发现但 daemon 当前不可连接；本机未发现 bwrap/podman。CLI 存在、发行版存在或 PATH 命中都只是 probe 输入，不是可用证据。

### Linux 主路径：Windows-hosted WSL2 Ubuntu + bwrap

- G3 只使用 JDK `ProcessBuilder` 调用固定绝对 `wsl.exe`，选择经过 probe 固定的 WSL2 distribution，再在该 Linux 环境中调用固定解析的 `bwrap`；当前 Ubuntu 未安装 bwrap，因此 Batch B 前置是用户/环境显式安装，程序不得自动下载或提权安装。
- probe 必须验证 distribution version=2、启动可用、Linux 内 `bwrap` 绝对路径不位于映射 Workspace、版本/feature、自包含最小 user/PID/network namespace smoke、只读 host + 显式 writable root、直接 socket deny 和完整取消清理。任一步失败为 `UNAVAILABLE/DEGRADED`，不执行用户命令。
- 该证据分类为 **Linux A（hosted on Windows）**，不是 native Windows A。WSL VM/namespace 内边界不能证明 Windows 主机 ACL/firewall/restricted token。

### Windows cwd/path → WSL 映射

- 只接受本地 fixed drive 的 canonical Workspace（例如 `G:\repo`），通过固定 `wslpath -a` 或等价 JDK 自有转换获得 Linux path，再双向核对：Linux `realpath` 必须位于对应 `/mnt/<drive>/...` 且反向映射回同一 Windows canonical identity。
- UNC、网络盘、subst、device path、case/Unicode 无法无歧义往返、路径含链接/reparse 不确定性、distribution 未挂载 drive 时 probe/plan 拒绝。
- 用户命令不是透明 argv：当前 `run_command` 是平台 Shell 文本，Windows PowerShell/cmd 语义不能安全改写成 `/bin/sh`。只有新增的显式 `LINUX_SH` execution request（审批预览清楚标示 WSL2 distribution、Linux cwd 与 `/bin/sh`）可进入 WSL backend；既有 Windows shell request 必须留 Local/未来 native Windows backend，selector 不得隐式换 shell。

### Container optional backend

- 使用外部已安装 `docker` fixed argv，不新增 SDK 依赖。probe 必须同时验证 daemon、固定 image identity/digest、只读 bind、独立 writable work mount、`--network none`、非 root、cap-drop/no-new-privileges、PID/timeout/cancel 与清理；仅 Docker CLI 无 server 时为 `UNAVAILABLE`。
- Windows path 只通过 Docker 自己接受的 bind 参数传递并以容器内 marker/digest 自测映射；无法证明映射时拒绝。容器命令同样是显式 `LINUX_SH`/结构化 Linux argv，不能透明执行 Windows argv。

### Native Windows 与 macOS

- S13 不承诺实现未经验证的 native Windows ACL/restricted token/firewall backend。可只实现 probe 和既有 Local process cleanup 证据，等级为 B（真实部分维度）/C（契约）/U；Job Object 若仅证明 process ownership，不得冒充 file/network isolation。
- macOS 当前无主机，只允许 C/U。任何后续 Seatbelt 实现必须另有真实主机和兼容证据。
- 新增非测试库依赖必须先有版本/许可证/兼容性 ADR 与真实 spike；本轮设计优先 JDK 21 加已安装/显式前置的外部工具，不引入 sandbox SDK。

## 最多三个实现 Batch

1. **Batch A — Contracts + Local refactor + truthful probe**：Domain/Core 公共契约、policy/provenance/fallback、`LocalExecutionBackend`；收敛 Command/Command Hook/MCP stdio/Sub-Agent 进程 seam；建立 WSL2/bwrap、Docker daemon/image 与 native platform probe，既有行为零回归。
2. **Batch B — WSL2-hosted Linux bwrap A**：固定 `wsl.exe → Ubuntu WSL2 → bwrap`，实现 Windows↔Linux path identity、显式 `LINUX_SH` 语义、file/process/network/env/secret policy、Managed baseline和攻击自测；bwrap 缺失时 fail closed。`HOOK-10` 不在本 Batch 提升。
3. **Batch C — Optional Docker B + attack matrix + integrated Eval**：daemon 可用时 container opt-in L1、跨来源攻击 Fixture、Windows native B/C/U 与 macOS C/U 诚实报告、Demo/Gap、完整 G4-G6 和 commit-scoped 复验。

## G4 验证门槛

### 安全零容忍

- Workspace 外写、deny-read 成功、保护路径修改、网络 deny 绕过、Secret sentinel 泄漏、Permission/Pipeline 旁路、fallback 静默发生、orphan process：全部 `0`。
- 每次进程调用恰一个 backend terminal 与 cleanup；Tool Call ID/durable started/completed 保持准确。
- Sandbox 不可用、策略不可表达、managed 配置损坏、probe 竞态、helper 被 PATH 替换均 execute count `0`。

### 攻击 Fixture

至少覆盖 traversal、symlink/junction/reparse、hard-link、rename/TOCTOU、mount/namespace 可用时、`.git`/Settings/Provider/Session/Plugin 控制面、fork/detach/child spawn、timeout/cancel、直接 TCP/UDP、DNS/redirect/private IP/loopback/Unix socket、代理绕过、环境/文件/进程 argv Secret、恶意 AGENTS/Skill/Plugin/MCP/Sub-Agent 企图改 policy/backend/fallback。

### 跨平台证据分级

| 级别 | 含义 |
| --- | --- |
| A | 真实目标 OS + 真实 backend + 攻击 Fixture 全通过 |
| B | 真实目标 OS + capability/smoke 与部分安全矩阵通过 |
| C | 编译/契约/Fake，仅证明协议，不证明 OS 隔离 |
| U | 未验证或不可用 |

Stage Exit 最低要求：Windows-hosted WSL2 Ubuntu 中的真实 bwrap Linux backend 对 `SEC-04/05/06/07/12` 达到 A；Docker daemon + pinned image 可用时 Container 达到 B（否则 `SEC-08` 保持 L0 且 Stage Exit 阻塞）；native Windows 至少 B（允许只证明真实 process/env/cleanup，file/network 如实 C/U），macOS C/U 并明确 gap。Linux A 不得写成 native Windows A；Fake/编译证据不得升级任何 OS enforcement。

## 被否决方案

- 把 WorkspaceGuard、Permission、Checkpoint、Worktree、进程清理或容器 cwd 称为 Sandbox；
- 仅用命令黑名单、Prompt 或代理环境变量提供安全保证；
- Sandbox 失败后静默执行 Local；
- 为 Command、Plugin/MCP、Hook、Sub-Agent 各建一套互不一致的隔离；
- 让项目 Settings 或 Plugin manifest 选择 helper、扩大 policy 或注入 Secret；
- 用 Docker-only 代替本机平台隔离，或把容器存在性作为普通 CLI 前提；
- 在没有真实 OS 证据时把 Capability 提升到 L2/L3。

## Gate 结论

## Gate 结论（2026-08-10）

S13 已在实现 Commit `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3` 上完成 Batch A-C 与 Commit-scoped G0-G6，Stage Exit Accepted。标准 clean verify 为 851 tests/29 skips（0 failure/error），TUI 133/133、launcher 59 assertions，真实 selector 5/5 + attack 8/8 共 13/13。首次真实测试因 Docker daemon 未运行导致 5 个 Docker 用例失败；启动 Docker Desktop、确认 daemon 26.1.4 后完整通过，测试后 label residue 为 0。

等级只按实际证据更新：`SEC-02/03/04/05/06/07/12`、`EVAL-04` 为 L2，`SEC-08` 为 L1；`PERM-08/09/12`、`SEC-09` 保持 L2，`PERM-05/CFG-07` 保持 L0，`HOOK-10` 保持 L1，`SEC-11` 保持 L0。JVM 内 HTTP、native Windows file/network、macOS 真实隔离、Managed/Auto 生产接入与供应链安全继续是明确 gap。S14 为 NOT_STARTED。
