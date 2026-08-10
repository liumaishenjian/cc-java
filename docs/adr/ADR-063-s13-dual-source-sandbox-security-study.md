# ADR-063：S13 Sandbox + Security 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-10
- Stage: S13 Sandbox + Security（G0-G2）
- Feature IDs: `SEC-02..09/11/12`、`PERM-05/08/09/12`、`CFG-07`、`HOOK-10`、`EVAL-04`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenAI Codex `rust-v0.147.0`，annotated tag `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d` → Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Classification: 授权快照为 `Observed / Inferred / Unknown`；Codex 固定公开源码为 `Documented / Observed`；采纳边界为本项目 `Documented`

## 背景与来源边界

S03-S12 已建立 WorkspaceGuard、命令 timeout/cancel、权限/审批、Settings/Trust、Hook、MCP、Plugin、Sub-Agent 与 Worktree，但这些应用层控制仍在当前用户账户下运行，不能限制一个已经获准启动的进程继续读取主机文件、访问网络或派生后代。S13 必须增加可证伪的 OS 执行隔离，而不是把既有控制重新命名为 Sandbox。

本轮按 ADR-022 在仓库外只读研究 `G:\AI Cloud\claude-code-main`。只抽象职责、状态、边界、恢复和验证；未复制函数体、Prompt、注释、错误文案、私有名称、布局或常量。授权快照的准确 Revision、License、权利人与公开再使用权继续为 `Unknown`。

官方 Codex clone 位于仓库外 `G:\AI Cloud\codex-rust-v0.147.0`。2026-08-10 通过 `git ls-remote` 核验 tag object 与 peeled commit，并以 detached HEAD 检查固定版本。公开源码按其许可证使用，但本项目仍采用独立 Java 契约、命名、策略与 Fixture，不复制实现表达。

## 双源研究结论

| 机制 | 授权快照 | Codex 0.147.0 | cc-java 采纳 |
| --- | --- | --- | --- |
| Sandbox 选择、策略解析、平台执行与故障诊断是分离职责 | Observed | Observed | `PlatformCapabilityProbe → ExecutionBackendSelector → ExecutionBackend` |
| Permission/Approval 决定“是否可尝试”，Sandbox 决定“获准进程实际上能做什么” | Observed | Observed | 两者保持正交；Sandbox 不跳过唯一 Pipeline |
| 文件、网络、进程、环境/Secret 是独立策略维度 | Observed | Observed | 四类不可变 policy，统一编译成 backend plan |
| 平台能力不等价：Linux、macOS、Windows 使用不同原语且可表达集合不同 | Observed / Inferred | Observed | probe 报告 `ENFORCED/DEGRADED/UNAVAILABLE/UNKNOWN`，不得按 OS 名猜测 |
| 无法完整表达策略时必须拒绝或经过显式 fallback；静默无隔离执行是安全缺陷 | Observed | Observed | 默认 fail-closed；只有调用前单次、精确审批的 Local fallback |
| Linux 文件默认只读、显式可写根、PID/network namespace 与 seccomp 可组合 | Inferred | Observed | 作为 Linux Adapter 验证目标，不照搬 helper/argv/常量 |
| macOS 可将文件与网络规则编译给系统 sandbox，网络代理可进一步限制域名/方法 | Inferred | Observed | 作为 macOS capability/证据目标，首轮实现受实际 CI 主机约束 |
| Windows restricted identity/token、ACL 与进程 Job 的能力边界需要显式探测 | Inferred | Observed | Windows Adapter 必须证明文件/进程/网络各维度；不支持的组合拒绝 |
| 网络仅设置代理环境变量不能阻止绕过；需要 OS egress 限制与代理策略共同成立 | Observed / Inferred | Observed | `NetworkPolicy` 的 enforceable 结果必须由 probe/backend 证明 |
| Worktree/Plugin/MCP/Hook/Sub-Agent 的命令最终需要同一 backend seam | Observed / Inferred | Observed | 所有本地进程请求经统一执行后端；来源只参与 policy narrowing |

## 固定公开源码观察

