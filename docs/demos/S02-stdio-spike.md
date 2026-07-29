# S02 Java Fake stdio Spike Demo

该 Demo 证明 Java Headless 边界能够被真实子进程驱动，但不宣称真实 Agent CLI 已可用。

## 运行

在仓库根目录执行：

```text
.\mvnw.cmd -pl cc-java-cli -am -Dtest=StdioProtocolProcessTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

- Java Fixture 进程收到 `initialize → run.start → run.cancel → shutdown`；
- stdout 每行都能解析为一个协议事件；
- Run 只产生一个 `run.cancelled` 终态；
- 进程退出码为 0，stderr 为空；
- 测试捕获到的后代进程全部停止。

要查看 Codec、上限、状态机和慢消费者负例，执行：

```text
.\mvnw.cmd -pl cc-java-cli -am test
```

这里的 Fixture 只存在于测试代码中。S02 后续必须用真实 Java Composition Root 替换它，
再由 React/Ink TUI 拉起；当前不能把本 Demo 当成可交付 CLI。
