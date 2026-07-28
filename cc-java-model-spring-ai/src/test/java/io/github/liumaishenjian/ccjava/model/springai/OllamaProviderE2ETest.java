package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelCallContext;
import io.github.liumaishenjian.ccjava.core.ModelFailureKind;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelTurnObserver;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 显式启用的真实 Ollama/Spring AI 协议实验。
 *
 * <p>普通 CI 不设置 {@code cc.java.ollama.e2e.model}，因此不需要网络、模型
 * 或 API Key。启用者必须自行固定 Ollama 版本与模型 Digest；断言只针对结构、
 * ID、事件和终态，不断言固定自然语言。</p>
 */
@EnabledIfSystemProperty(named = "cc.java.ollama.e2e.model", matches = ".+")
class OllamaProviderE2ETest {

    private static final SessionId SESSION_ID = new SessionId("ollama-e2e-session");
    private static final RunId RUN_ID = new RunId("ollama-e2e-run");

    @Test
    void provesTextToolsResultUsageLengthAndClientCancellation() throws Exception {
        String model = System.getProperty("cc.java.ollama.e2e.model");
        URI baseUrl = URI.create(Optional.ofNullable(
                        System.getProperty("cc.java.ollama.e2e.base-url"))
                .orElse("http://localhost:11434"));
        SpringAiOllamaModelGateway gateway = SpringAiOllamaModelGateway.create(
                configuration(baseUrl, model, 256));

        List<String> textDeltas = new ArrayList<>();
        var textTurn = gateway.complete(
                request(1, List.of(new UserMessage(
                        "Reply with one short sentence confirming streaming.")), List.of()),
                context(textDeltas::add, CancellationToken.none()));
        assertThat(textDeltas).isNotEmpty();
        assertThat(textTurn.assistantMessage().text()).isNotBlank();
        assertThat(textTurn.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(textTurn.usage()).isPresent();

        List<ToolDefinition> definitions = List.of(
                ToolDefinition.readOnlyText(
                        "sum_numbers",
                        "Add exactly two integer inputs.",
                        """
                        {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                        "required":["a","b"],"additionalProperties":false}
                        """),
                ToolDefinition.readOnlyText(
                        "repeat_text",
                        "Return the supplied text.",
                        """
                        {"type":"object","properties":{"text":{"type":"string"}},
                        "required":["text"],"additionalProperties":false}
                        """));
        UserMessage toolPrompt = new UserMessage(
                "Call both tools in this single response: sum_numbers with a=2,b=3 and "
                        + "repeat_text with text=\"s02\". Do not answer directly.");
        var toolTurn = gateway.complete(request(
                2,
                List.of(toolPrompt),
                definitions));
        assertThat(toolTurn.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(toolTurn.assistantMessage().toolCalls())
                .extracting(call -> call.id() + ":" + call.name())
                .hasSize(2)
                .doesNotHaveDuplicates();
        assertThat(toolTurn.assistantMessage().toolCalls())
                .extracting(call -> call.name())
                .containsExactly("sum_numbers", "repeat_text");

        List<AgentMessage> resultHistory = new ArrayList<>();
        resultHistory.add(toolPrompt);
        resultHistory.add(toolTurn.assistantMessage());
        for (var call : toolTurn.assistantMessage().toolCalls()) {
            resultHistory.add(new ToolResultMessage(ToolResult.success(
                    call.id(),
                    call.name(),
                    call.name().equals("sum_numbers")
                            ? "{\"result\":5}"
                            : "{\"result\":\"s02\"}")));
        }
        var finalTurn = gateway.complete(request(3, resultHistory, definitions));
        assertThat(finalTurn.assistantMessage().text()).isNotBlank();
        assertThat(finalTurn.assistantMessage().toolCalls()).isEmpty();

        SpringAiOllamaModelGateway lengthGateway = SpringAiOllamaModelGateway.create(
                configuration(baseUrl, model, 1));
        var lengthTurn = lengthGateway.complete(request(
                4,
                List.of(new UserMessage(
                        "Write at least ten numbered sentences and do not stop early.")),
                List.of()));
        assertThat(lengthTurn.finishReason()).isEqualTo(ModelFinishReason.LENGTH);

        CancellationSource cancellation = new CancellationSource();
        AtomicInteger cancelledDeltas = new AtomicInteger();
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.complete(
                request(
                        5,
                        List.of(new UserMessage(
                                "Write a numbered list of 200 distinct short facts.")),
                        List.of()),
                context(delta -> {
                    cancelledDeltas.incrementAndGet();
                    cancellation.cancel();
                }, cancellation.token())))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> assertThat(
                        ((ModelGatewayException) error).kind())
                        .isEqualTo(ModelFailureKind.CANCELLED));
        assertThat(cancelledDeltas).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(30));
    }

    private static OllamaModelConfiguration configuration(
            URI baseUrl,
            String model,
            int maxOutputTokens) {
        return new OllamaModelConfiguration(baseUrl, model, maxOutputTokens, 0, false);
    }

    private static ModelRequest request(
            int turn,
            List<AgentMessage> messages,
            List<ToolDefinition> definitions) {
        return new ModelRequest(SESSION_ID, RUN_ID, turn, messages, definitions);
    }

    private static ModelCallContext context(
            ModelTurnObserver observer,
            CancellationToken token) {
        return new ModelCallContext(observer, token, Optional.empty(), 1);
    }
}
