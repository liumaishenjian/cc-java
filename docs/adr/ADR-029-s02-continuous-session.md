# ADR-029：S02 连续 Headless Session

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `CLI-01`、`SESSION-02`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed`，本项目契约为 `Documented`

## 背景

能连续显示两个回答不等于连续 Session。S02 必须证明第二次 Run 复用同一个规范 Session，
并让 ModelRequest 看见第一轮 User/Assistant 历史；否则 TUI 只是重复调用两个一次性 Prompt。

## 受控参考机制

授权快照公开的 SDK 类型把“持久多轮 Session”和“One-shot Prompt”定义为不同契约，
并以稳定 Session ID 表示后续回合归属。本项目只采用这一职责划分，不复制其函数体、
类型名、存储格式、错误文案或私有实现。

## 决策

1. 一个 `HeadlessRuntimeSession.open()` 只创建一个 Java `AgentSession`；
2. 每次用户提交创建不同 Run ID，但 Model Turn 编号在新 Run 内从 1 开始；
3. 第二次 Run 的第一个 ModelRequest 按规范顺序包含：
   稳定 System Context、第一轮 User、第一轮 Assistant、第二轮 User；
4. stdio 连接保持同一个 Session ID，连续 Run 使用不同 Run ID 和单调事件序列；
5. React/Ink Reducer 追加 Run 投影，不覆盖已经完成的 Run；
6. S02 只承诺进程内连续性；JSONL、resume/fork 和崩溃恢复仍属于 S06。

## 可证伪验证

- Headless Runtime 捕获两个 ModelRequest，断言 Session/Run/历史顺序；
- 真实 Java Fake 子进程经一条 stdio 连接完成两个 Run 后优雅退出；
- TUI Reducer 保留两个完成态 Run；
- 任何一次出现新 Session ID、重复 Run ID、历史缺失或覆盖第一轮，验证均失败。
