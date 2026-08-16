# S14 可安装 codej CLI corrective evidence

- Date: 2026-08-16
- Commit: working tree（不得解释为已发布 artifact）
- Stage: S14 corrective maintenance during S15
- Feature IDs: `BOOT-01`、`DIST-02`、`DIST-06`
- Capability Level: L2，no level change

## Windows 实际结果

```text
BuildRelease.ps1 installable app-dir: PASS
codej --version: codej 0.1.0-SNAPSHOT
codej --help: PASS
codej doctor: files/runtime PASS
PackageDistribution.ps1 0.1.0 windows-x64 public release candidate: PASS
TestInstallDistribution.ps1: install/shim/version/doctor/tamper rejection/uninstall PASS
self-contained Windows ZIP: 195,857,096 bytes; external SHA-256 d04f9d459b4dc2fa1c818879c046f7f51044169d127b6785f6e3309c889bfdfd
system PATH without Java/Node + JAVA_HOME removed: bundled Node v24.14.0 / bundled Java 21 doctor and version PASS
Maven 0.1.0 verify: 1,012 tests / 32 skips / 0 failures / 0 errors
TUI 0.1.0 check: 11 files / 194 tests PASS
Apache-2.0 explicit public release manifest gate: PASS
```

构建出的产品入口包含 Java app-dir、编译 TUI、41 个 production npm package、manifest、
内部 checksum、CycloneDX SBOM、安装器与 launchers。安装测试仅在仓库 `target/release` 下的隔离根
执行，并使用 `SkipPathUpdate`，没有修改用户 PATH。最终候选还携带 jlink Java 21 runtime 与
Node executable；清除 `JAVA_HOME` 且 PATH 不含 Java/Node 后仍从安装目录启动。篡改 `.sha256`
后安装在解压/激活前失败。

## 未计为通过

- GitHub Actions Windows/Linux 自包含运行时矩阵尚未在远端运行；
- 维护者已选择 Apache-2.0；显式 public release gate 本地验证通过，tag CI 结果待对账；
- 没有创建 tag、GitHub Release、网站下载端点或 N-1 artifact；
- 没有 macOS、签名、撤销、透明日志或自动后台更新。
