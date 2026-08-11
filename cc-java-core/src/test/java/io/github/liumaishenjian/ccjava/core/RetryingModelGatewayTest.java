package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRY_EXHAUSTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证有界模型重试不会重复可见 Delta，并能在退避期间响应取消。
 */
class RetryingModelGatewayTest {

    @Test
    void retriesOnlyBeforeVisibleOutputAndThenReturnsSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ModelGatewayException(RETRYABLE, "transient");
            }
            observer.onTextDelta("ok");
            return ModelTurn.text("ok");
        };
        RetryingModelGateway gateway = new RetryingModelGateway(
                delegate,
                immediatePolicy(3));

        ModelTurn turn = gateway.complete(
                request(),
                ignored -> {
                },
                CancellationToken.none());

        assertThat(attempts).hasValue(3);
        assertThat(turn.assistantMessage().text()).isEqualTo("ok");
    }

    @Test
    void respectsTypedRetryAfterBeforeNextAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ModelGatewayException(
                        RETRYABLE,
                        "rate limited",
                        ModelFailureSummary.firstAttempt(
                                ModelFailureCategory.RATE_LIMITED,
                                java.util.Optional.of(ModelHttpStatusClass.CLIENT_ERROR),
                                false),
                        Duration.ofMillis(30),
                        null);
            }
            return ModelTurn.text("ok");
        };
        long started = System.nanoTime();
        ModelTurn turn = new RetryingModelGateway(delegate, immediatePolicy(2)).complete(
                request(), ignored -> { }, CancellationToken.none());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(turn.assistantMessage().text()).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(20);
    }

    @Test
    void marksFailureAfterVisibleDeltaAsIncompleteWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            attempts.incrementAndGet();
            observer.onTextDelta("partial");
            throw new ModelGatewayException(RETRYABLE, "connection lost");
        };
        RetryingModelGateway gateway = new RetryingModelGateway(
                delegate,
                immediatePolicy(3));

        assertThatThrownBy(() -> gateway.complete(
                request(),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> assertThat(
                        ((ModelGatewayException) failure).kind())
                        .isEqualTo(INCOMPLETE_STREAM));
        assertThat(attempts).hasValue(1);
    }

    @Test
    void reportsRetryExhaustionAfterBoundedAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            attempts.incrementAndGet();
            throw new ModelGatewayException(
                    RETRYABLE,
                    "busy SECRET_PROVIDER_TEXT",
                    new ModelFailureSummary(
                            ModelFailureCategory.PROVIDER_UNAVAILABLE,
                            java.util.Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                            1,
                            false));
        };
        RetryingModelGateway gateway = new RetryingModelGateway(
                delegate,
                immediatePolicy(3));

        assertThatThrownBy(() -> gateway.complete(
                request(),
                ignored -> {
                },
                CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(failure -> {
                    ModelGatewayException modelFailure = (ModelGatewayException) failure;
                    assertThat(modelFailure.kind()).isEqualTo(RETRY_EXHAUSTED);
                    assertThat(modelFailure.summary()).contains(new ModelFailureSummary(
                            ModelFailureCategory.PROVIDER_UNAVAILABLE,
                            java.util.Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                            3,
                            false));
                    assertThat(modelFailure.summary().orElseThrow().toString())
                            .doesNotContain("SECRET_PROVIDER_TEXT");
                });
        assertThat(attempts).hasValue(3);
    }

    @Test
    void exposesExactAttemptScopeWithoutChangingRetryBehavior() throws Exception {
        List<Integer> correlatedAttempts = new java.util.ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            correlatedAttempts.add(ModelDiagnosticAttempt.current());
            if (attempts.incrementAndGet() < 3) {
                throw new ModelGatewayException(RETRYABLE, "transient");
            }
            return ModelTurn.text("ok");
        };

        ModelTurn turn = new RetryingModelGateway(delegate, immediatePolicy(3)).complete(
                request(), ignored -> { }, CancellationToken.none());

        assertThat(turn.assistantMessage().text()).isEqualTo("ok");
        assertThat(correlatedAttempts).containsExactly(1, 2, 3);
        assertThat(ModelDiagnosticAttempt.current()).isEqualTo(1);
    }

    @Test
    void cancellationInterruptsBackoffBeforeNextAttempt() throws Exception {
        CountDownLatch firstFailure = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        StreamingModelGateway delegate = (request, observer, cancellation) -> {
            attempts.incrementAndGet();
            firstFailure.countDown();
            throw new ModelGatewayException(RETRYABLE, "busy");
        };
        RetryingModelGateway gateway = new RetryingModelGateway(
                delegate,
                new ModelRetryPolicy(2, List.of(Duration.ofSeconds(5))));
        CancellationSource cancellation = new CancellationSource();

        CompletableFuture<ModelTurn> running = CompletableFuture.supplyAsync(() -> {
            try {
                return gateway.complete(
                        request(),
                        ignored -> {
                        },
                        cancellation.token());
            } catch (ModelGatewayException failure) {
                throw new RuntimeException(failure);
            }
        });
        assertThat(firstFailure.await(2, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();

        assertThatThrownBy(() -> running.get(2, TimeUnit.SECONDS))
                .satisfies(failure -> {
                    Throwable asynchronousFailure = failure.getCause();
                    assertThat(asynchronousFailure)
                            .isInstanceOf(RuntimeException.class);
                    assertThat(asynchronousFailure.getCause())
                            .isInstanceOfSatisfying(
                                    ModelGatewayException.class,
                                    modelFailure -> assertThat(modelFailure.kind())
                                            .isEqualTo(CANCELLED));
                });
        assertThat(attempts).hasValue(1);
    }

    private static ModelRetryPolicy immediatePolicy(int attempts) {
        return new ModelRetryPolicy(
                attempts,
                java.util.Collections.nCopies(
                        attempts - 1,
                        Duration.ZERO));
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                List.of(new UserMessage("test")),
                List.of());
    }
}
