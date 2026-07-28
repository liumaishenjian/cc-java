package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 验证 Spring AI 边界保持消息顺序和 Tool Call/Result 关联。
 */
class SpringAiMessageMapperTest {

    private final SpringAiMessageMapper mapper = new SpringAiMessageMapper();

    @Test
    void mapsCanonicalHistoryWithoutLosingCallIds() {
        ToolCall call = new ToolCall(
                "call-1",
                "lookup",
                new JsonObject(Map.of("query", "S02")));

        var mapped = mapper.toSpringMessages(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                new AssistantMessage("", List.of(call)),
                new ToolResultMessage(ToolResult.success(
                        "call-1", "lookup", "found"))));

        assertThat(mapped)
                .extracting(value -> value.getClass().getSimpleName())
                .containsExactly(
                        "SystemMessage",
                        "UserMessage",
                        "AssistantMessage",
                        "ToolResponseMessage");
        var assistant = (org.springframework.ai.chat.messages.AssistantMessage) mapped.get(2);
        assertThat(assistant.getToolCalls()).singleElement().satisfies(mappedCall -> {
            assertThat(mappedCall.id()).isEqualTo("call-1");
            assertThat(mappedCall.name()).isEqualTo("lookup");
            assertThat(JsonCodec.read(mappedCall.arguments()))
                    .isEqualTo(Map.of("query", "S02"));
        });
        var result = (ToolResponseMessage) mapped.get(3);
        assertThat(result.getResponses()).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo("call-1");
            assertThat(response.name()).isEqualTo("lookup");
            assertThat(response.responseData()).isEqualTo("found");
        });
    }

    @Test
    void schemaCallbacksCannotExecuteToolsInsideSpringAi() {
        ToolDefinition definition = ToolDefinition.readOnlyText(
                "lookup",
                "Lookup a value",
                "{\"type\":\"object\"}");

        var callbacks = mapper.toToolCallbacks(List.of(definition));

        assertThat(callbacks).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("lookup");
            assertThat(callback.getToolDefinition().inputSchema())
                    .isEqualTo("{\"type\":\"object\"}");
            assertThatThrownBy(() -> callback.call("{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Runtime");
        });
    }

    @Test
    void validatesProviderEndpointWithoutLeakingCredentialsIntoConfiguration() {
        assertThatThrownBy(() -> new OllamaModelConfiguration(
                URI.create("http://user:secret@localhost:11434"),
                "model",
                128,
                0,
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");

        assertThat(OllamaModelConfiguration.local("model").baseUrl())
                .isEqualTo(URI.create("http://localhost:11434"));
    }
}
