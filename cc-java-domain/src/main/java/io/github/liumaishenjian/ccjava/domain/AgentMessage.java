package io.github.liumaishenjian.ccjava.domain;

/**
 * Agent Runtime 中可进入规范消息历史的消息协议。
 *
 * <p>该协议刻意不复用 Spring AI 或任意 Provider 的消息类型。Adapter 必须在
 * 边界完成转换，Core 只依赖这里定义的稳定语义。</p>
 *
 * @since 0.1.0
 */
public sealed interface AgentMessage
        permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage,
                ContextSummaryMessage {
}
