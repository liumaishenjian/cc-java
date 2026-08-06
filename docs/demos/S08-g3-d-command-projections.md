# S08 G3-D：受限命令投影演示

> Stage：S08 G3-D 局部实现
>
> Feature：`CTX-12`、`CTX-13`、`CLI-08`、`MODEL-08`、`PERM-06`、`PERM-12`
>
> 参考：公开行为基线 `R2026.03`；授权参考源码：`N/A - Not Used`（本切片仅落实已冻结 ADR-046/047 契约）
>
> 状态：实现与离线协议测试完成；G3-G6、Capability Level 与 S08 Stage Exit 仍 Open。

## 可复现验证

```text
.\mvnw.cmd -pl cc-java-cli -am -Dtest=HeadlessRuntimeSessionTest,SessionCommandDispatcherTest,StdioProtocolCodecTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix cc-java-tui test -- --run
```

## 已验证行为

1. `session.command` 只接受封闭 intent、严格 arguments、无 Run 关联及最多 128 code point 的非控制字符 `commandId`；重复 ID 复用同一终态，有界 budget 耗尽拒绝新的 ID。
2. `/compact [anchor...]` 最多接受 16 个、每项最多 512 code point 的无控制字符锚点。它先执行 S07 C1/C2；即使预算已满足仍可经原 C3/C4 Gate 尝试摘要。成功候选不改 Canonical Transcript、JSONL 或 Checkpoint，只在 Canonical 前缀未变化时一次性用于**下一 Run 的首个模型请求**。
3. `/context` 只投影 latest `ContextUsageView` 的 token 数值、状态/策略/原因枚举；stdio Client 对未知字段、Secret 泄漏、越界数字、错误 session/run 关联和非法结果状态 fail closed。
4. React/Ink Slash parser 与 Java codec 对 compact anchors、mode、commandId 上限及控制字符使用一致的封闭输入边界；本地渲染仅使用固定状态代码，不显示服务端自由文本。

## 保持的不变量

- 显式 compact 不覆盖整个下一 Run；消费后仍由既有 `ContextPreparationService.prepare` 为每个模型回合执行 S07 自动 C1-C4 reduction 与 typed-overflow 至多一次恢复。
- Canonical Transcript、Tool Call/Result 批次配对、取消、S05 Permission Pipeline、S06 Recovery Gate 和 ready-only Memory 路径不因命令投影改变。
- 没有持久 Settings、规则/selector 编辑、Provider discovery、多模型注册、热 resume、完整 Ink 输入/历史/补全/steering，也没有 S12 Worktree 或 S13 OS Sandbox。
