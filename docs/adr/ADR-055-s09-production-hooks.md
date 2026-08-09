# ADR-055：S09 Hook 生产接入与安全收口

- Status: Accepted
- Date: 2026-08-09
- Stage: S09 Hooks
- Features: `HOOK-02`～`HOOK-07`、`HOOK-09`、`HOOK-10`、`HOOK-12`、`HOOK-13`
- Depends on: ADR-051～ADR-054、ADR-043、ADR-048

## Context

S09 第一切片已证明 Core 协议与 Command Adapter，但还不能从实际 Session 配置启动 Hook，
Compact 也没有决策入口。最终切片需要在不建立第二套 Agent Loop、不放宽 Permission、也不把
应用层检查描述成 OS Sandbox 的前提下关闭这些差距。

## Decision

1. 固定 user `~/.cc-java/extensions.json` 与 project `.cc-java/extensions.json`，采用严格 JSON v1；
   project 文件只有其精确 SHA-256 已通过显式 CLI 动作写入用户私有 trust store 时才激活。
2. 未信任 project Hook 在创建 Handler 前过滤；配置变化使批准失效。`--extension-status` 只展示
   安全状态和指纹，`--trust-project-extensions` 是唯一批准入口，批准后要求重启 Session。
3. Subject Matcher 使用最长 256 字符、只含 `*`/`?` 的线性 glob，不执行任意正则，避免匹配阶段
   在 timeout 之外形成回溯型拒绝服务。
4. Command Hook 使用固定 argv、固定 cwd、空环境、异步 stdin、总墙钟 timeout 与 Windows 进程树清理；
   HTTP Hook 只允许每次请求都重新解析为 loopback 的 HTTP，不跟随重定向，不接受自定义 Header。
5. `PRE_COMPACT` 在摘要器前执行且可以阻断；`POST_COMPACT` 只观察终态。Pre additional context 进入
   本次摘要 anchor；Post Tool additional context 只进入下一次 Model Projection，不写 Canonical Transcript。
6. `OBSERVE_ONLY` 的成功返回也强制收敛为继续；单个损坏 Handler 只按显式 failure policy 影响当前事件。

## Verification

- 配置/Trust：首次未激活、显式批准、配置变化失效、未知字段/重复键/尾随 token 拒绝；
- Command/HTTP：真实子进程、阻塞 stdin、超时取消、进程树清理、loopback/DNS/正文上限；
- Runtime：Pre/Post Tool、Session/Run/Prompt/Permission、Compact 阻断、Post Context 下一回合投影；
- 完整 Maven Reactor、TUI 检查、进度看板与隐私审查。

## Deferred

远程 HTTP Hook、认证、OS Sandbox 属于 S13；Prompt/Agent/Sub-Agent Hook 属于 S12/S15；稳定外部
Hook 协议与迁移属于 S14。本 Stage 的 loopback HTTP 因此只达到 L1，不能描述成任意远程回调。
