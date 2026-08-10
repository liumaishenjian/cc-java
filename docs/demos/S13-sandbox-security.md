# S13 Sandbox + Security Demo（Skeleton）

> 当前状态：G0-G2 已冻结，G3-G5 尚未执行。本文件不是实现或通过证据。

## 前置条件

- 实现 Commit 固定；Workspace 使用独立公开攻击 Fixture；无真实 Secret。
- 目标主机 capability probe 报告 backend 与每个 enforcement dimension。
- Windows-hosted WSL2 Ubuntu 中显式安装并通过 probe 的 bwrap；Docker daemon + pinned image 显式启用。仅 WSL2/Docker CLI 存在不算可用。

## 预定场景

1. 相同命令在 Local 对照可观察到目标，而 Sandbox 对 Workspace 外写、deny-read、保护路径修改均拒绝。
2. symlink/junction/reparse/hard-link/rename 竞态不能越过文件 policy。
3. 直接 TCP/UDP、代理绕过、redirect、DNS/private/loopback/Unix socket 按 NetworkPolicy 拒绝或允许。
4. child/fork/detach 后 timeout/cancel/shutdown 无 orphan。
5. 环境、文件、argv、stdout/stderr、Event/Journal/Report 中 Secret sentinel 为 0。
6. Command、Sub-Agent、Plugin/MCP stdio、Command Hook 使用相同进程 backend；JVM 内 Hook/MCP HTTP 明确不在其中且不计网络隔离证据。
7. Windows PowerShell request 不能隐式换成 Linux shell；只有审批明确显示 WSL2/container、Linux cwd 和 `LINUX_SH` 的调用才可执行。
8. Windows fixed-drive path 双向映射失败、UNC/网络盘/reparse 不确定、bwrap 缺失、Docker daemon/image 不可用时 execute count 为 0；当前 Call ID 的显式 Local fallback 正负例。
9. Managed baseline 不能被 user/project/session/Plugin/Agent 文本放宽。

## 预定命令

```powershell
.\mvnw.cmd clean verify
# 后续由实现固定跨平台安全矩阵 runner；当前不存在，不得伪造命令。
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

## 预期观察点

- WSL2 Ubuntu+bwrap 为 Linux A；Docker daemon+pinned image 为 Container B；native Windows 为 B（file/network C/U 如实列出），macOS C/U。Linux A 不冒充 native Windows A。
- 越权、旁路、静默 fallback、Secret 泄漏和 orphan 全为 0。
- 每次调用有唯一 backend terminal/cleanup，Tool Call ID 与 durable started/completed 保持配对。

## 实际结果

`OPEN — 等待 Batch A-C 实现与 commit-scoped G4/G5。`

## 事实边界

Permission、Checkpoint、Worktree、Job cleanup、最小环境和 Local backend 均不等于 Sandbox。没有真实 OS 攻击证据时不得提升 `SEC-06/07/12/EVAL-04`。