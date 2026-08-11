# S13 Sandbox + Security Demo（Accepted）

- Implementation Commit: `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3`
- Date: 2026-08-10
- Result: G5 PASSED / Stage Exit Accepted

## 前置条件

- Windows host，Ubuntu WSL2，`/usr/bin/bwrap` 0.4.0。
- Docker Desktop daemon 已启动；验证环境 daemon 为 26.1.4。
- 已存在 pinned `nginx@sha256:0d17b565c37bcbd895e9d92315a05c1c3c9a29f762b011a10c54a66cd53c9b31`。
- 本 Demo 不下载、安装、提权、commit 或 push。

## 命令

```powershell
$env:CC_JAVA_S13_REAL_BACKENDS='true'
$env:CC_JAVA_S13_DOCKER_IMAGE='nginx@sha256:0d17b565c37bcbd895e9d92315a05c1c3c9a29f762b011a10c54a66cd53c9b31'
.\mvnw.cmd -pl cc-java-tools-local -am `
  '-Dtest=ExecutionBackendSelectorTest,S13RealBackendAttackTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

.\mvnw.cmd clean verify
npm --prefix cc-java-tui test
pwsh -NoProfile -File scripts/TestCodejDevLauncher.ps1
java scripts/ProgressDashboard.java
java scripts/ProgressDashboard.java --check
java scripts/ProgressDashboard.java --self-test
git diff --check
```

## 实际结果

| 验证 | 结果 |
| --- | --- |
| WSL2+bwrap | Linux A；file/process/network/env/secret 攻击回归通过 |
| Docker | Container B；非 root、read-only、network none、cap-drop、no-new-privileges |
| 真实测试 | selector 5/5 + attack 8/8 = 13/13，0 skip/failure/error |
| Maven | 851 tests、29 skips、0 failure/error |
| TUI | 133/133 |
| Launcher | 59 assertions |
| Docker residue | `label=cc-java.s13=true` 为 0 |

模块精确汇总：domain 53/0、core 238/0、model 45/2、tools 175/16、mcp 13/0、cli 327/11。

## 环境前置条件与恢复记录

第一次真实测试时 Docker daemon 未运行，5 个 Docker 用例失败。启动 Docker Desktop并确认 daemon 26.1.4 后，使用同一完整真实测试命令重跑，13/13 全部通过。第一次失败属于环境前置条件未满足，不计入通过证据；最终证据没有跳过 Docker 用例。

## 负例

- Windows platform shell + Sandbox/Container selector 被 `SHELL_SEMANTICS_MISMATCH` 拒绝；只有明确 `LINUX_SH` 可进入。
- require-isolation 时 capability 不完整即拒绝；不允许静默 Local fallback。
- fallback approval 的 Call ID 不匹配即拒绝。
- native Windows file/network 只报告 UNKNOWN，不借 WSL2 Linux A 冒充 Windows A。
- Workspace 外写、保护路径修改、直接网络绕过、Secret sentinel 泄漏和 orphan process 均为 0。

## 事实边界

Linux A 是 Windows-hosted WSL2 Ubuntu 证据；Container 是 B；native Windows 是 process/env/cleanup B 且 file/network U；macOS C/U。JVM 内 HTTP/MCP remote 不受进程 backend 强制，`HOOK-10` 保持 L1。Command Hook/MCP stdio 已复用 managed fixed-argv seam，root/child `run_command` 继承执行配置。

在 S13 验收时，`PERM-05`、`CFG-07` 因未生产接入保持 L0，`SEC-11` 保持 L0，S14 尚未开始；这是历史证据边界。S14 后续已在实现 Commit `dff814c1bb5a659979e007061e6d10a0a9ff6e82` 上 Accepted with documented deviations，但本 Demo 仍不证明 S14 Managed Policy、Auto Mode、全平台同等级 OS Sandbox、供应链签名或稳定外部协议。
