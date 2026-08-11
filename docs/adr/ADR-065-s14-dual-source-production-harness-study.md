# ADR-065：S14 Production Harness 双源机制研究与采纳边界

- Status: Accepted
- Date: 2026-08-10
- Stage: S14 Production Harness（G0-G2）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Public Source Snapshot: OpenAI Codex `rust-v0.147.0`，annotated tag `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d` → Commit `be6e8eac029b183056b7e4402879f15d2c85f61b`
- Feature IDs: `CLI-11/12`、`LOOP-12`、`MODEL-07/09/10/11/12`、`SEC-10`、`CTX-16`、`CFG-07/10/11`、`SESSION-08/12/13/14`、`PLUGIN-04/05`、`OBS-04/06`、`EVAL-03`、`DIST-02..06`

## 背景与来源边界

S13 已在实现 Commit `8a75d5f5e977ce4c5fcd19fafb3e5776a5ec2bf3` 上 Accepted。S14 要把已有 Runtime、Session、Permission、Context、Extension 与执行后端组合成可嵌入、可观测、可迁移和可发行的生产 Harness，而不是建立第二套 Agent Loop 或复制参考 wire/schema。

本轮按 ADR-022 在仓库外只读研究 `G:\AI Cloud\claude-code-main`，并复核公开 `G:\AI Cloud\codex-rust-v0.147.0` 的固定 tag/commit。只提炼职责、状态、不变量、失败恢复和验证方法；未复制或翻译函数体、Prompt、注释、错误文案、私有类型、文件布局、内部格式或实现常量。授权快照 Revision、License、权利人与公开再使用权继续为 `Unknown`；参考字节不进入仓库、依赖、Fixture、Golden Output 或发行物。

## 双源机制结论

| 机制 | 授权快照 | Codex 0.147.0 | cc-java 采纳 |
| --- | --- | --- | --- |
| Provider 请求、能力、重试、Fallback、Usage/Cost 是分离职责 | Observed | Observed | capability snapshot + provider router + shared budget |
| Fallback 必须避免重复用户可见文本和副作用意图 | Observed / Inferred | Observed | 仅在无 visible delta、无 durable assistant/tool intent 前切换 |
| Prompt cache/context editing 是 Adapter 优化，不是正确性前提 | Observed | Observed | 与 S07 通用 Projection A/B，对不利场景默认关闭 |
| 生产遥测由控制流事件派生，正文导出需要独立隐私策略 | Observed | Observed | 独立 OTel Adapter、字段白名单、默认 No-op |
| 稳定 Client 边界需要 initialize/negotiation、关联、顺序、取消和唯一终态 | Observed | Observed | 项目自有 v1 envelope；v0/v1 双栈至少一个 release |
| 长生命周期本机服务需要 ownership、token、drain 与 disconnect fence | Observed | Observed | loopback-only daemon，单实例、有界 graceful shutdown |
| Session canonical journal 与索引/Export/Retention/Migration 分离 | Observed | Observed | JSONL 仍是事实源；Index 可重建；迁移 journal/staging/verify/publish |
| Managed policy、feature gates 与 extension lifecycle 需要 provenance/LKG/事务恢复 | Observed | Observed | 本机管理员来源只能收窄；安全项无可信 LKG 时 fail-closed |
| 发行升级需要 manifest、checksum、SBOM、staging 与 rollback | Observed / Inferred | Observed | 本地/CI artifact；License 未决不公开 Release |

## Observed / Inferred / Unknown

- **Observed**：参考机制把 Provider capability、错误恢复、遥测、稳定应用协议、会话索引和更新生命周期分别管理；控制面与正文平面分离；执行前与执行后失败具有不同恢复语义。
- **Inferred**：Java 实现应通过纯 Port 把网络、OTel、协议、存储和发行保持在边缘；稳定 v1 应复用一个 Application Service，而不是从 stdio/daemon/SDK 分叉 Runtime。
- **Unknown**：授权快照准确发行版及内部协议兼容期、云控制面治理、签名根/撤销、全部 Provider cache/context-editing 质量、真实 N/N-1 artifacts 与跨平台安装长期保证。

