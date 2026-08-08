# ADR-052：S09 Hook 产品与 Java 架构契约

- Status: Accepted
- Date: 2026-08-08
- Stage: S09 Hooks
- Features: `HOOK-02`～`HOOK-13`
- Depends on: ADR-035、ADR-039、ADR-043、ADR-045、ADR-051

## Decision

S09 在现有生命周期旁路上建立一条独立、可阻断但不可越权的 Hook 管线：

```text
Runtime decision point
  → redact bounded HookInvocation
  → match ordered bindings
  → bounded concurrent Handler execution
  → stable aggregation
  → continue / allow / block
```

`LifecycleDispatcher` 继续把内部事件记录到 Session 并通知观察 Sink；它不调用用户 Hook，
也不允许 Sink 阻断。只有 Runtime 在明确决策点调用 `HookCoordinator`，结果才可能改变控制流。

## Event 与控制权

| Event | 默认语义 | 可阻断 | 进入位置 |
| --- | --- | --- | --- |
| `PRE_TOOL` | Tool 摘要观察/安全前置检查 | 是 | 参数校验后、Permission 前 |
| `POST_TOOL` | Result 观察与有界反馈 | 否 | Result 规范化且 journal 完成后 |
| `PERMISSION_REQUEST` | ASK 的附加意见 | 是 | Hard Denial/Policy 后、人工审批前 |
| `USER_PROMPT` / `PRE_COMPACT` | 输入或压缩前安全 Gate | 是 | 相应状态迁移前 |
| `SESSION_START/END`、`RUN_START/END`、`MODEL_TURN_*`、`POST_COMPACT` | 诊断/上下文观察 | 否 | 对应生命周期点 |

阻断 Hook 只能产生结构化 Tool/Run 结果或停止意见，不能直接执行文件、命令、网络或模型调用。
Pre Tool 阻断必须保留原始 Tool Call ID；Post Tool 不能撤销已完成副作用。

## Matcher、绑定与聚合

- Matcher 首版按 Event 和有界 `subject` 正则匹配；不把任意 Prompt 或未验证路径当作隐式范围。
- Handler 绑定带稳定 ID、来源顺序、失败策略、信任状态和有界超时；配置层合并不依赖 Map 遍历顺序。
- 多个匹配 Handler 可在共享有界执行器中并发运行，结果按 `order,id` 排序重新聚合。
- 聚合优先级：`DENY` > `BLOCK` > `ALLOW` > `CONTINUE`；Post/其他非阻断 Event 将所有阻断意见降级为
  `CONTINUE`。additional context 按绑定顺序拼接并受总字符上限约束。
- `FAIL_CLOSED` 只对决策点形成阻断；诊断/完成事件默认隔离故障。`OBSERVE_ONLY` 永远不能改变决策。

## Handler 与安全边界

首版只提供 Core `HookHandler` Port 与 Fake/内存 Handler，证明协议和执行语义。后续外部 Adapter 必须：

1. 使用结构化 argv 或严格 loopback HTTP，不拼接用户文本进 Shell；
2. 使用固定 Workspace cwd、最小环境，过滤 Provider Key 和未知 Secret；
3. 对 stdin/stdout/stderr、Context、reason 和耗时施加硬上限；
4. 传播取消、超时并清理 Windows 进程树；
5. 对首次/变更配置执行 fingerprint/trust Gate；未信任 Handler 不启动；
6. 失败只能按绑定策略映射，不得绕过 Permission、Hard Denial 或统一 Tool Pipeline。

S09 的 HTTP 目标只限 `127.0.0.1`/`localhost`，远程网络与 OS 隔离留给 S13。Prompt/Agent Handler、
Sub-Agent Hook 留给 S12/S15。

## 配置与 Session 边界

Hook 配置复用 S08 的 user/project/local 来源与诊断原则，但使用独立的项目自有 schema；不解析参考
产品内部 JSON。Session-scoped Handler 只在当前进程有效，不能通过 Hook 输出偷偷写入 Settings 或
Canonical Transcript。Context 增量必须经过 S07 Context Projection 的显式入口，并可丢弃、限量和追踪。

## 分阶段实现

1. G0-G2：ADR-051 机制研究、Feature/目标等级和本 ADR 冻结；
2. G3：Domain/Core 协议、匹配、聚合、超时/取消和 Pre/Post Tool 接入；
3. G4：Fake、Pipeline E2E、失败/安全/并发/Call ID 回归；
4. G5：Command/HTTP loopback Demo、外部进程生命周期和 TUI/stdio 活动摘要；
5. G6：矩阵、差距报告、看板和 Commit-scoped 证据。达到 S09 Exit 前不得把 Hooks 描述成完整产品能力。

## 明确非目标

本 ADR 不实现 OS Sandbox、Managed Policy、远程 Hook、模型参与决策、S12 Sub-Agent Hook 或 S14
稳定外部 Hook 协议。它们保持矩阵中的后续 Stage 差距。
