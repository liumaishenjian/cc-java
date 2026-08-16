# S15 PERM-05 Permission Auto Review Evidence

- Date: 2026-08-17
- Stage: S15 IN_PROGRESS / Stage Exit OPEN
- Feature: `PERM-05 L0 → L1`
- Implementation baseline: 当前工作树，待本次提交固定
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: `Observed / Inferred`（双源机制）；`Documented / Tested`（项目实现）

## 实现证据

- Domain：`PermissionSelection` 将 PLAN/ASK/AUTO 精确映射到 mode/reviewer；
- Core：final-ASK-only coordinator、Run-owned circuit、共享取消、失败关闭与 batch typed stop；
- Adapter/Headless：复用当前 `ModelGateway` 的独立空 Tool 复核回合；
- Application/stdio：事务化下一 Run 配置、严格 selection 输入和三字段查询投影；
- React/Ink：固定三项 picker、ASK 默认、导航、单次 Enter、Esc 零发送；
- 文档：ADR-072/073、PRD、技术设计、矩阵、Demo 与 Gap Report 同步。

## 自动验证

1. 聚焦 Java 闭环：178 tests / 1 skip / 0 failures / 0 errors；
2. 完整 `./mvnw.cmd clean verify`：175 份 Surefire XML，1058 tests / 32 skips /
   0 failures / 0 errors；
3. 严格 aggregate Javadoc：BUILD SUCCESS，0 warning；
4. `npm --prefix cc-java-tui run check`：13 files，195/195；
5. `TestCodejDevLauncher.ps1`：60 assertions；
6. `TestBuildRelease.ps1`：77 Maven components，4804 checksums；
7. Windows x64 distribution package + install/uninstall lifecycle：PASS，`codej 0.1.0`；
8. Progress Dashboard generate/check/self-test：在本证据提交前执行并记录到看板。

安装生命周期首次直接调用因 archive 尚未生成而按预期报 `Distribution archive missing`；随后先运行
`PackageDistribution.ps1 -Version 0.1.0 -Platform windows-x64 -SkipBuild -SkipTuiBuild`，再执行安装测试，
完整 install/version/doctor/tampered-checksum/uninstall 生命周期通过。该前置条件错误不是产品测试失败。

## E2E 观察

- AUTO Fake allow：Headless 先读后 patch 成功，交互 ApprovalHandler 零调用；
- AUTO Fake deny：Workspace 不变，形成匹配 Call ID 的拒绝结果；
- stdio：selection 变更经真实 Handler → Dispatcher → Settings → Runtime Scope 后查询一致；
- TUI：`/permissions` 不进入模型 Prompt，三个标签精确，快速双 Enter 只产生一个 AUTO wire command；
- 取消：只有共享 Run token 已取消才进入 Run cancellation，伪造 cancel 信号失败关闭。

## Level 与剩余边界

上述证据证明可运行、可测试的生产接线，支持 `PERM-05 L1`。它未提供真实 Provider 的误放行率、
误拒绝率、提示注入、语义摘要质量、延迟、成本或 A/B Eval，因此不支持 L2。S15 仍因该质量证据、
MODEL-13 双 Provider 在线证据与 L4 创新 Eval 未完成而保持 IN_PROGRESS/OPEN。