Unknown 不进入默认放行、价格、协议兼容或 Capability Level。

## 采纳范围与等级冻结

目标 L2：`CLI-11/12`、`LOOP-12`、`MODEL-07/09/10/11/12`、`SEC-10`、`CTX-16`、`CFG-07/10/11`、`SESSION-08/12/13`、`PLUGIN-04`、`OBS-04/06`、`EVAL-03`、`DIST-02..06`。`SESSION-14` 仅实现 SessionIndex Port、普通文件/内存 projection 与 10k benchmark 至 L1；`PLUGIN-05` 仅实现 signature envelope/verification port 至 L1。

`OBS-02/03/05`、`EVAL-01` 只有真实重复双 Provider/Win+Linux 达到预冻结阈值才可 L3，否则保持 L2；`CLI-11` 首个 v1 最多 L2，只有真实 N/N-1 已发布 artifact 才可 L3。`TOOL-17/18`、`PLUGIN-06`、`SUB-11` 延期。

## 安全边界

- 模型、仓库、Provider、协议 Client、OTel exporter、Session/Plugin/Policy 文件和发行输入均不可信。
- 新 Provider、OTel exporter、managed HTTP 的出站创建与执行必须经过 `NetworkAccessPort` 或受控 client factory；这仍是应用层审计/策略，不是 OS Sandbox。
- Prompt、Completion、Tool 参数/结果、正文、绝对路径、Secret sentinel 默认不得进入观测出口。
- Managed Policy 只能收窄。声明存在但未知 schema、不可信 provenance 或安全项无可信 LKG 时 Fail Closed。
- Stable v1 使用每次进程启动高熵 capability token；只绑定 loopback 不替代 token、单实例 ownership 或输入上限。
- Export 默认 metadata-only；正文 opt-in 必须脱敏和显式确认。永久删除拒绝 active/uncertain/incomplete-side-effect/migrating，并要求二次确认。

## Batch C 补充机制研究（2026-08-11）

按 ADR-022 重新只读核对 `G:\AI Cloud\claude-code-main` 的 Provider/bridge/telemetry/compatibility 职责，以及公开 Codex `rust-v0.147.0` 固定快照中的 provider client、retry、model migration、session archive 与 app-server compatibility 边界；未复制函数体、名称布局、文案、常量或 wire。补充结论：

- **Observed**：Provider 流、重试/限流、能力与 Usage 分离；不同 Provider 的 finish reason、tool blocks 与错误类型必须在 Adapter 归一化，不能只识别 OpenAI 类型。
- **Observed**：兼容验证分 schema fixture、真实进程和已发布 artifact 三层；本地 first-v1 fixture 不能冒充 N/N-1 已发布 artifact。
- **Inferred**：双 route Eval 必须显式标记 `REAL_PROVIDER` 与 `PROTOCOL_MOCK`，模拟 Anthropic 只能证明 Adapter/wire 契约，不能提升真实 Anthropic L3。
- **Unknown / Accepted Deviation**：无 Anthropic 真实凭证、无已发布 N-1 artifact、WSL Ubuntu 无 JDK 21、无 macOS/Native Image/公共更新服务；这些不得计 Pass。

本项目据此增加 Anthropic protocol mock、真实 OpenAI-compatible suite、12×5×2 无正文 Eval artifact、Windows/WSL package/launcher contract smoke 与 first-v1 compatibility fixtures。真实证据不足的条目保持 L1/L2，不提升 L3。

## 停止条件

授权撤回、快照身份变化、研究输出无法与参考表达分离，或实现需要复制参考字节时停止授权材料研究。公开 Codex tag/commit 不匹配时暂停该来源升级。本 ADR 只固定研究采纳边界，不单独提升等级。
