# S09 Hooks Demo

状态：S09 Stage Exit Accepted；演示本地 Command/loopback HTTP、Trust 与 Compact，不代表远程 Hook 或 Sandbox。

## 自动复现

```powershell
.\mvnw.cmd -pl cc-java-core,cc-java-cli -am `
  '-Dtest=HookCoordinatorTest,S09HookRuntimeLifecycleTest,S09HookToolPipelineTest,CommandHookHandlerTest,HttpHookHandlerTest,ExtensionConfigurationLoaderTest,S09CompactHookE2ETest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期 0 failures/errors。可观察点包括稳定并发聚合、线性 glob、Pre 阻断、Post 只观察、真实
loopback HTTP Compact 阻断、Command timeout/进程清理，以及 project Trust 变化失效。

## 手工 Trust 流程

1. 在 `.cc-java/extensions.json` 写入 version 1 的 project Hook 配置；
2. 执行 `codej --extension-status`，预期显示 project 存在但需要信任，且不启动 Handler；
3. 执行 `codej --trust-project-extensions`，检查只输出安全指纹与“重启生效”；
4. 重启后再执行状态命令，预期 Hook 为 active；
5. 修改配置任意字节后重启，预期重新变为 `PROJECT_TRUST_REQUIRED`。

User 配置位于 `~/.cc-java/extensions.json`，视为用户主动维护的私有来源。配置严格拒绝未知字段、
重复 JSON key 与尾随 token。不要把密钥、Provider 配置或完整源码放入 Hook 输出。

## 负例

- 非 loopback HTTP 在构造时拒绝；解析到任一非 loopback 地址也拒绝；
- `OBSERVE_ONLY` 即使返回 ALLOW/BLOCK 也不能改变控制流；
- Pre Compact BLOCK 时摘要器调用次数保持 0；
- Project 配置未批准时 Handler/Transport 都不会创建。
