package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 10k SessionIndex 冻结 SLA 的机器可读 benchmark artifact。 */
class SessionIndexBenchmarkTest {
    @TempDir Path temp;

    @Test
    void emitsMachineReadableTenThousandSessionBenchmark() throws Exception {
        Path indexRoot = temp.resolve("index");
        var entries = new ArrayList<SessionIndexEntry>(10_000);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10_000; i++) entries.add(new SessionIndexEntry(
                "session-bench-" + i, "workspace-bench", "needle-demo-" + i,
                base.plusSeconds(i), SessionLifecycleStatus.CLOSED));
        long before = usedMemory();
        long rebuildStart = System.nanoTime();
        new FileSessionIndex(indexRoot).rebuild(entries);
        long rebuildMs = elapsed(rebuildStart);
        FileSessionIndex reopened = new FileSessionIndex(indexRoot);
        long[] samples = new long[31];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            if ((i & 1) == 0) reopened.list(i * 10, 100);
            else reopened.search("needle-demo-99", 100);
            samples[i] = elapsed(start);
        }
        Arrays.sort(samples);
        long p95 = samples[(int) Math.ceil(samples.length * .95) - 1];
        long memory = Math.max(0, usedMemory() - before);
        boolean passed = rebuildMs <= 30_000 && p95 <= 250 && memory <= 256L * 1024 * 1024;
        Path artifact = Path.of("target", "s14-session-index-benchmark.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\n"
                + "  \"schema\": \"cc-java-s14-session-index-benchmark-v1\",\n"
                + "  \"records\": 10000,\n"
                + "  \"rebuildMillis\": " + rebuildMs + ",\n"
                + "  \"listSearchP95Millis\": " + p95 + ",\n"
                + "  \"additionalHeapBytes\": " + memory + ",\n"
                + "  \"slaPassed\": " + passed + "\n}\n", StandardCharsets.UTF_8);
        assertThat(reopened.find("session-bench-9999")).isPresent();
        assertThat(passed).isTrue();
    }

    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
