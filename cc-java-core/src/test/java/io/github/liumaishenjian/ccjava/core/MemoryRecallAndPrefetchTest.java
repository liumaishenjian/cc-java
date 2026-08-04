package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryProjection;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryRecallPlan;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import io.github.liumaishenjian.ccjava.domain.MemoryTopicHeader;
import io.github.liumaishenjian.ccjava.domain.RecallQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MemoryRecallAndPrefetchTest {

    private static final MemoryCatalogRevision REVISION =
            new MemoryCatalogRevision("0".repeat(64));

    @Test
    void selectsDeterministicallyAndHonorsLimit() {
        MemoryCatalog catalog = catalog(List.of(
                header("alpha-java", "Java build guidance", "1"),
                header("beta-java", "Java testing", "2"),
                header("other-topic", "unrelated", "3")));
        RecallQuery query = new RecallQuery(
                "fix java tests", List.of("java", "tests"), 1, 100, REVISION);

        MemoryRecallPlan plan = new RelevantMemoryRecall().select(catalog, query);

        assertThat(plan.selectedHeaders()).extracting(MemoryTopicHeader::name)
                .containsExactly("alpha-java");
    }

    @Test
    void consumeReadyNeverCallsBlockingFutureMethodsAndLateResultIsIgnored() {
        BlockingForbiddenFuture future = new BlockingForbiddenFuture();
        MemoryPrefetch prefetch = new MemoryPrefetch(future, 100, REVISION);

        MemoryProjection first = prefetch.consumeReady();
        future.complete(emptyProjection());
        MemoryProjection second = prefetch.consumeReady();

        assertThat(first.diagnostics()).extracting(d -> d.kind())
                .containsExactly(MemoryProjectionDiagnosticKind.NOT_READY);
        assertThat(second.diagnostics()).extracting(d -> d.kind())
                .containsExactly(MemoryProjectionDiagnosticKind.ALREADY_CONSUMED);
        assertThat(future.blockingCalled).isFalse();
    }

    @Test
    void concurrentReadyConsumersHaveOneWinnerWithoutBlockingFutureCalls()
            throws Exception {
        BlockingForbiddenFuture future = new BlockingForbiddenFuture();
        future.complete(emptyProjection());
        MemoryPrefetch prefetch = new MemoryPrefetch(future, 100, REVISION);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<MemoryProjection>> results =
                    java.util.stream.IntStream.range(0, 16)
                            .mapToObj(index -> executor.submit(() -> {
                                start.await();
                                return prefetch.consumeReady();
                            }))
                            .toList();
            start.countDown();
            List<MemoryProjection> projections = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<MemoryProjection> result : results) {
                projections.add(result.get());
            }

            assertThat(projections.stream()
                    .filter(projection -> projection.diagnostics().isEmpty()))
                    .hasSize(1);
            assertThat(projections.stream()
                    .flatMap(projection -> projection.diagnostics().stream())
                    .filter(diagnostic -> diagnostic.kind()
                            == MemoryProjectionDiagnosticKind.ALREADY_CONSUMED))
                    .hasSize(15);
            assertThat(future.blockingCalled).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidPrefetchConstruction() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new MemoryPrefetch(new CompletableFuture<>(), 0, REVISION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new MemoryPrefetch(new CompletableFuture<>(), 100, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void startValidatesEverythingBeforeExecutorSubmission() {
        RecordingExecutor executor = new RecordingExecutor();
        java.util.concurrent.atomic.AtomicInteger executions =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<MemoryProjection> work = () -> {
            executions.incrementAndGet();
            return emptyProjection();
        };

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                MemoryPrefetch.start(executor, work, 0, REVISION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                MemoryPrefetch.start(executor, work, 100, null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(executor.submissions).isZero();
        assertThat(executions).hasValue(0);

        MemoryPrefetch prefetch = MemoryPrefetch.start(
                executor, work, 100, REVISION);
        assertThat(executor.submissions).isOne();
        assertThat(executions).hasValue(1);
        assertThat(prefetch.consumeReady().diagnostics()).isEmpty();
    }

    @Test
    void cancelIsNonBlockingAndPropagatesToFuture() {
        BlockingForbiddenFuture future = new BlockingForbiddenFuture();
        MemoryPrefetch prefetch = new MemoryPrefetch(future, 100, REVISION);

        assertThat(prefetch.cancel()).isTrue();
        assertThat(future.isCancelled()).isTrue();
        assertThat(future.blockingCalled).isFalse();
    }

    @Test
    void consumesReadySuccessExceptionalAndCancelled() {
        CompletableFuture<MemoryProjection> ready =
                CompletableFuture.completedFuture(emptyProjection());
        assertThat(new MemoryPrefetch(ready, 100, REVISION).consumeReady())
                .isSameAs(ready.getNow(null));

        CompletableFuture<MemoryProjection> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        assertThat(new MemoryPrefetch(failed, 100, REVISION).consumeReady().diagnostics())
                .extracting(d -> d.kind())
                .containsExactly(MemoryProjectionDiagnosticKind.RECALL_FAILED);

        CompletableFuture<MemoryProjection> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertThat(new MemoryPrefetch(cancelled, 100, REVISION).consumeReady().diagnostics())
                .extracting(d -> d.kind())
                .containsExactly(MemoryProjectionDiagnosticKind.CANCELLED);
    }

    @Test
    void projectorIsolatesStaleDigestDuplicateMissingAndBudget() {
        MemoryTopic alpha = topic("alpha", "one", "1");
        MemoryTopic beta = topic("beta", "toolong", "2");
        MemoryBodyLoader loader = name -> Optional.ofNullable(
                Map.of("alpha", alpha, "beta", beta).get(name));
        MemoryProjector projector = new MemoryProjector(loader);
        MemoryRecallPlan plan = new MemoryRecallPlan(
                List.of(alpha.header(), alpha.header(),
                        new MemoryTopicHeader("missing", MemoryKind.PROJECT_STATE,
                                "hook", LocalDate.of(2026, 8, 4), "3".repeat(64)),
                        new MemoryTopicHeader("beta", MemoryKind.PROJECT_STATE,
                                "hook", LocalDate.of(2026, 8, 4), "9".repeat(64)),
                        beta.header()), REVISION, 4);

        MemoryProjection projection = projector.project(plan, catalog(List.of()));

        assertThat(projection.items()).extracting(item -> item.name())
                .containsExactly("alpha");
        assertThat(projection.diagnostics()).extracting(d -> d.kind())
                .containsExactly(
                        MemoryProjectionDiagnosticKind.DUPLICATE_TOPIC,
                        MemoryProjectionDiagnosticKind.TOPIC_UNAVAILABLE,
                        MemoryProjectionDiagnosticKind.DIGEST_MISMATCH,
                        MemoryProjectionDiagnosticKind.DUPLICATE_TOPIC);
    }

    @Test
    void projectorRejectsStaleRevision() {
        MemoryRecallPlan plan = new MemoryRecallPlan(List.of(), REVISION, 10);
        MemoryCatalog current = new MemoryCatalog(
                List.of(), List.of(), new MemoryCatalogRevision("f".repeat(64)));

        MemoryProjection projection = new MemoryProjector(name -> Optional.empty())
                .project(plan, current);

        assertThat(projection.diagnostics()).extracting(d -> d.kind())
                .containsExactly(MemoryProjectionDiagnosticKind.STALE_CATALOG);
    }

    private static MemoryProjection emptyProjection() {
        return new MemoryProjection(List.of(), 0, 100, REVISION, List.of());
    }

    private static MemoryCatalog catalog(List<MemoryTopicHeader> entries) {
        return new MemoryCatalog(entries, List.of(), REVISION);
    }

    private static MemoryTopicHeader header(String name, String description, String digest) {
        return new MemoryTopicHeader(name, MemoryKind.PROJECT_STATE, description,
                LocalDate.of(2026, 8, 4), digest.repeat(64));
    }

    private static MemoryTopic topic(String name, String body, String digest) {
        return new MemoryTopic(name, MemoryKind.PROJECT_STATE, "hook", body,
                digest.repeat(64), LocalDate.of(2026, 8, 4));
    }

    private static final class RecordingExecutor implements java.util.concurrent.Executor {
        private int submissions;

        @Override
        public void execute(Runnable command) {
            submissions++;
            command.run();
        }
    }

    private static final class BlockingForbiddenFuture
            extends CompletableFuture<MemoryProjection> {
        private boolean blockingCalled;

        @Override
        public MemoryProjection get() {
            blockingCalled = true;
            throw new AssertionError("不得调用 get");
        }

        @Override
        public MemoryProjection get(long timeout, TimeUnit unit) {
            blockingCalled = true;
            throw new AssertionError("不得调用 timed get");
        }

        @Override
        public MemoryProjection join() {
            blockingCalled = true;
            throw new AssertionError("不得调用 join");
        }
    }
}
