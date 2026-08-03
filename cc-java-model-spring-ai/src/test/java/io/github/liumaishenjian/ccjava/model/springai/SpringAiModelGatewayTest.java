package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.errors.OpenAIRetryableException;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Spring AI Adapter 的流式聚合、原始 Tool Call 保留与取消边界。
 *
 * @since 0.1.0
 */
class SpringAiModelGatewayTest {

    @Test
    void aggregatesTextAndMapsFinishUsageAndProviderModel() throws Exception {
        RecordingChatModel model = new RecordingChatModel(Flux.just(
                response("你", null, null),
                response("好", "stop", new DefaultUsage(7, 2, 9))));
        SpringAiModelGateway gateway = new SpringAiModelGateway(model, "test-model");
        List<String> deltas = new ArrayList<>();

        ModelTurn turn = gateway.complete(request(List.of()), deltas::add, CancellationToken.none());

        assertThat(deltas).containsExactly("你", "好");
        assertThat(turn.assistantMessage().text()).isEqualTo("你好");
        assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(turn.metadata().usage()).hasValueSatisfying(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(7);
            assertThat(usage.outputTokens()).isEqualTo(2);
            assertThat(usage.totalTokens()).isEqualTo(9);
        });
        assertThat(turn.metadata().providerModel()).contains("provider-model");
    }

    @Test
    void exposesDefinitionsButReturnsToolCallsWithoutExecutingThem() throws Exception {
        ToolDefinition definition = ToolDefinition.readOnlyText(
                "read_probe",
                "读取探针值",
                """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
                """);
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "read_probe",
                "{\"path\":\"README.md\"}");
        RecordingChatModel model = new RecordingChatModel(Flux.just(toolResponse(call)));
        SpringAiModelGateway gateway = new SpringAiModelGateway(model, "test-model");

        ModelTurn turn = gateway.complete(
                request(List.of(definition)),
                ignored -> {
                },
                CancellationToken.none());

        assertThat(turn.assistantMessage().toolCalls()).singleElement().satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo("call-1");
            assertThat(mapped.name()).isEqualTo("read_probe");
            assertThat(mapped.arguments().values()).containsEntry("path", "README.md");
        });
        assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);

        OpenAiChatOptions options = (OpenAiChatOptions) model.prompt().getOptions();
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("read_probe");
            assertThatThrownBy(() -> callback.call("{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not execute tools");
        });
    }

    @Test
    void preservesMultipleToolCallOrderIdsAndArguments() throws Exception {
        AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "read_probe",
                "{\"path\":\"README.md\"}");
        AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(
                "call-2",
                "function",
                "read_probe",
                "{\"path\":\"pom.xml\"}");
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(toolResponse(List.of(first, second)))),
                "test-model");

        ModelTurn turn = gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none());

        assertThat(turn.assistantMessage().toolCalls())
                .extracting(io.github.liumaishenjian.ccjava.domain.ToolCall::id)
                .containsExactly("call-1", "call-2");
        assertThat(turn.assistantMessage().toolCalls())
                .extracting(call -> call.arguments().values().get("path"))
                .containsExactly("README.md", "pom.xml");
    }

    @Test
    void rejectsToolCallsWithoutToolCallsFinishReason() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "read_probe",
                "{\"path\":\"README.md\"}");
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        ChatResponse inconsistent = new ChatResponse(
                List.of(new Generation(
                        message,
                        ChatGenerationMetadata.builder()
                                .finishReason("stop")
                                .build())),
                ChatResponseMetadata.builder().model("provider-model").build());
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(inconsistent)),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ModelGatewayException) failure).kind())
                        .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM));
    }

    @Test
    void rejectsMalformedToolArgumentsAsStructuredGatewayFailure() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-bad",
                "function",
                "read_probe",
                "not-json");
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(toolResponse(call))),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .hasMessage("Provider returned invalid Tool Call arguments");
    }

    @Test
    void rejectsCleanStreamCompletionWithoutFinishReasonAsIncomplete() {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(response("partial", null, null))),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ModelGatewayException) failure).kind())
                        .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM));
    }

    @Test
    void emptyStreamDoesNotClaimProviderOutput() {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.empty()),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> {
                    ModelGatewayException modelFailure = (ModelGatewayException) failure;
                    assertThat(modelFailure.kind())
                            .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM);
                    assertThat(modelFailure.summary()).hasValueSatisfying(summary -> {
                        assertThat(summary.category())
                                .isEqualTo(ModelFailureCategory.INVALID_RESPONSE);
                        assertThat(summary.receivedOutput()).isFalse();
                    });
                });
    }

    @Test
    void metadataOnlyRawResponseClaimsProviderResponse() {
        ChatResponse metadataOnly = new ChatResponse(
                List.of(),
                ChatResponseMetadata.builder().model("provider-model").build());
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(metadataOnly)),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> {
                    ModelGatewayException modelFailure = (ModelGatewayException) failure;
                    assertThat(modelFailure.kind())
                            .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM);
                    assertThat(modelFailure.summary()).hasValueSatisfying(summary ->
                            assertThat(summary.receivedOutput()).isTrue());
                });
    }

    @Test
    void classifiesFailureAfterDeltaAsIncompleteAndKeepsPublishedDelta() {
        List<String> deltas = new ArrayList<>();
        Flux<ChatResponse> responses = Flux.concat(
                Flux.just(response("partial", null, null)),
                Flux.error(new OpenAIRetryableException("connection lost")));
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(responses),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                deltas::add,
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ModelGatewayException) failure).kind())
                        .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM));
        assertThat(deltas).containsExactly("partial");
    }

    @Test
    void classifiesRetryableFailureBeforeFirstResponse() {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.error(
                        new OpenAIRetryableException("temporarily unavailable"))),
                "test-model");

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ModelGatewayException) failure).kind())
                        .isEqualTo(ModelGatewayException.FailureKind.RETRYABLE));
    }

    @Test
    void cancellationDisposesTheProviderSubscription() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        RecordingChatModel model = new RecordingChatModel(
                Flux.<ChatResponse>never().doOnCancel(() -> disposed.set(true)));
        SpringAiModelGateway gateway = new SpringAiModelGateway(model, "test-model");
        CancellationSource cancellation = new CancellationSource();

        CompletableFuture<ModelTurn> running = CompletableFuture.supplyAsync(() -> {
            try {
                return gateway.complete(request(List.of()), ignored -> {
                }, cancellation.token());
            } catch (ModelGatewayException exception) {
                throw new RuntimeException(exception);
            }
        });
        awaitPrompt(model);
        cancellation.cancel();

        assertThatThrownBy(() -> running.get(2, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ModelGatewayException.class)
                .rootCause()
                .hasMessage("Model request cancelled");
        assertThat(disposed).isTrue();
    }

    @Test
    void observerFailureCannotAbortTheModelTurn() throws Exception {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(Flux.just(response("answer", "stop", null))),
                "test-model");

        ModelTurn turn = gateway.complete(
                request(List.of()),
                ignored -> {
                    throw new IllegalStateException("broken renderer");
                },
                CancellationToken.none());

        assertThat(turn.assistantMessage().text()).isEqualTo("answer");
    }

    private static void awaitPrompt(RecordingChatModel model) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (model.prompt() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(model.prompt()).isNotNull();
    }

    private static ModelRequest request(List<ToolDefinition> definitions) {
        return new ModelRequest(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                List.of(new UserMessage("test")),
                definitions);
    }

    private static ChatResponse response(String text, String finishReason, DefaultUsage usage) {
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata.Builder responseMetadata = ChatResponseMetadata.builder()
                .model("provider-model");
        if (usage != null) {
            responseMetadata.usage(usage);
        }
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text), generationMetadata)),
                responseMetadata.build());
    }

    private static ChatResponse toolResponse(AssistantMessage.ToolCall call) {
        return toolResponse(List.of(call));
    }

    private static ChatResponse toolResponse(List<AssistantMessage.ToolCall> calls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(calls)
                .build();
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason("tool_calls")
                .build();
        return new ChatResponse(
                List.of(new Generation(message, metadata)),
                ChatResponseMetadata.builder().model("provider-model").build());
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Flux<ChatResponse> responses;
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        private RecordingChatModel(Flux<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.prompt.set(prompt);
            return responses;
        }

        private Prompt prompt() {
            return prompt.get();
        }
    }
}
