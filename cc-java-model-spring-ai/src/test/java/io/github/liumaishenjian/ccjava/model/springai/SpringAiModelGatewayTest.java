package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.models.ErrorObject;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelDiagnosticRecorder;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelRetryPolicy;
import io.github.liumaishenjian.ccjava.core.RetryingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
    void timeoutAfterAnyProviderFrameIsIncompleteEvenWithoutVisibleDelta() {
        Flux<ChatResponse> responses = Flux.concat(
                Flux.just(response("", null, null)),
                Flux.error(new java.util.concurrent.TimeoutException("private timeout")));
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                new RecordingChatModel(responses),
                "test-model");

        assertThat(captureFailure(gateway).kind())
                .isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM);
    }

    @ParameterizedTest(name = "{0} before first frame -> {2}/{3}")
    @MethodSource("transportFailures")
    void classifiesConcreteTransportFailuresBeforeFirstFrame(
            String name,
            Throwable transportFailure,
            ModelFailureCategory expectedCategory,
            ModelFailureReason expectedReason) {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        SpringAiModelGateway gateway = gatewayFailure(transportFailure, events);

        ModelGatewayException failure = captureFailure(gateway);

        assertThat(failure.kind()).isEqualTo(ModelGatewayException.FailureKind.RETRYABLE);
        assertThat(failure.summary()).hasValueSatisfying(summary -> {
            assertThat(summary.category()).isEqualTo(expectedCategory);
            assertThat(summary.receivedOutput()).isFalse();
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo(expectedReason);
            assertThat(event.receivedProviderFrame()).isFalse();
        });
        assertThat(failure.getMessage()).doesNotContain("private");
    }

    @ParameterizedTest(name = "{0} after provider frame -> incomplete stream")
    @MethodSource("transportFailures")
    void classifiesEveryConcreteTransportFailureAfterAnyFrameAsIncompleteStream(
            String name,
            Throwable transportFailure,
            ModelFailureCategory ignoredCategory,
            ModelFailureReason expectedRootReason) {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ChatResponse> responses = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.concat(
                    Flux.just(response("", null, null)),
                    Flux.error(transportFailure));
        });
        RetryingModelGateway gateway = new RetryingModelGateway(
                gateway(responses, events),
                new ModelRetryPolicy(3, List.of(Duration.ZERO, Duration.ZERO)));

        ModelGatewayException failure = captureFailure(gateway);

        assertThat(failure.kind()).isEqualTo(ModelGatewayException.FailureKind.INCOMPLETE_STREAM);
        assertThat(subscriptions).hasValue(1);
        assertThat(failure.summary()).hasValueSatisfying(summary -> {
            assertThat(summary.category()).isEqualTo(ModelFailureCategory.INCOMPLETE_STREAM);
            assertThat(summary.receivedOutput()).isTrue();
        });
        assertThat(events).singleElement().satisfies(event -> {
            ModelFailureReason expected = expectedRootReason == ModelFailureReason.TIMEOUT
                    ? ModelFailureReason.TIMEOUT
                    : ModelFailureReason.TRANSPORT_CLOSED;
            assertThat(event.reason()).isEqualTo(expected);
            assertThat(event.receivedProviderFrame()).isTrue();
        });
    }

    private static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of("dns", new java.net.UnknownHostException("private dns"),
                        ModelFailureCategory.NETWORK_ERROR, ModelFailureReason.NETWORK_IO),
                Arguments.of("connect", new java.net.ConnectException("private connect"),
                        ModelFailureCategory.NETWORK_ERROR, ModelFailureReason.NETWORK_IO),
                Arguments.of("reset", new java.net.SocketException("private reset"),
                        ModelFailureCategory.NETWORK_ERROR, ModelFailureReason.NETWORK_IO),
                Arguments.of("socket-timeout", new java.net.SocketTimeoutException("private socket timeout"),
                        ModelFailureCategory.REQUEST_TIMEOUT, ModelFailureReason.TIMEOUT),
                Arguments.of("http-timeout", new java.net.http.HttpTimeoutException("private http timeout"),
                        ModelFailureCategory.REQUEST_TIMEOUT, ModelFailureReason.TIMEOUT),
                Arguments.of("tls", new javax.net.ssl.SSLHandshakeException("private tls"),
                        ModelFailureCategory.NETWORK_ERROR, ModelFailureReason.NETWORK_IO));
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
    void classifiesOnlyExactStructuredContextCodeAsOverflow() {
        SpringAiModelGateway gateway = gatewayFailure(badRequest(
                "context_length_exceeded", "sensitive body must not escape"));

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()), ignored -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> {
                    ModelGatewayException modelFailure = (ModelGatewayException) failure;
                    assertThat(modelFailure.kind())
                            .isEqualTo(ModelGatewayException.FailureKind.CONTEXT_OVERFLOW);
                    assertThat(modelFailure.getMessage())
                            .doesNotContain("sensitive body")
                            .doesNotContain("context_length_exceeded");
                });
    }

    @Test
    void otherBadRequestRemainsPermanentEvenWhenMessageMentionsContext() {
        SpringAiModelGateway gateway = gatewayFailure(badRequest(
                "invalid_parameter", "context_length_exceeded secret"));

        assertThatThrownBy(() -> gateway.complete(
                request(List.of()), ignored -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> {
                    ModelGatewayException modelFailure = (ModelGatewayException) failure;
                    assertThat(modelFailure.kind())
                            .isEqualTo(ModelGatewayException.FailureKind.PERMANENT);
                    assertThat(modelFailure.summary()).hasValueSatisfying(summary ->
                            assertThat(summary.category())
                                    .isEqualTo(ModelFailureCategory.INVALID_REQUEST));
                    assertThat(modelFailure.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void badRequestWithoutStructuredCodeFailsClosed() {
        SpringAiModelGateway gateway = gatewayFailure(BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .code(java.util.Optional.empty())
                        .message("context_length_exceeded private")
                        .param(java.util.Optional.empty())
                        .type("invalid_request_error")
                        .build())
                .build());

        ModelGatewayException failure = captureFailure(gateway);

        assertThat(failure.kind()).isEqualTo(ModelGatewayException.FailureKind.PERMANENT);
        assertThat(failure.summary()).hasValueSatisfying(summary ->
                assertThat(summary.category()).isEqualTo(ModelFailureCategory.INVALID_REQUEST));
        assertThat(failure.getMessage()).doesNotContain("private");
    }

    @ParameterizedTest(name = "HTTP {0} -> {1}/{2}")
    @MethodSource("httpStatusClassifications")
    void classifiesHttpStatusMatrixDirectly(
            int status,
            ModelGatewayException.FailureKind expectedKind,
            ModelFailureCategory expectedCategory) {
        ModelGatewayException failure = captureFailure(gatewayFailure(
                serviceFailure(status, Headers.builder().build())));

        assertThat(failure.kind()).isEqualTo(expectedKind);
        assertThat(failure.summary()).hasValueSatisfying(summary ->
                assertThat(summary.category()).isEqualTo(expectedCategory));
    }

    private static Stream<Arguments> httpStatusClassifications() {
        return Stream.of(
                Arguments.of(408, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.REQUEST_TIMEOUT),
                Arguments.of(409, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.REQUEST_CONFLICT),
                Arguments.of(429, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.RATE_LIMITED),
                Arguments.of(500, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.PROVIDER_UNAVAILABLE),
                Arguments.of(503, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.PROVIDER_UNAVAILABLE),
                Arguments.of(529, ModelGatewayException.FailureKind.RETRYABLE,
                        ModelFailureCategory.PROVIDER_UNAVAILABLE),
                Arguments.of(400, ModelGatewayException.FailureKind.PERMANENT,
                        ModelFailureCategory.INVALID_REQUEST),
                Arguments.of(401, ModelGatewayException.FailureKind.PERMANENT,
                        ModelFailureCategory.AUTHENTICATION_FAILED),
                Arguments.of(403, ModelGatewayException.FailureKind.PERMANENT,
                        ModelFailureCategory.AUTHENTICATION_FAILED),
                Arguments.of(404, ModelGatewayException.FailureKind.PERMANENT,
                        ModelFailureCategory.INVALID_REQUEST));
    }

    @ParameterizedTest(name = "Retry-After {0}")
    @MethodSource("retryAfterHeaders")
    void acceptsOnlyUniqueDeltaSecondsAndCapsAtFiveMinutes(
            String name,
            List<String> values,
            Duration expected) {
        Headers headers = Headers.builder().put("retry-after", values).build();
        ModelGatewayException failure = captureFailure(gatewayFailure(serviceFailure(429, headers)));

        if (expected == null) {
            assertThat(failure.retryAfter()).isEmpty();
        } else {
            assertThat(failure.retryAfter()).contains(expected);
        }
    }

    private static Stream<Arguments> retryAfterHeaders() {
        return Stream.of(
                Arguments.of("unique delta-seconds", List.of("17"), Duration.ofSeconds(17)),
                Arguments.of("five minute cap", List.of("301"), Duration.ofMinutes(5)),
                Arguments.of("negative ignored", List.of("-1"), null),
                Arguments.of("decimal ignored", List.of("1.5"), null),
                Arguments.of("http date ignored", List.of("Sat, 22 Aug 2026 10:00:00 GMT"), null),
                Arguments.of("overflow ignored", List.of("99999999999"), null),
                Arguments.of("duplicate ignored", List.of("1", "2"), null));
    }

    @Test
    void discoversStructuredContextCodeInNestedCause() {
        SpringAiModelGateway gateway = gatewayFailure(
                new IllegalStateException("wrapper", badRequest(
                        "context_length_exceeded", "private")));

        assertThat(captureFailure(gateway).kind())
                .isEqualTo(ModelGatewayException.FailureKind.CONTEXT_OVERFLOW);
    }

    @Test
    void propagatesRunRemainingBudgetIntoPromptRequestTimeout() throws Exception {
        RecordingChatModel model = new RecordingChatModel(
                Flux.just(response("answer", "stop", null)));
        SpringAiModelGateway gateway = new SpringAiModelGateway(model, "test-model");
        CancellationToken bounded = new CancellationToken() {
            @Override public boolean isCancellationRequested() { return false; }
            @Override public Registration onCancellation(Runnable action) { return () -> { }; }
            @Override public java.util.Optional<Duration> remainingTime() {
                return java.util.Optional.of(Duration.ofMillis(321));
            }
        };

        gateway.complete(request(List.of()), ignored -> { }, bounded);

        OpenAiChatOptions options = (OpenAiChatOptions) model.prompt().getOptions();
        assertThat(options.getTimeout()).isEqualTo(Duration.ofMillis(321));
    }

    @Test
    void cancellationDisposesNeverTerminatingProviderPublisher() throws Exception {
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

    private static SpringAiModelGateway gatewayFailure(Throwable failure) {
        return new SpringAiModelGateway(
                new RecordingChatModel(Flux.error(failure)), "test-model");
    }

    private static SpringAiModelGateway gatewayFailure(
            Throwable failure,
            List<ModelDiagnosticEvent> events) {
        return gateway(Flux.error(failure), events);
    }

    private static SpringAiModelGateway gateway(
            Flux<ChatResponse> responses,
            List<ModelDiagnosticEvent> events) {
        return new SpringAiModelGateway(
                new RecordingChatModel(responses),
                "test-model",
                new ModelDiagnosticRecorder(
                        ModelDiagnosticMode.SAFE,
                        events::add,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> 1_000_000L));
    }

    private static BadRequestException badRequest(String code, String message) {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(error(code, message))
                .build();
    }

    private static com.openai.errors.UnexpectedStatusCodeException serviceFailure(
            int status,
            Headers headers) {
        return com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(status)
                .headers(headers)
                .error(error("status_" + status, "private"))
                .build();
    }

    private static ErrorObject error(String code, String message) {
        return ErrorObject.builder()
                .code(code)
                .message(message)
                .param(java.util.Optional.empty())
                .type("invalid_request_error")
                .build();
    }

    private static ModelGatewayException captureFailure(SpringAiModelGateway gateway) {
        try {
            gateway.complete(request(List.of()), ignored -> { }, CancellationToken.none());
            throw new AssertionError("expected ModelGatewayException");
        } catch (ModelGatewayException failure) {
            return failure;
        }
    }

    private static ModelGatewayException captureFailure(
            io.github.liumaishenjian.ccjava.core.ModelGateway gateway) {
        try {
            gateway.complete(request(List.of()));
            throw new AssertionError("expected ModelGatewayException");
        } catch (ModelGatewayException failure) {
            return failure;
        }
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
