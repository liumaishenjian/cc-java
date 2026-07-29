# S02 连续 Session 验证证据

- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `CLI-01`、`SESSION-02`
- ADR: [ADR-029](../adr/ADR-029-s02-continuous-session.md)
- Tested implementation: `WORKTREE`
- Classification: `WORKTREE_VERIFIED`

## 机制对照

授权快照把持久多轮 Session 和一次性 Prompt 定义成不同入口。本项目采用独立 Java 契约：
一个 `HeadlessRuntimeSession` 拥有一个规范 `AgentSession`，每次提交产生新 Run，但后续
ModelRequest 继续读取该 Session 的有序消息历史。

## 确定性验证

执行：

```text
./mvnw.cmd -pl cc-java-cli -am \
  -Dtest=HeadlessRuntimeSessionTest,StdioProtocolProcessTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果为 6/6 通过。其中新增断言证明：

- 两次 Run 的 Session ID 相同、Run ID 不同；
- 每个新 Run 的首个模型回合编号从 1 开始；
- 第二轮请求顺序为 System、User1、Assistant1、User2；
- 同一个真实 Java Fake 子进程和 stdio 连接连续完成两个 Run；
- 两个协议终态使用相同 Session ID、不同 Run ID；
- shutdown 后 Java 退出码为 0，stderr 为空且无存活后代进程。

执行：

```text
cd cc-java-tui
npm.cmd test -- --run test/state.test.ts
```

结果为 3/3 通过。Reducer 在同一个 Session 投影中保留两个完成态 Run，没有覆盖第一轮。

## Capability 判断

- `CLI-01`：L1 → L2。Java Headless、stdio 和 React/Ink 三层连续 Run 契约均有确定性证据；
- `SESSION-02`：L1 → L2。同一进程内规范消息历史会进入下一次 ModelRequest；
- 不宣称 S06 能力：本轮没有 JSONL、resume、fork、跨进程恢复或崩溃恢复。

## 剩余差距

- `OBS-02`、`OBS-03`、`OBS-05` 尚未达到 S02 L2；
- 真实 Provider 同回合多 Tool 仍保留兼容性负例；
- 真实 TTY 活动取消仍保留维护者人工复核项。
