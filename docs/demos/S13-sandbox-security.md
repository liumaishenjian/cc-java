# S13 Sandbox + Security Demo（G3-G5 Candidate）

## 前置条件

- Windows host，Ubuntu WSL2，`/usr/bin/bwrap` 0.4.0。
- Docker daemon 可用，已存在 pinned `nginx@sha256:0d17b565c37bcbd895e9d92315a05c1c3c9a29f762b011a10c54a66cd53c9b31`。
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
```

## 实际结果（2026-08-10）

- WSL2+bwrap truthful probe 与攻击：通过；只读 `/etc`、显式 Workspace、network namespace、空环境。
- Docker daemon + pinned image：通过；非 root、read-only root、network none、cap-drop、no-new-privileges。
- 标准离线 clean Maven 851 tests/10 skips；focused real 13/13、0 skip（real backend 8/8 + selector 5/5）；TUI 133/133；launcher 59/59。
- 越权、静默 fallback、Secret sentinel、orphan：0。

## 负例

- Windows platform shell + Sandbox/Container selector 被 `SHELL_SEMANTICS_MISMATCH` 拒绝；只有明确 `LINUX_SH` 可进入。
- require-isolation 时 capability 不完整即拒绝；不允许 Local fallback。
- fallback approval 的 Call ID 不匹配即拒绝。
- native Windows file/network 只报告 UNKNOWN，不借 WSL2 Linux A 冒充 Windows A。

## 事实边界

Linux A 是 Windows-hosted WSL2 Ubuntu 证据；Container 是 B；native Windows 是 process/env/cleanup B 且 file/network U；macOS C/U。JVM 内 HTTP/MCP remote 不受进程 backend 强制，HOOK-10 保持 L1。Command Hook/MCP stdio 已复用 managed fixed-argv seam，Sub-Agent run_command 继承父配置；没有 implementation Commit，因此 Stage Exit/G6 仍 Open。
