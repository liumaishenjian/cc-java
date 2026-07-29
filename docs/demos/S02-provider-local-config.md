# S02 OpenAI-compatible 本地配置 Demo

普通测试只验证配置加载、优先级、校验和脱敏，不访问网络。真实 Provider 兼容性由
显式 opt-in Spike 单独验证。

## 每台电脑填写

编辑 Git 忽略文件：

```text
config/provider.local.properties
```

```properties
openai.base-url=https://your-gateway.example
openai.api-key=your-api-key
openai.model=your-model-name
```

不要把真实值写入 `.example` 模板。

## 验证

```powershell
git check-ignore config/provider.local.properties
.\mvnw.cmd -pl cc-java-model-spring-ai -am test
```

第一条必须输出 `config/provider.local.properties`。测试覆盖：

- 本地文件加载；
- 环境变量覆盖本地文件；
- 必填项缺失；
- Base URL 中嵌入凭证时拒绝；
- 配置对象和异常不输出 API Key。

测试不读取当前电脑的真实配置，也不会显示真实 API Key。

## 显式真实验证

仅在维护者已填写本地配置并明确允许网络请求时运行：

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am `
  '-Dtest=OpenAiProviderSpikeTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dccjava.real-provider=true' test
```

测试断言文本 Delta/聚合、Usage、Finish Reason 和原始 Tool Call 的结构，不断言固定自然
语言，也不输出 API Key、完整端点或 Prompt。普通构建中该测试默认 Skip。

当前 Provider 是否支持同一 Assistant Turn 返回两个 Tool Call 使用更严格的独立开关：

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am `
  '-Dtest=OpenAiProviderSpikeTest#returnsTwoToolCallsInOneAssistantTurn' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dccjava.real-provider=true' `
  '-Dccjava.real-provider-multi-tool=true' test
```

本机 2026-07-29 对照结果只生成第一个 Tool Call，因此该命令当前是有意保留的兼容性
负例，不属于普通通过链。Spring AI/Adapter 的跨 Chunk 双 Tool 聚合由不访问外网的
`OpenAiStreamingContractTest` 独立证明。
