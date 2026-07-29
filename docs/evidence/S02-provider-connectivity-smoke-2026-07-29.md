# S02 OpenAI-compatible Provider 连通性 Smoke

- Date: 2026-07-29
- Stage: `S02 Model + Streaming CLI`
- Feature IDs: `MODEL-02`、`CFG-02`
- Classification: `Observed`
- Authorized Snapshot ID: `N/A - Not Used`
- Capability Level: 未变化

## 配置与安全边界

请求读取 Git 忽略的 `config/provider.local.properties`。证据不记录完整 Base URL、
API Key、Authorization Header 或请求体；临时请求与响应文件在请求结束后删除。

## 观察结果

向维护者配置的 OpenAI-compatible Chat Completions 路径发送一次非流式最小请求：

```text
HTTP 200
response model: gpt-5.5
assistant content: PONG
```

这证明当前 Base URL、API Key、模型名和基础 Chat Completion 路径可以共同工作。

## 尚未证明

- Spring AI Adapter 兼容；
- 流式 Text Delta；
- Tool Call 参数分片与多 Tool Call；
- Usage、Finish Reason 和 Provider 错误映射；
- Cancellation 和取消后的事件边界；
- Java Runtime、stdio 与 React/Ink 的真实端到端组合。

因此 G2-G6 保持 `OPEN`，`MODEL-02` 仍保持 `L0`。
