package io.github.liumaishenjian.ccjava.domain;

/**
 * Agent Runtime 在 Canonical Transcript 与短生命周期 Context Projection 中使用的消息协议。
 *
 * <p>该封闭协议刻意不复用 Spring AI 或任意 Provider 的消息类型。实现该协议不等于具备规范
 * 持久化资格：{@link MemoryContextMessage} 等 Projection-only 消息只能存在于单次模型请求，绝不能
 * 进入 Session 或 Journal；哪些消息可以追加、恢复或持久化，由 Runtime、Session 与 Journal API 的
 * 确定性写入入口强制执行。Adapter 必须在边界完成转换，Core 只依赖这里定义的稳定语义。</p>
 *
 * @since 0.1.0
 */
public sealed interface AgentMessage
        permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage,
                ContextSummaryMessage, MemoryContextMessage, SkillContextMessage {
}
