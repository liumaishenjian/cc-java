# ADR-071：S14 可安装 CLI 发行闭环

- Status: Accepted
- Date: 2026-08-16
- Stage: S14 Production Harness corrective maintenance（S15 期间补齐）
- Feature IDs: `BOOT-01`、`DIST-02`、`DIST-06`
- Current → Target: `L2 → L2`（行为增强，Capability Level 不变化）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`

## 背景

S14 已生成 Java Headless app-dir、checksum、SBOM 和 Windows/Linux launcher，但该 launcher
只转发到要求显式选择 `--print/--stdio` 的 Java 入口。它没有交付用户所理解的 `codej`：无参数
进入交互 TUI、`--print` 非交互运行、Provider 管理、版本诊断，以及可验证的用户级安装/升级/卸载。
因此原候选可以作为 Headless 证据，不能直接支撑官网安装命令。

本 corrective 仅使用项目现有协议和公开发行行为设计，不读取授权参考快照。公开对照包括
OpenAI Codex README/installer 与 Anthropic Claude Code setup（访问日期 2026-08-16）：二者均把
平台 artifact、固定入口、checksum/安装脚本和升级渠道分离。这里只采纳可独立表达的发行职责，
不复制脚本文案、文件布局、常量或内部协议。

- https://github.com/openai/codex/blob/main/README.md
- https://docs.anthropic.com/en/docs/claude-code/setup
- https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases

## 决策

1. 发行物同时包含 Java app-dir、编译后的 Ink TUI、生产 npm 依赖和产品启动器。
2. `codej` 无参数默认进入 TUI；`--print` 仍经 TUI 的非交互桥接调用同一 Java stdio/Runtime；
   `auth/providers/models` 与 stable/headless 模式直接进入同一 Java Composition Root。
3. launcher 优先使用发行物内的 Java 21 与 Node 22；本地候选允许回退到系统运行时。GitHub Actions
   的平台包必须携带两个运行时，最终用户不需要预装 Java、Node、Maven 或 npm。
4. Windows ZIP 与 Linux tar.gz 使用固定 asset 名；独立 `.sha256` 在解压前验证。内部 manifest、
   SHA256SUMS 与 CycloneDX SBOM 继续覆盖 app-dir，其中 SBOM 增加 TUI 直接运行依赖。
5. 安装采用 `versions/<version>`、原子 `current.txt` 和稳定 shim。新版本完整校验、解压和发布后才
   切换 current；旧版本保留为 LKG。卸载只删除拥有标记的 shim 和 codej 安装根。
6. tag/workflow_dispatch 共用矩阵构建。维护者于 2026-08-16 选择 Apache-2.0；只有 tag 构建且
   仓库存在该 `LICENSE` 时，`PublicRelease` 才能为 true 并创建 GitHub Release。

## 安全与失败语义

- archive checksum 不匹配、manifest schema/platform/version 不匹配、路径或运行时缺失均在激活前失败；
- 安装器不接受 archive 内的安装目标，也不从文件名推断版本；
- Windows 用户 PATH 只追加用户级 bin，不修改系统 PATH；卸载保守保留 PATH 条目；
- `codej update` 重新调用同一签名/校验入口，不在 Node launcher 中实现第二套更新协议；
- 当前 checksum 解决传输完整性，不等于 publisher identity。签名、撤销和透明日志仍是 gap。

## 验证与等级纪律

Windows 本地候选必须通过 build、`--version/--help/doctor`、archive checksum、安装、从 shim 启动、
篡改 checksum Fail Closed 和卸载。Linux 在 CI 构建自包含 artifact；未得到真实 runner 结果前不记
本地 PASS。由于尚无公开 Release 与真实 N-1 artifact，`DIST-02/DIST-06` 保持 L2，S14 Accepted
状态不变，S15 Gate/Capability 也不因此变化。