Codex 固定版本显示：平台选择与 permission profile 分离；Linux 默认使用 bubblewrap 路径并保留显式 legacy fallback，包含只读根、可写根重挂载、保护子路径、PID/user/network namespace 与 seccomp；macOS 使用固定系统 sandbox executable，将文件读写根、保护子路径、网络/Unix socket策略编译后执行；Windows 存在 restricted-token/elevated 能力分支，策略无法表达时明确拒绝 unsandboxed；受管网络使用本地代理并保持 deny-wins、allowlist-first 和本地地址保护。以上仅是固定公开源码的机制观察，不等于 cc-java 已实现或跨平台等价。

## 采纳、偏离与 Unknown

### 采纳

1. 可替换 `ExecutionBackend`，至少 Local 与平台 Sandbox；Container 为可选后端。
2. 明确 capability probe、策略编译、执行、取消/timeout/进程树清理、诊断与攻击 Fixture。
3. Sandbox 默认 fail-closed；backend 缺失、策略不可表达、初始化失败、身份漂移或执行计划不一致均不静默 Local。
4. Permission/Approval 先于 backend；backend 选择与实际策略摘要进入精确审批和 durable 安全事实，但不能把 Sandbox 成功当成 Permission Allow。
5. Command、Sub-Agent、Plugin/MCP stdio 与 Command Hook 的本地进程入口复用同一 backend seam。`ExecutionBackend` 不能约束当前 JVM 内 HTTP；远程 Hook 保持 L1，in-process MCP/Plugin remote 不计入 S13 OS network 证据，未来须经独立 `PolicyAwareNetworkAccessPort`/egress broker 才能形成强制边界。

### 有意偏离与延期

- 不兼容参考私有配置、策略 schema、helper 协议、错误文案或内部事件。
- `SEC-08 Container Backend` 在 S13 只达到 L1：冻结可选 Adapter/探测契约并用显式 opt-in smoke 证明一个容器运行时；它不是默认正确性前提。
- `PERM-05 Auto Mode` 只达到 L1：仅允许“Sandbox 已证明完整强制 + policy 明确允许”的确定性免询问骨架；不实现模型分类器或全自动高风险执行。
- `CFG-07 Managed Policy` 只达到 L1：只冻结不可由 user/project/session 降级的 Sandbox 安全底线与 provenance；企业分发、签名、迁移留 S14。
- `SEC-11` 保持 L0；OS Sandbox 不能证明 Plugin 作者身份、签名、SBOM、撤销或供应链安全。
- `PERM-08/09/12` 与 `SEC-09` 已是 L2，S13 只增加 OS 隔离组合回归，不擅自升 L3。
- 当前可执行路线冻结为 Windows-hosted WSL2 Ubuntu 内的 Linux bwrap backend（需显式安装 bwrap）与 Docker daemon 可用时的 optional Container；本机只有 Docker CLI、daemon 不可连接，不能算 Container B。
- WSL2 Linux A 不等于 native Windows A。native Windows 本轮只要求真实 B（process/env/cleanup）并对 file/network 报 C/U；macOS 无主机只报 C/U。
- Windows PowerShell/cmd 不能隐式翻译为 Linux shell；只有明确审批为 `LINUX_SH` 且 Windows↔WSL/container path identity 可双向证明的 request 才进入这些 backend。
- `HOOK-10` 保持 L1；OAuth、稳定外部协议、remote network port/broker、远程 worker、Marketplace、签名与跨版本迁移仍按 S14/S15 推进。

## Unknown

- 授权快照准确发行版及其外部 sandbox runtime 的完整实现与许可证边界；
- 三个平台在全部文件系统、企业策略、容器嵌套、网络/VPN/代理和管理员配置下的等价保证；
- Windows 在无管理员权限下可可靠表达的 deny-read、网络 egress 与私有桌面上限；
- Linux user namespace 被禁用、rootless container、WSL1/WSL2 与受限 CI runner 的完整行为；
- macOS 系统 sandbox 接口的长期兼容承诺；
- DNS rebinding、Unix socket、本机服务、命名管道和内核漏洞等超出首轮 S13 threat model 的保证。

Unknown 不进入默认放行、常量或测试 Oracle。

## 停止条件

授权撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要把参考字节带入仓库时立即停止授权材料研究。公开 Codex tag/commit 不匹配、许可证边界变化或固定版本无法复验时暂停该来源的结论升级。本 ADR 只完成研究与采纳边界，不提升 Capability Level。