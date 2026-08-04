package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 证伪 Run 级摘要冷却与 overflow retry 生命周期不会跨 Run 无界保留。 */
class SummaryLifecycleTest {

    private static final ContextTokenEstimator ESTIMATOR =
            new CodePointContextTokenEstimator();

    @Test
    void closeClearsRunKeysAndRejectsNewAcquireWithoutCrossRunRetention() {
        RunId firstRun = new RunId("run-lifecycle-first");
        SummaryAttemptGuard first = new SummaryAttemptGuard(firstRun);
        assertThat(first.tryAcquire(firstRun, 1, SummaryTier.C3_ROLLING)).isTrue();
        assertThat(first.tryAcquire(firstRun, 2, SummaryTier.C4_FULL)).isTrue();
        assertThat(first.retainedAttemptCount()).isEqualTo(2);

        first.close();

        assertThat(first.retainedAttemptCount()).isZero();
        assertThatThrownBy(() -> first.tryAcquire(
                firstRun, 3, SummaryTier.C3_ROLLING))
                .isInstanceOf(IllegalStateException.class);

        RunId nextRun = new RunId("run-lifecycle-next");
        SummaryAttemptGuard next = new SummaryAttemptGuard(nextRun);
        assertThat(next.retainedAttemptCount()).isZero();
        assertThat(next.tryAcquire(nextRun, 1, SummaryTier.C3_ROLLING)).isTrue();
        assertThatThrownBy(() -> next.tryAcquire(
                firstRun, 1, SummaryTier.C3_ROLLING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeRacingAcquireIsLinearizableAndAlwaysFailClosedAfterReturn()
            throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            RunId runId = new RunId("run-race-" + iteration);
            SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> acquire = executor.submit(() -> {
                    start.await();
                    try {
                        return guard.tryAcquire(runId, 1, SummaryTier.C3_ROLLING);
                    } catch (IllegalStateException closed) {
                        return false;
                    }
                });
                Future<?> close = executor.submit(() -> {
                    start.await();
                    guard.close();
                    return null;
                });
                start.countDown();
                acquire.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(guard.retainedAttemptCount()).isZero();
            assertThatThrownBy(() -> guard.tryAcquire(
                    runId, 2, SummaryTier.C4_FULL))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closeWinningBeforeCommitRejectsAdoptionDeterministically() {
        RunId runId = new RunId("run-close-wins");
        SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
        assertThat(guard.tryAcquire(runId, 1, SummaryTier.C3_ROLLING)).isTrue();
        AtomicInteger commits = new AtomicInteger();

        guard.close();
        Optional<String> adopted = guard.commitIfOpen(() -> {
            commits.incrementAndGet();
            return "adopted";
        });

        assertThat(adopted).isEmpty();
        assertThat(commits).hasValue(0);
        assertThat(guard.retainedAttemptCount()).isZero();
    }

    @Test
    void commitWinningBeforeClosePublishesAdoptionDeterministically() {
        RunId runId = new RunId("run-commit-wins");
        SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
        assertThat(guard.tryAcquire(runId, 1, SummaryTier.C3_ROLLING)).isTrue();
        AtomicInteger commits = new AtomicInteger();

        Optional<String> adopted = guard.commitIfOpen(() -> {
            commits.incrementAndGet();
            return "adopted";
        });
        guard.close();

        assertThat(adopted).contains("adopted");
        assertThat(commits).hasValue(1);
        assertThat(guard.retainedAttemptCount()).isZero();
        assertThat(guard.commitIfOpen(() -> "late")).isEmpty();
    }

    @Test
    void closeDuringSummaryPreventsAdoptionAndRetryAndClearsBothGuards()
            throws Exception {
        RunId runId = new RunId("run-close-summary");
        Fixture fixture = fixture(true);
        CountDownLatch summarizerEntered = new CountDownLatch(1);
        CountDownLatch releaseSummarizer = new CountDownLatch(1);
        SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
        ContextSummarizer summarizer = (request, cancellation) -> {
            summarizerEntered.countDown();
            await(releaseSummarizer);
            return Optional.of(candidate(request, "compact"));
        };
        SummaryReductionCoordinator summary = new SummaryReductionCoordinator(
                summarizer, ESTIMATOR, guard);
        ContextOverflowRetryCoordinator coordinator =
                new ContextOverflowRetryCoordinator(runId, summary);
        AtomicInteger modelCalls = new AtomicInteger();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ContextOverflowRetryCoordinator.Outcome<String>> execution =
                    executor.submit(() -> coordinator.execute(
                            runId,
                            fixture.request(),
                            fixture.projection(),
                            policy(),
                            CancellationToken.none(),
                            projection -> {
                                modelCalls.incrementAndGet();
                                return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                            }));
            assertThat(summarizerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.close();
            releaseSummarizer.countDown();

            ContextOverflowRetryCoordinator.Outcome<String> outcome = get(execution);
            assertThat(outcome.result().status())
                    .isEqualTo(ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED);
            assertThat(outcome.modelRequestAttempts()).isOne();
            assertThat(outcome.projection()).isEqualTo(fixture.projection());
            assertThat(modelCalls).hasValue(1);
        } finally {
            releaseSummarizer.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(guard.retainedAttemptCount()).isZero();
        assertThat(coordinator.retainedRetryCount()).isZero();
        assertThatThrownBy(() -> coordinator.execute(
                runId, fixture.request(), fixture.projection(), policy(),
                CancellationToken.none(), projection ->
                        ContextOverflowRetryCoordinator.AttemptResult.overflow()))
                .isInstanceOf(IllegalStateException.class);
    }

    private ContextOverflowRetryCoordinator.Outcome<String> get(
            Future<ContextOverflowRetryCoordinator.Outcome<String>> future)
            throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test boundary");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private Fixture fixture(boolean recoveryAvailable) {
        List<io.github.liumaishenjian.ccjava.domain.AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("old history " + "x".repeat(90)),
                new UserMessage("active"));
        ContextCapacity capacity = new ContextCapacity("offline", 32, 1, 1);
        ProjectionRequest request = new ProjectionRequest(
                canonical, capacity, 9, 1, recoveryAvailable);
        ContextUsage usage = ESTIMATOR.estimate(canonical, capacity);
        return new Fixture(
                request,
                new ContextProjection(canonical, usage, List.of(), 9));
    }

    private SummaryReductionPolicy policy() {
        return new SummaryReductionPolicy(
                2, true, List.of(), List.of(), 1_000, 200);
    }

    private SummaryCandidate candidate(SummaryRequest request, String text) {
        return new SummaryCandidate(
                request.tier(),
                text,
                request.sourceRevision(),
                request.sourceMessageIds(),
                text.getBytes(StandardCharsets.UTF_8).length,
                7);
    }

    private record Fixture(
            ProjectionRequest request,
            ContextProjection projection) {
    }
}
