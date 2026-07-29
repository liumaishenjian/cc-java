# ADR-027：S02 模型流健壮性与有界恢复

- Status: Accepted
- Date: 2026-07-29
- Stage: S02 Model + Streaming CLI
- Capability IDs: `LOOP-09`、`LOOP-10`、`MODEL-05`、`MODEL-10`
- Reference Behavior Baseline: `R2026.03`
- Authorized Snapshot ID: `AUTH-SRC-2026-07-29-A`
- Classification: 参考机制为 `Observed / Inferred`，本项目契约为 `Documented`

## 背景

当前 Spring AI Adapter 已能聚合文本、保留单个原始 Tool Call、映射 Usage 与
Finish Reason，但还没有证明：

- 同一回合多个 Tool Call 的 ID、顺序和参数完整性；
- Provider 在第一个可见 Delta 前失败时的有界重试；
- 已经输出 Delta 后断流时不会重试并重复用户可见文本；
- 流正常关闭但缺少 Finish Reason 时不会被误判为完整回答；
- `length` 终止不会被 Runtime 当作普通成功。

这些缺口不能靠一次真实自然语言响应关闭，需要独立状态和确定性故障注入。

## 受控参考研究结论

对 `AUTH-SRC-2026-07-29-A` 的模型查询、工具编排、API 错误处理和取消清理子系统进行
只读研究后，提炼出以下不含参考表达的机制：

| 分类 | 机制结论 |
| --- | --- |
| Observed | 模型流、Tool Use 聚合、Tool 执行和查询循环是不同职责 |
| Observed | 可重试错误有次数/时间边界，并受同一取消信号约束 |
| Observed | 部分输出后的传输失败与请求建立前失败不是同一种恢复路径 |
| Observed | 多 Tool Use 必须先形成完整批次，再进入确定性的执行阶段 |
| Inferred | 已发布文本后自动重试会产生重复 Delta，S02 应 Fail Closed |
| Inferred | 长度终止需要显式终态；未经评测的自动续写不应成为默认行为 |

授权材料只用于上述机制抽象。函数体、Prompt、错误文案、私有类型、文件布局和常量
均不进入本项目。

## Spring AI 2.0 边界核验

本项目固定的 Spring AI 2.0.0 会在 `OpenAiChatModel` 内聚合 OpenAI Tool Call
参数片段，再由通用消息聚合器形成最终 `ChatResponse`。本项目不会再实现第二套
OpenAI SSE Parser，但会在 Adapter 边界验证聚合结果：

1. Tool Call 的 ID 和名称非空；
2. 参数是完整 JSON Object；
3. 多调用保持 Provider 返回顺序；
4. Tool Call 与 `TOOL_CALLS` Finish Reason 相互一致；
5. 缺少 Finish Reason 的正常关闭流视为不完整。

如果真实 OpenAI-compatible E2E 证明框架不能保持多调用或跨 Chunk 参数，本技术组合
返回 G2 重新选择，而不是在 Core 中泄漏 Provider Chunk 类型。

## 决策

### 1. 结构化模型失败

`ModelGatewayException` 增加稳定的失败分类：

- `PERMANENT`：请求或响应确定性无效，不重试；
- `RETRYABLE`：尚未产生可见输出的瞬时失败；
- `RETRY_EXHAUSTED`：有界策略已经耗尽；
- `INCOMPLETE_STREAM`：流在完整终态前结束；
- `CANCELLED`：用户取消或 Run Deadline。

异常消息只能包含固定诊断和安全类型名，不包含请求、Prompt、响应体、端点或 Secret。

### 2. 有界重试

在 Core 使用 Provider-neutral `RetryingModelGateway` 装饰实际 Gateway：

- 默认最多 3 次请求；
- 只重试 `RETRYABLE`；
- 第一个用户可见 Delta 之后禁止重试，转换为 `INCOMPLETE_STREAM`；
- 退避等待监听同一 `CancellationToken`；
- Run Deadline 覆盖重试和等待总时间；
- 普通 Fake 测试使用零等待，不访问网络。

Spring AI/OpenAI Adapter 只负责把底层限流、服务端、网络和流错误分类；重试次数和
取消边界不由 SDK 的隐藏默认值决定。SDK 内建重试保持关闭。

### 3. 不完整流

以下情况 Fail Closed：

- 零响应或没有最终 Generation；
- 流出现过响应后异常关闭；
- 正常关闭但 Finish Reason 缺失；
- `TOOL_CALLS` 没有完整调用；
- 存在 Tool Call 但 Finish Reason 不是 `TOOL_CALLS`；
- Tool 参数不是完整 JSON Object。

Runtime 将其映射为 `INCOMPLETE_MODEL_STREAM`，不会追加残缺 Assistant Message，
也不会执行任何 Tool。

### 4. 长度终止

S02 识别 `ModelFinishReason.LENGTH` 并以 `OUTPUT_LIMIT_REACHED` 停止：

- 已发布 Delta 可以保留在当前 Surface；
- 截断文本不进入规范 Session 历史，也不作为最终成功文本；
- 不自动插入隐藏续写 Prompt；
- S14 在独立任务集上比较“一次续写、摘要后续写和明确停止”后，才能改变默认策略。

这是有界恢复中的“明确停止”路径，避免未经验证的自动续写重复、偏移或无限循环。

## 可证伪验证

至少覆盖：

1. 一个回合两个 Tool Call，顺序、ID、名称和参数保持；
2. Tool Call Finish Reason 不一致时拒绝整批；
3. 第一个 Delta 前连续两个瞬时错误，第三次成功；
4. 重试次数耗尽后只有一个 Runtime 终态；
5. 退避期间取消立即结束；
6. 已发布 Delta 后断流不重试；
7. 缺少 Finish Reason 的流映射为不完整；
8. `length` 映射为 `OUTPUT_LIMIT_REACHED`，不写入 Session；
9. opt-in 真实 Provider 多 Tool Call；不对自然语言文案做固定断言。

## 延后内容

- Provider 自适应退避、`Retry-After`、全局限流协调与熔断属于 S14；
- 自动长度续写和质量评测属于 S14；
- Tool 并行执行属于 S12；
- 第二 Provider 和 Provider Fallback 属于 S14。
