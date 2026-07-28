package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

/**
 * 在项目消息协议与 Spring AI 公开消息协议之间做无副作用转换。
 *
 * <p>映射保持消息顺序、Tool Call ID 和 Tool Result ID，不执行 Tool、
 * 不修改历史，也不把 Spring 类型泄漏到 Domain/Core。</p>
 *
 * @since 0.1.0
 */
final class SpringAiMessageMapper {

    /**
     * 按原顺序映射完整消息历史。
     *
     * @param messages 项目规范消息
     * @return Spring AI 消息快照
     */
    List<Message> toSpringMessages(List<AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages 不能为空");
        return messages.stream().map(this::toSpringMessage).toList();
    }

    /**
     * 把项目 Tool 定义转换为只暴露 Schema 的 Callback。
     *
     * @param definitions 项目 Tool 定义
     * @return 不具有执行能力的 Spring AI Callback
     */
    List<ToolCallback> toToolCallbacks(
            List<io.github.liumaishenjian.ccjava.domain.ToolDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions 不能为空");
        return definitions.stream()
                .map(SchemaOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    private Message toSpringMessage(AgentMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        if (message instanceof io.github.liumaishenjian.ccjava.domain.SystemMessage system) {
            return new org.springframework.ai.chat.messages.SystemMessage(system.content());
        }
        if (message instanceof io.github.liumaishenjian.ccjava.domain.UserMessage user) {
            return new org.springframework.ai.chat.messages.UserMessage(user.content());
        }
        if (message instanceof io.github.liumaishenjian.ccjava.domain.AssistantMessage assistant) {
            List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls =
                    assistant.toolCalls().stream()
                            .map(call -> new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    call.id(),
                                    "function",
                                    call.name(),
                                    JsonCodec.write(call.arguments().values())))
                            .toList();
            return org.springframework.ai.chat.messages.AssistantMessage.builder()
                    .content(assistant.text())
                    .toolCalls(calls)
                    .build();
        }
        if (message instanceof ToolResultMessage toolResultMessage) {
            ToolResult result = toolResultMessage.result();
            String responseData = result.status()
                            == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                    ? result.content()
                    : JsonCodec.write(Map.of(
                            "status", result.status().name(),
                            "errorCode", result.error().orElseThrow().code().name(),
                            "message", result.error().orElseThrow().message()));
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            result.callId(),
                            result.toolName(),
                            responseData)))
                    .build();
        }
        throw new IllegalArgumentException(
                "不支持的 AgentMessage 类型: " + message.getClass().getName());
    }
}
