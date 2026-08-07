package io.github.liumaishenjian.ccjava.tools.local.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkspaceReadRegistryTest {

    private static final SessionId FIRST = new SessionId("session-1");
    private static final SessionId SECOND = new SessionId("session-2");

    @Test
    void isolatesEvidenceBetweenSessions() {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();

        registry.record(FIRST, evidence("a.txt", 1, 10, true, 7));

        assertThat(registry.find(FIRST, "a.txt")).isPresent();
        assertThat(registry.find(SECOND, "a.txt")).isEmpty();
    }

    @Test
    void replacesEvidenceForSamePathAndInvalidatesAcrossSessions() {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        registry.record(FIRST, evidence("a.txt", 1, 10, true, 7));
        registry.record(SECOND, evidence("a.txt", 1, 10, true, 7));

        registry.record(FIRST, evidence("a.txt", 5, 20, false, 9));
        assertThat(registry.find(FIRST, "a.txt").orElseThrow().firstLine()).isEqualTo(5);
        assertThat(registry.size()).isEqualTo(2);

        registry.invalidate("a.txt");

        assertThat(registry.find(FIRST, "a.txt")).isEmpty();
        assertThat(registry.find(SECOND, "a.txt")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void boundsEntriesPerSessionByLeastRecentlyUsed() {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        int overflow = WorkspaceReadRegistry.MAX_ENTRIES_PER_SESSION + 40;

        for (int index = 0; index < overflow; index++) {
            registry.record(FIRST, evidence("file-" + index + ".txt", 1, 3, true, index));
        }

        assertThat(registry.find(FIRST, "file-0.txt")).isEmpty();
        assertThat(registry.find(FIRST, "file-" + (overflow - 1) + ".txt")).isPresent();
        assertThat(registry.size())
                .isEqualTo(WorkspaceReadRegistry.MAX_ENTRIES_PER_SESSION);
    }

    @Test
    void lookupRefreshesLeastRecentlyUsedOrder() {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        for (int index = 0; index < WorkspaceReadRegistry.MAX_ENTRIES_PER_SESSION; index++) {
            registry.record(FIRST, evidence("file-" + index + ".txt", 1, 3, true, index));
        }

        assertThat(registry.find(FIRST, "file-0.txt")).isPresent();
        registry.record(FIRST, evidence("overflow.txt", 1, 3, true, 999));

        assertThat(registry.find(FIRST, "file-0.txt")).isPresent();
        assertThat(registry.find(FIRST, "file-1.txt")).isEmpty();
    }

    @Test
    void boundsSessionCountByLeastRecentlyUsed() {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        int overflow = WorkspaceReadRegistry.MAX_SESSIONS + 5;

        for (int index = 0; index < overflow; index++) {
            registry.record(new SessionId("s-" + index), evidence("x.txt", 1, 3, true, index));
        }

        assertThat(registry.find(new SessionId("s-0"), "x.txt")).isEmpty();
        assertThat(registry.find(new SessionId("s-" + (overflow - 1)), "x.txt")).isPresent();
        assertThat(registry.size()).isEqualTo(WorkspaceReadRegistry.MAX_SESSIONS);
    }

    @Test
    void staysConsistentUnderConcurrentAccess() throws Exception {
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            int id = index;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        registry.record(
                                new SessionId("s-" + (id % 3)),
                                evidence("p-" + (round % 5) + ".txt", 1, 4, true, round));
                        registry.find(new SessionId("s-" + (id % 3)), "p-1.txt");
                        if (round % 25 == 0) {
                            registry.invalidate("p-2.txt");
                        }
                    }
                } catch (Throwable throwable) {
                    synchronized (failures) {
                        failures.add(throwable);
                    }
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(registry.size())
                .isLessThanOrEqualTo(3 * WorkspaceReadRegistry.MAX_ENTRIES_PER_SESSION);
    }

    @Test
    void coversOnlyRecordedRangeUnlessCompleteFile() {
        ReadEvidence partial = evidence("a.txt", 10, 20, false, 3);
        ReadEvidence whole = evidence("a.txt", 1, 4, true, 3);

        assertThat(partial.covers(10, 20)).isTrue();
        assertThat(partial.covers(9, 20)).isFalse();
        assertThat(partial.covers(10, 21)).isFalse();
        assertThat(whole.covers(1_000, 2_000)).isTrue();
    }

    @Test
    void producesStableDigestForIdenticalCanonicalText() {
        assertThat(ReadEvidence.digestOf("a\nb")).isEqualTo(ReadEvidence.digestOf("a\nb"));
        assertThat(ReadEvidence.digestOf("a\nb")).isNotEqualTo(ReadEvidence.digestOf("a\nc"));
    }

    private static ReadEvidence evidence(
            String path,
            int firstLine,
            int lastLine,
            boolean completeFile,
            long digest) {
        return new ReadEvidence(path, firstLine, lastLine, completeFile, 128, 1_000, digest);
    }
}
