package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelCallContext;
import io.github.liumaishenjian.ccjava.core.ModelFailureKind;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelTurnObserver;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 验证 Adapter 的线程、取消、截止时间和安全错误边界。
 */
class SpringAiOllamaModelGatewayTest {

    @Test
    void drainsProviderSignalsOnCallingThreadAndEmitsOrderedDeltas() throws Exception {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        StreamingChatModel model = prompt -> {
            capturedPrompt.set(prompt);
            return Flux.just(
                            response("A", null, null),
                            response("B", "stop", new DefaultUsage(3, 2, 5)))
                    .publishOn(Schedulers.parallel());
        };
        SpringAiOllamaModelGateway gateway = gateway(model, Clock.systemUTC());
        List<String> deltas = new ArrayList<>();
        List<String> threads = new ArrayList<>();
        String callerThread = Thread.currentThread().getName();

        var turn = gateway.complete(request(), new ModelCallContext(
                delta -> {
                    deltas.add(delta);
                    threads.add(Thread.currentThread().getName());
                },
                CancellationToken.none(),
                Optional.empty(),
                1));

        assertThat(deltas).containsExactly("A", "B");
        assertThat(threads).containsOnly(callerThread);
        assertThat(turn.assistantMessage().text()).isEqualTo("AB");
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(turn.usage()).isPresent();
        assertThat(capturedPrompt.get().getInstructions()).hasSize(1);
    }

    @Test
    void requestsProviderChunksOneAtATimeWithoutUnboundedDemand() throws Exception {
        List<Long> demands = new ArrayList<>();
        StreamingChatModel model = ignored -> Flux.just(
                        response("A", null, null),
                        response("B", "stop", null))
                .doOnRequest(demands::add);
        SpringAiOllamaModelGateway gateway = gateway(model, Clock.systemUTC());

        var turn = gateway.complete(request());

        assertThat(turn.assistantMessage().text()).isEqualTo("AB");
        assertThat(demands)
                .isNotEmpty()
                .doesNotContain(Long.MAX_VALUE)
                .allMatch(demand -> demand == 1L);
    }

