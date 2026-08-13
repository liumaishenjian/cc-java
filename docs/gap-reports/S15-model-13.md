# S15 MODEL-13 Provider BYOK 差距报告

- Date: 2026-08-14
- Reference Baseline: R2026.03
- Authorized Snapshot: AUTH-SRC-2026-07-29-A
- Stage: S15 IN_PROGRESS / Exit OPEN
- Capability advanced: MODEL-13 L0 → L1

## 当前已完成

`MODEL-13` 当前达到 **L1**。本地 Provider 凭证控制已具备以下实现骨架：

1. 本地 restricted store，用于受限保存 Provider 凭证。
2. Provider 配置 surface 与 factory 接线。
3. `run`、`probe`、`logout` 控制流程。
4. `modelOverrides`，用于向单次运行显式传递模型覆盖配置。

这些能力证明本地 BYOK 配置、选择和生命周期控制已经形成可运行切片，但尚不足以证明多 Provider 的真实端到端兼容性。

## 未达到 L2 的关键证据

尚未完成至少两个 **distinct Provider** 的真实 BYOK 端到端验证，且每个 Provider 均需覆盖：

- text stream；
- Tool call；
- cancel；
- auth-negative。

在上述证据齐备前，`MODEL-13` **不得提升至 L2**。本地 store、配置 surface、factory、命令控制或离线测试均不能替代两个不同真实 Provider 的在线行为证据。

## 仍有差距

1. **Remote model sync**：没有远程模型配置同步、冲突处理、信任边界和恢复证据。
2. **跨平台 selector / NFS**：没有 Windows、Linux、macOS 以及 NFS 等共享文件系统上的 selector、一致性和并发语义验证。
3. **跨进程 active revoke**：凭证被使用期间，尚不能证明另一进程发起的 revoke 会立即、可靠地使活动使用失效。
4. **Remote revoke**：没有 Provider 远端凭证吊销、状态确认和失败恢复闭环。
5. **OAuth**：未实现 OAuth 授权、刷新、撤销和账户生命周期。
6. **OS vault**：未接入 Windows Credential Manager、macOS Keychain、Linux Secret Service 等操作系统凭证库。
7. **第三方 SDK `String` 清零**：JVM `String` 不可变，进入第三方 SDK 后无法证明敏感字符串可被确定性清零。
8. **长期兼容**：缺少跨版本、跨 Provider SDK、凭证格式迁移及长期重复运行的兼容性证据。

## S15 结论

`MODEL-13` 是参考能力差距补齐，**不是 L4 独立创新**。当前只能声明 `MODEL-13 L1`；既不能因该本地切片宣称 L2，也不能据此满足 S15 的 L4 创新退出条件。**S15 Stage Exit 保持 OPEN。**
