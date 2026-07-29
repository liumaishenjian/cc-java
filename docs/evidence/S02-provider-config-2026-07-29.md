# S02 OpenAI-compatible Provider 本地配置证据

- Stage: `S02 Model + Streaming CLI`
- Feature IDs: `CFG-02`、`MODEL-02`、`OBS-05`
- Current → Target: 全部保持矩阵现有等级；本变更不提升 Capability Level
- Public Baseline: `R2026.03`，配置与秘密边界为 `Documented`
- Authorized Snapshot ID: `N/A - Not Used`

## 独立实现

项目定义固定的 `config/provider.local.properties`，只接受 Base URL、API Key 和模型名。
文件被 Git 忽略；环境变量可以覆盖。Java Loader 固定路径、限制 16 KiB、拒绝符号链接，
并使配置对象的字符串表示脱敏。

该实现没有读取授权参考源码，也没有复制 Provider SDK 或其他 CLI 的配置表达。

## 可证伪证据

```powershell
git check-ignore config/provider.local.properties
.\mvnw.cmd -pl cc-java-model-spring-ai -am test
```

结果：

- 本地文件被 `.gitignore` 命中；
- `ProviderSettingsLoaderTest` 4/4 通过；
- Core 回归 23/23 通过；
- 测试报告未包含测试 API Key。

## 剩余差距

- 尚未创建 Spring AI OpenAI Client；
- 尚未访问维护者中转端点；
- 尚未证明流式文本、Tool Call、Usage、Finish Reason 或取消兼容；
- S08 的通用分层配置仍未实现。
