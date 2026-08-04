package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 在项目 Domain 消息与 Spring AI Prompt 之间执行单向转换。
 *
 * <p>映射器不执行 Tool。提供给 Spring AI 的 ToolCallback 只有 Schema，
 * 若框架错误地尝试调用它会立即失败，从而让自动执行边界可被测试证伪。</p>
 *
 * @since 0.1.0
 */
final class SpringAiPromptMapper {

    private static final String SUMMARY_ENVELOPE_VERSION = "cc-java-context-summary-v1";

    Prompt map(ModelRequest request, String model) {
        Objects.requireNonNull(request, "request 不能为空");
        List<Message> messages = request.messages().stream().map(this::mapMessage).toList();
        List<ToolCallback> callbacks = request.toolDefinitions().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(Objects.requireNonNull(model, "model 不能为空"))
                .toolCallbacks(callbacks)
                .streamUsage(true)
                .parallelToolCalls(true)
                .build();
        return new Prompt(messages, options);
    }

    private Message mapMessage(AgentMessage message) {
        return switch (message) {
            case io.github.liumaishenjian.ccjava.domain.SystemMessage system ->
                    new org.springframework.ai.chat.messages.SystemMessage(system.content());
            case io.github.liumaishenjian.ccjava.domain.UserMessage user ->
                    new org.springframework.ai.chat.messages.UserMessage(user.content());
            case io.github.liumaishenjian.ccjava.domain.AssistantMessage assistant ->
                    mapAssistant(assistant);
            case io.github.liumaishenjian.ccjava.domain.ToolResultMessage toolResult ->
                    mapToolResult(toolResult.result());
            case ContextSummaryMessage summary -> mapSummary(summary);
        };
    }

    /**
     * 把摘要编码成固定 User envelope，绝不映射成 Assistant/ToolResponse 或 Provider Tool Call。
     *
     * <p>正文先按 UTF-8 Base64 编码后放入单一字段，即使其中含角色标记或类似 Tool 协议的文本，
     * Provider envelope 也不会出现可解释为 Tool Call/Result 的原始片段；tier 和来源 ID 使用稳定字段
     * 顺序，保证相同输入确定性映射。</p>
     */
    private org.springframework.ai.chat.messages.UserMessage mapSummary(
            ContextSummaryMessage summary) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", SUMMARY_ENVELOPE_VERSION);
        fields.put("tier", summary.tier().name());
        fields.put("sourceMessageIds", summary.sourceMessageIds());
        fields.put("contentBase64", Base64.getEncoder().encodeToString(
                summary.content().getBytes(StandardCharsets.UTF_8)));
        return new org.springframework.ai.chat.messages.UserMessage(SpringAiJson.write(fields));
    }

    private org.springframework.ai.chat.messages.AssistantMessage mapAssistant(
            io.github.liumaishenjian.ccjava.domain.AssistantMessage assistant) {
        List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls =
                assistant.toolCalls().stream()
                        .map(call -> new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                call.id(),
                                "function",
                                call.name(),
                                SpringAiJson.write(call.arguments().values())))
                        .toList();
        return org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(assistant.text())
                .toolCalls(calls)
                .build();
    }

    private ToolResponseMessage mapToolResult(ToolResult result) {
        String response = result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                ? result.content()
                : result.error()
                        .map(error -> error.code().name() + ": " + error.message())
                        .orElse("");
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        result.callId(),
                        result.toolName(),
                        response)))
                .build();
    }

    /**
     * 只把项目 ToolDefinition 暴露给模型，禁止适配器侧执行。
     */
    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final org.springframework.ai.tool.definition.ToolDefinition definition;

        private DefinitionOnlyToolCallback(ToolDefinition source) {
            this.definition = DefaultToolDefinition.builder()
                    .name(source.name())
                    .description(source.description())
                    .inputSchema(source.inputSchemaJson())
                    .build();
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                    "Spring AI Adapter must not execute tools; use ToolExecutionPipeline");
        }
    }
}
