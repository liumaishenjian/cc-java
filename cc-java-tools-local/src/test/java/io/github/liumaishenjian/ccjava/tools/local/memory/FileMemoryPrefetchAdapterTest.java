package io.github.liumaishenjian.ccjava.tools.local.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.MemoryPrefetch;
import io.github.liumaishenjian.ccjava.core.RelevantMemoryRecall;
import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryPrefetchAdapterTest {

    @TempDir
    Path temporary;

    @Test
    void missingRootDoesNotCreateDirectoryAndReturnsUsableEmptyProjection() {
        Path missing = temporary.resolve("missing");
        QueuedExecutorService executor = new QueuedExecutorService();

        try (FileMemoryPrefetchAdapter adapter = adapter(missing, executor)) {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("java build"), CancellationToken.none());

            assertThat(executor.queuedTaskCount()).isOne();
            assertThat(Files.exists(missing)).isFalse();
            executor.runNext();
            assertThat(prefetch.consumeReady().items()).isEmpty();
            assertThat(Files.exists(missing)).isFalse();
        }
    }

    @Test
    void invalidRootDegradesWithoutExposingPathText() throws Exception {
        Path invalid = Files.writeString(temporary.resolve("private-invalid-root"), "not a directory");
        QueuedExecutorService executor = new QueuedExecutorService();

        try (FileMemoryPrefetchAdapter adapter = adapter(invalid, executor)) {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("java build"), CancellationToken.none());

            executor.runNext();
            assertThat(prefetch.consumeReady().items()).isEmpty();
            assertThat(adapter.toString()).doesNotContain(invalid.toString(), "private-invalid-root");
        }
    }

    @Test
    void readyRelevantTopicIsLoadedThroughCatalogRecallAndProjector() {
        Path root = createMemoryRoot("ready");
        writeTopic(root, "java-build", "Java Maven build guidance", "Use mvnw test.");
        writeTopic(root, "unrelated", "Gardening notes", "Water plants.");
        QueuedExecutorService executor = new QueuedExecutorService();

        try (FileMemoryPrefetchAdapter adapter = adapter(root, executor)) {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("fix JAVA build"), CancellationToken.none());

            executor.runNext();
            assertThat(prefetch.consumeReady().items())
                    .extracting(item -> item.name())
                    .containsExactly("java-build");
        }
    }

    @Test
    void corruptAndSecretRejectedTopicsDoNotEnterProjection() {
        Path root = createMemoryRoot("unsafe");
        writeTopic(root, "safe-topic", "safe java guidance", "Run safe checks.");
        try {
            Files.writeString(root.resolve("secret-topic.md"), topicText(
                    "secret-topic", "java credentials", "api_key=fixture-secret"));
            Files.write(root.resolve("corrupt-topic.md"), new byte[] {(byte) 0xC3, (byte) 0x28});
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        QueuedExecutorService executor = new QueuedExecutorService();

        try (FileMemoryPrefetchAdapter adapter = adapter(root, executor)) {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("java safe credentials corrupt"), CancellationToken.none());

            executor.runNext();
            assertThat(prefetch.consumeReady().items())
                    .extracting(item -> item.name())
                    .containsExactly("safe-topic");
        }
    }

    @Test
    void startOnlyEnqueuesAndDoesNotRunFileWorkInline() {
        Path root = createMemoryRoot("queued");
        writeTopic(root, "java-build", "java build", "body");
        QueuedExecutorService executor = new QueuedExecutorService();
        AtomicBoolean fileWorkObserved = new AtomicBoolean();
        FileMemoryPrefetchAdapter adapter = new FileMemoryPrefetchAdapter(
                root,
                executor,
                new DeterministicMemoryKeywordPolicy(),
                new RelevantMemoryRecall(),
                observer(() -> fileWorkObserved.set(true), () -> { }));
        try {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("java build"), CancellationToken.none());

            assertThat(executor.queuedTaskCount()).isOne();
            assertThat(fileWorkObserved).isFalse();
            assertThat(prefetch.consumeReady().items()).isEmpty();
            assertThat(fileWorkObserved).isFalse();
        } finally {
            adapter.close();
        }
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.shutdownNowCalls()).isOne();
    }

    @Test
    void freshCatalogRevisionRejectsPlanWhenTopicChangesAfterSelection() {
        Path root = createMemoryRoot("stale");
        writeTopic(root, "java-build", "java build", "old body");
        QueuedExecutorService executor = new QueuedExecutorService();
        AtomicBoolean changed = new AtomicBoolean();
        FileMemoryPrefetchAdapter adapter = new FileMemoryPrefetchAdapter(
                root,
                executor,
                new DeterministicMemoryKeywordPolicy(),
                new RelevantMemoryRecall(),
                observer(
                        () -> { },
                        () -> {
                            try {
                                Files.writeString(
                                        root.resolve("java-build.md"),
                                        topicText("java-build", "java build", "changed body"));
                                changed.set(true);
                            } catch (java.io.IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        }));
        try {
            MemoryPrefetch prefetch = adapter.start(
                    new UserMessage("java build"), CancellationToken.none());

            assertThat(changed).isFalse();
            executor.runNext();
            assertThat(changed).isTrue();
            assertThat(prefetch.consumeReady())
                    .satisfies(projection -> {
                        assertThat(projection.items()).isEmpty();
                        assertThat(projection.diagnostics())
                                .extracting(diagnostic -> diagnostic.kind())
                                .containsExactly(MemoryProjectionDiagnosticKind.STALE_CATALOG);
                    });
        } finally {
            adapter.close();
        }
    }

    @Test
    void cancelledClosedAndRejectedStartsDegradeWithoutThrowing() {
        Path root = createMemoryRoot("closed");
        QueuedExecutorService rejected = new QueuedExecutorService();
        rejected.shutdownNow();
        FileMemoryPrefetchAdapter rejecting = adapter(root, rejected);
        MemoryPrefetch rejectedPrefetch = rejecting.start(
                new UserMessage("java build"), CancellationToken.none());
        assertThat(rejectedPrefetch.consumeReady().items()).isEmpty();

        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel();
        QueuedExecutorService executor = new QueuedExecutorService();
        try (FileMemoryPrefetchAdapter adapter = adapter(root, executor)) {
            assertThat(adapter.start(new UserMessage("java"), cancellation.token())
                    .consumeReady().items()).isEmpty();
            assertThat(executor.queuedTaskCount()).isZero();
            adapter.close();
            assertThat(adapter.start(new UserMessage("java"), CancellationToken.none())
                    .consumeReady().items()).isEmpty();
        }
        assertThat(executor.shutdownNowCalls()).isOne();
    }

    private FileMemoryPrefetchAdapter adapter(Path root, QueuedExecutorService executor) {
        return new FileMemoryPrefetchAdapter(
                root,
                executor,
                new DeterministicMemoryKeywordPolicy(),
                new RelevantMemoryRecall(),
                FileMemoryPrefetchAdapter.RecallObserver.noop());
    }

    private FileMemoryPrefetchAdapter.RecallObserver observer(
            Runnable beforeFileWork,
            Runnable afterSelection) {
        return new FileMemoryPrefetchAdapter.RecallObserver() {
            @Override
            public void beforeFileWork() {
                beforeFileWork.run();
            }

            @Override
            public void afterSelection() {
                afterSelection.run();
            }
        };
    }

    private Path createMemoryRoot(String name) {
        try {
            return Files.createDirectory(temporary.resolve(name));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void writeTopic(Path root, String name, String description, String body) {
        FileMemoryRepository repository = new FileMemoryRepository(root);
        repository.saveTopic(
                MemoryTopic.candidate(
                        name,
                        MemoryKind.PROJECT_STATE,
                        description,
                        body,
                        LocalDate.of(2026, 8, 5)),
                Optional.empty());
    }

    private String topicText(String name, String description, String body) {
        return "---\n"
                + "kind: PROJECT_STATE\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "updated-at: 2026-08-05\n"
                + "---\n\n"
                + body;
    }

    private static final class QueuedExecutorService
            extends java.util.concurrent.AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private int shutdownNowCalls;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNowCalls++;
            java.util.List<Runnable> pending = java.util.List.copyOf(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }

        int queuedTaskCount() {
            return tasks.size();
        }

        int shutdownNowCalls() {
            return shutdownNowCalls;
        }
    }
}
