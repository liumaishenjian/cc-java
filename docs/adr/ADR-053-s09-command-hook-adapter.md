# ADR-053：S09 受控 Command Hook Adapter

- Status: Accepted
- Date: 2026-08-08
- Stage: S09 Hooks
- Features: `HOOK-09`、`HOOK-13`
- Depends on: ADR-051、ADR-052、ADR-039

## Decision

第一版外部 Hook 只提供一个 CLI 边缘的本地 Command Adapter。它接收一个已经由 Core
构造的 `HookInvocation`，使用固定 argv 启动绝对可执行路径，把脱敏 JSON 写入 stdin，
再从 stdout 读取一个有界的结构化意见：

```json
{"disposition":"CONTINUE|ALLOW|DENY|BLOCK","reason":"...","additionalContext":"..."}
```

退出码非零、JSON 字段未知/缺失、重复字段、输出超限、超时或取消都不会把原始进程输出
带回 Runtime，而是转成 `HookExecutionStatus`。`HookCoordinator` 再按绑定的
`FAIL_OPEN`/`FAIL_CLOSED` 重新决定是否阻断；Command Adapter 自身不能绕过该策略。

## 安全边界

- 不经过 cmd/bash/PowerShell，不把用户输入拼入 Shell 字符串；
- 第一个 argv 必须是绝对路径，工作目录固定为已解析真实 Workspace；
- 清空继承环境，只保留协议版本标记，不继承 Provider Key 或未知 Secret；
- stdout/stderr 各自有硬字节上限，两个流并发 drain，超时/取消递归清理子进程；
- stdin 只包含 `event/sessionId/runId/subject/data` 等项目自有脱敏字段；
- 外部 Handler 的 Context/Reason 仍受 Core 字符上限约束，不允许返回 Tool 参数、文件正文
  或新的执行意图。

## 采用范围与缺口

本 ADR 只固定 Adapter 与 Fake/内存进程测试契约，不解析参考产品内部 Hook 配置，也不在本轮
接入 user/project/local Settings、Trust UI、稳定 stdio 展示或远程 HTTP。配置来源和信任
指纹属于后续 S09 切片；OS Sandbox 与远程网络留给 S13。