    @Test
    void cancellationDisposesSubscriptionAndReturnsStableKind() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        StreamingChatModel model = ignored -> Flux.<ChatResponse>never()
                .doOnSubscribe(subscription -> subscribed.countDown())
                .doOnCancel(disposed::countDown);
        SpringAiOllamaModelGateway gateway = gateway(model, Clock.systemUTC());
        CancellationSource cancellation = new CancellationSource();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> gateway.complete(
                    request(),
                    new ModelCallContext(
                            ModelTurnObserver.noop(),
                            cancellation.token(),
                            Optional.empty(),
                            1)));
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.cancel()).isTrue();

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ModelGatewayException.class)
                    .rootCause()
                    .satisfies(root -> assertThat(
                            ((ModelGatewayException) root).kind())
                            .isEqualTo(ModelFailureKind.CANCELLED));
            assertThat(disposed.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsReachedDeadlineBeforeSubscribing() {
        Instant now = Instant.parse("2026-07-28T12:00:00Z");
        AtomicReference<Boolean> subscribed = new AtomicReference<>(false);
        StreamingChatModel model = ignored -> Flux.defer(() -> {
            subscribed.set(true);
            return Flux.just(response("late", "stop", null));
        });
        SpringAiOllamaModelGateway gateway = gateway(
                model,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> gateway.complete(
                request(),
                new ModelCallContext(
                        ModelTurnObserver.noop(),
                        CancellationToken.none(),
                        Optional.of(now),
                        1)))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> assertThat(
                        ((ModelGatewayException) error).kind())
                        .isEqualTo(ModelFailureKind.DEADLINE_EXCEEDED));
        assertThat(subscribed.get()).isFalse();
    }

    @Test
    void rejectsQueuedSignalWhenDeadlineArrivesDuringPollWithoutPublishingDelta() {
        Instant beforeDeadline = Instant.parse("2026-07-28T12:00:00Z");
        Instant deadline = beforeDeadline.plusSeconds(1);
        Clock clock = new DeadlineDuringPollClock(
                beforeDeadline,
                deadline.plusSeconds(1));
        StreamingChatModel model = ignored ->
                Flux.just(response("late", "stop", null));
        SpringAiOllamaModelGateway gateway = gateway(model, clock);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> gateway.complete(
                request(),
                new ModelCallContext(
                        deltas::add,
                        CancellationToken.none(),
                        Optional.of(deadline),
                        1)))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> assertThat(
                        ((ModelGatewayException) error).kind())
                        .isEqualTo(ModelFailureKind.DEADLINE_EXCEEDED));
        assertThat(deltas).isEmpty();
    }

    @Test
    void mapsRateLimitWithoutEchoingResponseOrHeaderSecrets() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer secret-canary");
        WebClientResponseException providerFailure = WebClientResponseException.create(
                429,
                "Too Many Requests",
                headers,
                "response-secret-canary".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ModelGatewayException mapped = SpringAiFailureMapper.map(providerFailure, false);

        assertThat(mapped.kind()).isEqualTo(ModelFailureKind.RATE_LIMITED);
        assertThat(mapped.retryable()).isTrue();
        assertThat(mapped.getMessage())
                .doesNotContain("secret-canary")
                .doesNotContain("response-secret");
    }

    @Test
    void marksErrorsAfterVisibleContentAsPartialAndNeverRetriesInsideAdapter() {
        StreamingChatModel model = ignored -> Flux.concat(
                Flux.just(response("visible", null, null)),
                Flux.error(new IllegalStateException("provider-secret")));
        SpringAiOllamaModelGateway gateway = gateway(model, Clock.systemUTC());
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> gateway.complete(
                request(),
                new ModelCallContext(
                        deltas::add,
                        CancellationToken.none(),
                        Optional.empty(),
                        1)))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> {
                    ModelGatewayException modelError = (ModelGatewayException) error;
                    assertThat(modelError.partialResponse()).isTrue();
                    assertThat(modelError.getMessage()).doesNotContain("provider-secret");
                });
        assertThat(deltas).containsExactly("visible");
    }

    @Test
    void rejectsOverflowBeforePublishingCurrentDeltaAndCancelsSubscription() {
        CountDownLatch disposed = new CountDownLatch(1);
        StreamingChatModel model = ignored -> Flux.concat(
                        Flux.just(
                                response("A", null, null),
                                response("你好", "stop", null)),
                        Flux.never())
                .doOnCancel(disposed::countDown);
        SpringAiOllamaModelGateway gateway = new SpringAiOllamaModelGateway(
                model,
                OllamaModelConfiguration.local("test-model"),
                Clock.systemUTC(),
                5,
                2);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> gateway.complete(
                request(),
                new ModelCallContext(
                        deltas::add,
                        CancellationToken.none(),
                        Optional.empty(),
                        1)))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> {
                    ModelGatewayException modelError = (ModelGatewayException) error;
                    assertThat(modelError.kind())
                            .isEqualTo(ModelFailureKind.RESPONSE_LIMIT_EXCEEDED);
                    assertThat(modelError.retryable()).isFalse();
                    assertThat(modelError.partialResponse()).isTrue();
                    assertThat(modelError.getMessage())
                            .isEqualTo("模型响应超过本地安全上限")
                            .doesNotContain("你好");
                });
        assertThat(deltas).containsExactly("A");
        assertThat(disposed.getCount()).isZero();
    }

    @Test
    void mapsEmptyToolCallsFinishToStructuredIncompleteResponse() {
        StreamingChatModel model = ignored ->
                Flux.just(response("ordinary", List.of(), "tool_calls", null));
        SpringAiOllamaModelGateway gateway = gateway(model, Clock.systemUTC());

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> {
                    ModelGatewayException modelError = (ModelGatewayException) error;
                    assertThat(modelError.kind())
                            .isEqualTo(ModelFailureKind.INCOMPLETE_RESPONSE);
                    assertThat(modelError.retryable()).isFalse();
                    assertThat(modelError.getMessage())
                            .isEqualTo("Provider 流未完整结束")
                            .doesNotContain("ordinary");
                });
    }

    private static SpringAiOllamaModelGateway gateway(
            StreamingChatModel model,
            Clock clock) {
        return new SpringAiOllamaModelGateway(
                model,
                OllamaModelConfiguration.local("test-model"),
                clock);
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new SessionId("session"),
                new RunId("run"),
                1,
                List.of(new UserMessage("hello")),
                List.of());
    }

    private static ChatResponse response(
            String text,
            String finishReason,
            DefaultUsage usage) {
        return response(text, List.of(), finishReason, usage);
    }

    private static ChatResponse response(
            String text,
            List<AssistantMessage.ToolCall> calls,
            String finishReason,
            DefaultUsage usage) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .toolCalls(calls)
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata responseMetadata = usage == null
                ? new ChatResponseMetadata()
                : ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(
                List.of(new Generation(output, generationMetadata)),
                responseMetadata);
    }

    /**
     * 前三次读取返回截止时间之前，模拟信号在 poll 返回后才跨过 Deadline。
     */
    private static final class DeadlineDuringPollClock extends Clock {

        private final Instant beforeDeadline;
        private final Instant afterDeadline;
        private final AtomicInteger reads = new AtomicInteger();

        private DeadlineDuringPollClock(
                Instant beforeDeadline,
                Instant afterDeadline) {
            this.beforeDeadline = beforeDeadline;
            this.afterDeadline = afterDeadline;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("测试 Clock 只支持 UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return reads.incrementAndGet() <= 3
                    ? beforeDeadline
                    : afterDeadline;
        }
    }
}
