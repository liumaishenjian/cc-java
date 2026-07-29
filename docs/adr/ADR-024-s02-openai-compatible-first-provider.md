# ADR-024：S02 首个真实 Provider 采用 OpenAI 兼容接口

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI（Provider、Streaming 与真实 Headless 链路已验证）
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `N/A - Not Used`
- Capability IDs: `MODEL-02`、`MODEL-04`、`MODEL-05`、`MODEL-06`、`CFG-02`、
  `LOOP-04`、`LOOP-08`、`LOOP-09`、`LOOP-10`
- Decision Scope: 首个 Provider 类型、秘密来源、配置边界和可证伪实验

## 背景

维护者明确选择自己的 OpenAI 兼容中转地址、API Key 和真实模型完成 S02 Provider
验证，不再把 Ollama 作为首个 Provider。该选择不改变 Java Runtime、Spring AI Adapter
或 Tool Pipeline 的职责，只决定首个真实模型实验的传输适配方向。

OpenAI 兼容只表示目标端点声称接受相应 API 形状，不等于已经证明 Streaming、Tool Call、
Usage、Finish Reason 和 Cancellation 与官方 OpenAI 或 Spring AI 完全一致。这些差异
必须由真实 Spike 观察，不能从“兼容”名称推断。

## 公开依据（访问日期 2026-07-29）

| 来源 | 分类 | 最小结论 |
| --- | --- | --- |
| [Spring AI 2.0.0 Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) | 官方文档，`Documented` | 2.0.0 是当前稳定线，支持 Spring Boot 4.0/4.1，BOM 与模型模块可从 Maven Central 取得 |
| [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) | 官方文档，`Documented` | Chat 支持独立 Base URL、API Key 和模型配置，可用于 OpenAI 兼容端点 |
| [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) | 官方文档，`Documented` | Spring AI 2.0 的直接 `ChatModel` 调用返回原始 Tool Call；自动循环只在显式 Advisor/框架路径发生 |

中转服务的准确实现、模型能力、兼容程度和错误语义目前均为 `Unknown`。

## 决策

1. 首个 Provider Spike 使用 Spring AI 的 OpenAI Chat 适配器，不引入 Ollama Starter；
2. S02 使用固定的 `config/provider.local.properties` 作为每台电脑独立填写的本地配置：
   - `openai.base-url`
   - `openai.api-key`
   - `openai.model`
3. 真实本地文件必须由 `.gitignore` 排除；仓库只提交空值模板
   `config/provider.local.properties.example`；
4. 以下环境变量作为 CI、临时运行或外部 Secret Store 注入的覆盖层，优先级高于本地文件：
   - `CC_JAVA_OPENAI_BASE_URL`
   - `CC_JAVA_OPENAI_API_KEY`
   - `CC_JAVA_OPENAI_MODEL`
5. API Key 不允许通过普通 CLI 参数传入，不写入 stdout、stderr、异常、测试报告、
   Transcript、进度看板或 Git；
6. Loader 固定路径、限制文件为 16 KiB、拒绝符号链接，并校验 Base URL；配置对象
   的字符串表示必须把 API Key 替换为 `<redacted>`；
7. Base URL 的诊断最多显示协议与 Host，不记录 Query、Header 或凭证；
8. Adapter 直接使用 Spring AI 2.0 `ChatModel`，不创建带 `ToolCallingAdvisor` 的
   `ChatClient`；定义型 `ToolCallback` 若被错误调用会立即失败，Adapter 只向 Core 返回原始 Tool Call；
9. 普通构建和测试继续使用 Fake，不需要网络或密钥；真实 E2E 必须显式启用；
10. 真实 Spike 已固定 Spring AI 2.0.0、Spring Boot BOM 4.1.0 与
    `org.springframework.ai:spring-ai-openai`；Boot BOM 当前仅用于版本管理。

## 可证伪实验

真实模型由维护者提供时，依次验证：

1. 文本 Delta 按序到达并聚合；
2. 单个 Tool Call 参数跨 Chunk 后仍是合法 JSON；
3. 同一回合多个 Tool Call 的 ID、名称和顺序保持；
4. Tool Result 能作为下一回合消息返回模型，Spring AI 未自行执行 Tool；
5. 取消能终止订阅，旧 Run 不再发布 Delta 或第二终态；
6. 限流、空响应、不完整流和普通异常映射为结构化失败；
7. Finish Reason 能识别长度上限；Usage 缺失时保持为空；
8. 请求以外的异常、诊断、测试报告和进度产物中均找不到 API Key；
9. `git check-ignore config/provider.local.properties` 能证明真实本地文件不会进入 Git。

若中转端点不能稳定提供原始 Tool Call、取消后仍产生不可控事件，或只有泄漏完整请求才能
诊断，则否决当前组合并返回 G2。

## 2026-07-29 实验结论

- `Observed`：真实端点通过 Spring AI 2.0.0 返回有序文本增量、聚合文本、可信 Usage、
  `stop` Finish Reason，以及带非空 ID、名称和 JSON 参数的原始 Tool Call；
- `Observed`：真实 React/Ink 非 TTY 路径经 stdio v0 与 Java Runtime 输出模型文本并
  产生唯一 `run.completed`；
- `Documented + Tested`：直接 `ChatModel` 不拥有 Agent Loop；定义型
  `ToolCallback.call` 被设计为立即失败，离线测试证明 Adapter 只返回 Tool Call；
- `Tested`：Core 把模型取消传播到 Reactor 订阅，精确校验 Session/Run ID，并产生唯一
  `USER_CANCELLED` 终态；
- `Unknown`：真实端点的跨 Chunk 多 Tool Call、不完整流、限流、长度上限和真实网络取消
  仍缺证据，因此 `MODEL-05` 只提升到 L1，S02 不退出。

详细证据见
[S02 Provider/Runtime Integration](../evidence/S02-provider-runtime-integration-2026-07-29.md)。

## 非目标

- 本 ADR 不实现 Provider，不证明中转服务兼容；
- 不实现多 Provider、Fallback、路由或费用表；
- 不把 OpenAI SDK 类型引入 Domain/Core；
- 不把中转地址、API Key 或真实模型名提交到仓库；被忽略的本地文件不属于共享配置；
- 不在 S02 提前实现 S08 的通用用户/项目/目录配置层级；
- 不因为首个文本请求成功就提升 S02 Capability Level。
