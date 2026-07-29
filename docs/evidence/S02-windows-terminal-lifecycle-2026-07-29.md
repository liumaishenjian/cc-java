# S02 Windows 终端生命周期证据

- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `CLI-01`、`CLI-06`、`CLI-09`、`CLI-10`、`CLI-11`
- ADR: [ADR-028](../adr/ADR-028-s02-windows-terminal-lifecycle.md)
- Tested implementation: `WORKTREE`
- Classification: `WORKTREE_VERIFIED`

## 实现结论

本轮把 React/Ink 到 Java Headless 的直接子进程生命周期收紧为可观察状态：

- Java 未进入 shutdown 就退出时，先发布固定 Transport Failure，再发布 exit；
- `shutdown` 先等待优雅退出，超时后 kill，并继续等待真实 exit；
- 取消命令有 2 秒默认期限，超时后终止 Java；
- 活动 Run 第一次 `Ctrl+C` 请求取消，第二次直接终止；
- Node exit 注册同步 kill 兜底，正常路径解除；
- Paste 按 Unicode Code Point 限制为 8192 字符；
- Resize 只重新投影 View，不重建 Session、Run 或输入状态。
- connecting 阶段输入立即回显，最新 Input Ref 避免快速输入后回车丢字符；
- 非交互子进程失败返回固定诊断与退出码，不再泄漏 Node/TypeScript 堆栈；
- `-SkipBuild` 会拒绝复用比 Java 源码/POM 更旧的 class 或 classpath。

这些行为是本项目独立契约。授权快照只用于提炼“输入、取消、清理和进程退出是不同职责”
这一机制，没有复制参考函数体、类型名、文案、常量或文件布局。

## 自动化结果

在 Windows、Node.js 22 下执行：

```text
cd cc-java-tui
npm.cmd run check
```

结果为 TypeScript 编译通过，5 个 Test File、21 个 Test 全部通过。新增负例证明：

1. 活动 Run 中 Java `exit(17)` 会拒绝非 TTY Promise，不再悬挂；
2. Fake 子进程忽略 shutdown 时，Client 超时 kill、等待 exit，捕获 PID 随后不存在；
3. Fake 子进程忽略 cancel 时，取消期限到达后 kill，捕获 PID 随后不存在；
4. 协议失败只发布一次，不被后续 exit 覆盖；
5. 第二次活动中断的纯状态决策为 terminate；
6. 8192 个 Code Point 上限在中文输入下成立；
7. 100 列到 20 列重渲染后，Run 内容和未提交输入保持不变。
8. 连接期真实 Ink `useInput` 立即回显；ready 后同批次文本与回车提交完整 Prompt；
9. 子进程崩溃只输出固定诊断、exit code 和 stderr 字节数，不打印 Node 堆栈。

## 真实 Windows TTY

从仓库根目录运行真实脚本，观察到：

```text
connecting → ready → closing
```

空闲 `Ctrl+C` 发送 shutdown 并以退出码 0 结束。随后执行 `jps -lv`，没有发现
`io.github.liumaishenjian.ccjava.cli.CcJavaCliMain`。

修复后又执行两次真实 Provider 非交互会话，分别得到正常自我介绍和严格 `OK`，退出码
均为 0。真实 TTY 在 `connecting` 阶段输入“连接中输入”立即显示，进入 `ready` 后内容
保持，继续输入“补充”仍正确显示；空闲 Ctrl+C 后再次确认无 cc-java Java 残留。

当前 Codex 自动 PTY 能写入中文文本，但不能可靠合成 Ink 识别的 Enter 键，因此本轮没有把
“真实 Provider 活动 Run 中连续两次 Ctrl+C”标成真实 TTY 已验证；该行为由确定性状态测试、
取消超时测试和 PID 消失测试覆盖，并保留为人工 Demo 复核项。

## Capability 判断

本轮没有机械提升 Capability Level：

- `CLI-06` 保持 L1：模型 Run 取消边界和强制退出已验证，S04 仍需 Tool/进程树取消；
- `CLI-09` 保持 L0：8192 字符 Paste 只是安全边界，多行、历史和补全仍属于 S08；
- `CLI-10` 保持 L2：TTY/非 TTY 与无 ANSI 证据继续有效；
- `CLI-11` 保持 L1：stdio v0 仍是内部实验协议；
- `CLI-01` 保持 L1：需再完成真实 TTY 连续多轮与活动取消人工复核后才能升到 S02 L2。

## 剩余差距

- 真实 TTY 连续多轮、活动取消和第二次中断仍需维护者在原生终端复核；
- 当前真实 Provider 同一回合只生成一个 Tool Call，仍需兼容性确认或明确偏差；
- S04 才负责 Tool 子进程树和 Shell 取消；S14 才建立跨平台 PTY 自动化矩阵。
