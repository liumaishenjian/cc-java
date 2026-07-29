# ADR-028：S02 Windows 终端输入与子进程生命周期

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `CLI-01`、`CLI-06`、`CLI-09`、`CLI-10`、`CLI-11`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`，本项目契约为 `Documented`

## 背景

React/Ink Spike 已证明中文输入、流式渲染、非 TTY 降级和一次取消命令，但现有最小实现
仍有可证伪缺口：

- Java 在 Run 终态前崩溃时，非交互调用可能只观察到进程退出而无法结束 Promise；
- 优雅关闭超时后只发出 kill，没有等待 Java 确认退出；
- 活动 Run 连续两次 `Ctrl+C` 没有升级为强制终止；
- 粘贴没有输入上限，Resize 只有静态窄窗口测试；
- Node 非正常退出时没有显式同步清理钩子。

## 受控参考研究结论

对授权快照的终端输入、Viewport、Signal、Abort、Cleanup 和 Process 子系统进行只读研究，
只提炼以下机制：

| 分类 | 机制结论 |
| --- | --- |
| Observed | 键盘输入、Paste、Resize、渲染和进程清理是独立职责 |
| Observed | 一次取消与退出应用是不同状态转换 |
| Observed | Cleanup 需要覆盖正常结束、异常、信号和子进程提前退出 |
| Inferred | Node 不应依赖 Windows 自动把 Ctrl+C 转发给 Java |
| Inferred | Java 的结构化终态到达前，子进程退出必须成为显式 Transport Failure |

不复制参考函数体、Renderer、错误文案、私有类型、文件布局或常量。

## 决策

### 1. Java 子进程权威状态

`StdioClient` 维护：

- 是否请求过 shutdown；
- 是否存在活动 Run；
- 是否已观察进程 exit；
- 是否已经进入取消等待；
- stderr 只计字节，不展示原文。

Java 在未请求 shutdown 时退出，Client 发布固定 Transport Failure；活动 Run 的 Promise
必须结束，不能静默悬挂。协议残行、非法 UTF-8 和乱序仍优先报告对应协议失败。

### 2. 两阶段终止

- 空闲 `Ctrl+C`：发送 `shutdown`，关闭 stdin，等待 Java exit；
- 活动 Run 第一次 `Ctrl+C`：发送 `run.cancel`；
- 活动 Run 第二次 `Ctrl+C`：直接终止 Java 并退出 TUI；
- shutdown 超时：终止 Java，继续等待 exit；
- 强制终止后仍未观察 exit：以生命周期错误失败，不能声称无孤儿进程。

S02 只管理直接 Java 子进程。S04 引入 Tool 进程后，由 Java 执行层清理其进程树。

### 3. Node 退出兜底

TUI 入口注册同步 `process exit` 清理钩子；正常关闭后解除。该钩子只终止已经创建的
Java 直接子进程，不执行异步 I/O、不输出 stderr 内容。

### 4. Paste 与 Resize

- Paste 只在 `ready` 状态进入输入缓冲；
- 输入缓冲与 Java Prompt 共用 8192 字符上限；
- Unicode 按 Code Point 删除和裁剪；
- Resize 只改变 Viewport 投影，不重建 Session 或 Run；
- Bracketed Paste 的终端解析由 Ink 负责，本项目验证传入组件后的状态行为。

## 可证伪验证

1. Java 正常 shutdown 后 PID 消失；
2. Java 忽略 shutdown 时，超时终止并等待 exit；
3. Java 在活动 Run 中崩溃，非 TTY 调用固定失败且不悬挂；
4. 第一次/第二次活动中断分别为 cancel/terminate；
5. 大段 Unicode Paste 被限制到 8192 字符；
6. 80 列到 20 列重渲染保持同一 Run 内容；
7. Windows 原生 TTY 完成中文 Paste、Resize、活动取消和空闲退出；
8. 每个进程场景结束后按捕获 PID 验证不存在。

## 延后内容

- 完整多行编辑、历史、补全和 Vim 模式属于 S08；
- Tool 子进程树和 Shell 取消属于 S04；
- 跨平台 PTY 自动化矩阵和发行安装属于 S14。
