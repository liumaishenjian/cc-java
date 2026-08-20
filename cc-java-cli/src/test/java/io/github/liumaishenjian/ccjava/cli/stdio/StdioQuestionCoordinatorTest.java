package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.domain.UserQuestionOption;
import io.github.liumaishenjian.ccjava.domain.UserQuestionRequest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StdioQuestionCoordinatorTest {
    @Test
    void onlyFirstMatchingAnswerCompletesAndDuplicateOrLateAnswersAreRejected() throws Exception {
        AtomicReference<UserQuestionRequest> emitted = new AtomicReference<>();
        try (StdioQuestionCoordinator coordinator = new StdioQuestionCoordinator(emitted::set)) {
            var answer = CompletableFuture.supplyAsync(() -> coordinator.ask(request("call-1"),
                    io.github.liumaishenjian.ccjava.core.CancellationToken.none()));
            await(emitted);
            assertThat(coordinator.resolve("other", "safe")).isFalse();
            assertThat(coordinator.resolve("call-1", "missing")).isFalse();
            assertThat(coordinator.resolve("call-1", "safe")).isTrue();
            assertThat(answer.get(2, TimeUnit.SECONDS).optionId()).isEqualTo("safe");
            assertThat(coordinator.resolve("call-1", "safe")).isFalse();
        }
    }

    @Test
    void cancellationAndDisconnectReleaseWaiterWithoutFabricatingAnswer() throws Exception {
        CancellationSource source = new CancellationSource();
        AtomicReference<UserQuestionRequest> emitted = new AtomicReference<>();
        try (StdioQuestionCoordinator coordinator = new StdioQuestionCoordinator(emitted::set)) {
            var cancelled = CompletableFuture.supplyAsync(() -> coordinator.ask(request("cancel"), source.token()));
            await(emitted);
            source.cancel();
            assertThatThrownBy(() -> cancelled.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        }

        AtomicReference<UserQuestionRequest> second = new AtomicReference<>();
        StdioQuestionCoordinator disconnected = new StdioQuestionCoordinator(second::set);
        var waiting = CompletableFuture.supplyAsync(() -> disconnected.ask(request("disconnect"),
                io.github.liumaishenjian.ccjava.core.CancellationToken.none()));
        await(second);
        disconnected.close();
        assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(disconnected.resolve("disconnect", "safe")).isFalse();
    }

    private static UserQuestionRequest request(String callId) {
        return new UserQuestionRequest(callId, "Choose", List.of(
                new UserQuestionOption("safe", "Safe", "Staged"),
                new UserQuestionOption("fast", "Fast", "Direct")));
    }

    private static void await(AtomicReference<UserQuestionRequest> emitted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (emitted.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(emitted.get()).isNotNull();
    }
}
