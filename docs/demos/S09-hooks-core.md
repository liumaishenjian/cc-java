# S09 Core Hook Demo

状态：第一批 Core/Tool Pipeline 学习切片，尚未达到 S09 Stage Exit。

## 复现

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl cc-java-core -am `
  '-Dtest=HookCoordinatorTest,S09HookRuntimeLifecycleTest,S09HookToolPipelineTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试只使用 Fake Handler 和内存 Tool，不需要 API Key、网络、真实命令或仓库写入。

## 可观察结果

- 多个匹配 Handler 可以并发运行，但聚合结果按 `order,id` 稳定排列；`DENY/BLOCK` 优先于 `ALLOW`。
- 超时、取消和未信任 Handler 转成结构化状态；Pre Tool 的 `FAIL_CLOSED` 会阻断。
- Pre Tool 发生在参数校验之后、Permission 之前；阻断结果保留原始 Tool Call ID，Tool 副作用和
  Permission 都不会发生。
- Post Tool 在规范 Result 和生命周期记录之后运行；即使 Handler 返回 `BLOCK`，Post 也不能
  改写已经产生的 Result。
- Session Start/End、User Prompt、Run、Model Turn 和 ASK Permission Hook 都通过同一协调器进入；
  User Prompt 可以在 Run Journal 前安全阻断，Hard Denial 不会触发可覆盖的 Permission Hook。
- Command Adapter 的独立协议/进程测试位于 `CommandHookHandlerTest`：固定 argv、空环境、JSON
  stdin/stdout、超时/取消/输出上限和无效协议均有确定性 Fake 验证。
- Hook 输入只包含 `callId`、`toolName`、`status` 等项目自有摘要，不包含 Tool 参数正文。

## 当前边界

该 Demo 不代表 Hook Settings/Trust、Compact/HTTP 的生产配置、稳定 stdio/TUI 活动协议或 OS
Sandbox 已可用；这些属于后续 S09/S13 切片。
