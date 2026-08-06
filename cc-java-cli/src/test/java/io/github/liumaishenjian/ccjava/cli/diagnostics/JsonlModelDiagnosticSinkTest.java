package io.github.liumaishenjian.ccjava.cli.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureStage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlModelDiagnosticSinkTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void offDoesNotCreateConfiguredDirectory() {
        Path absent = temporary.resolve("off");

        try (ModelDiagnostics diagnostics = ModelDiagnostics.open(
                ModelDiagnosticMode.OFF, Optional.of(absent))) {
            diagnostics.recorder().isOff();
        }

        assertThat(absent).doesNotExist();
    }

    @Test
    void writesOnlyClosedJsonFieldsAndNeverLeaksSentinels() throws Exception {
        Path directory = temporary.resolve("safe");
        try (JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 256)) {
            sink.record(event(1));
        }

        String json = Files.readString(singleFile(directory), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"kind\":\"FAILURE\"")
                .contains("\"recordedAt\":\"2026-08-07T00:00:00Z\"")
                .doesNotContain("PROMPT_SENTINEL", "ENDPOINT_SENTINEL", "TOKEN_SENTINEL",
                        "TOOL_SENTINEL", temporary.toAbsolutePath().toString());
        assertThat(json.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(JsonlModelDiagnosticSink.MAX_RECORD_BYTES);
        assertThat(json.lines()).allMatch(line -> line.getBytes(StandardCharsets.UTF_8).length
                <= JsonlModelDiagnosticSink.MAX_RECORD_BYTES);
    }

    @Test
    void rotatesAtOneMiBAndKeepsAtMostFiveFiles() throws Exception {
        Path directory = temporary.resolve("rotation");
        try (JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(
                directory, CLOCK, 256, 4 * 1024L)) {
            for (int index = 0; index < 200; index++) {
                sink.record(event(index + 1));
            }
        }

        List<Path> files = diagnosticFiles(directory);
        assertThat(files).hasSizeBetween(2, JsonlModelDiagnosticSink.MAX_FILES);
        assertThat(files).allSatisfy(file -> assertThat(size(file))
                .isLessThanOrEqualTo(4 * 1024L));
    }

    @Test
    void prunesExpiredAndExcessFilesAtStartup() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("retention"));
        for (int index = 0; index < 7; index++) {
            Path file = Files.writeString(directory.resolve(
                    "model-diagnostics-old-" + index + ".jsonl"), "{}\n");
            Files.setLastModifiedTime(file, FileTime.from(
                    index == 0 ? NOW.minusSeconds(8 * 86_400L) : NOW.minusSeconds(index)));
        }

        try (JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 256)) {
            sink.record(event(1));
        }

        assertThat(diagnosticFiles(directory)).hasSizeLessThanOrEqualTo(5);
        assertThat(directory.resolve("model-diagnostics-old-0.jsonl")).doesNotExist();
    }

    @Test
    void queueOverflowAndIoFailureRemainNonBlockingAndIsolated() throws Exception {
        Path directory = temporary.resolve("overflow");
        JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 1);
        for (int index = 0; index < 100_000; index++) {
            sink.record(event(index + 1));
        }
        sink.close();
        assertThat(sink.droppedCount()).isPositive();

        Path regularFile = Files.writeString(temporary.resolve("not-a-directory"), "occupied");
        assertThatThrownBy(() -> new JsonlModelDiagnosticSink(regularFile, CLOCK, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(regularFile.toString());
        assertThatCode(() -> {
            try (ModelDiagnostics diagnostics = ModelDiagnostics.open(
                    ModelDiagnosticMode.SAFE, Optional.of(regularFile))) {
                assertThat(diagnostics.recorder().isOff()).isTrue();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void compositionDegradesConstructionAndPathFailuresToOff() throws Exception {
        Path occupied = Files.writeString(temporary.resolve("occupied"), "not a directory");

        try (ModelDiagnostics diagnostics = ModelDiagnostics.open(
                ModelDiagnosticMode.SAFE, Optional.of(occupied))) {
            assertThat(diagnostics.recorder().isOff()).isTrue();
        }

        Path target = Files.createDirectories(temporary.resolve("target"));
        Path link = temporary.resolve("linked");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return;
        }
        try (ModelDiagnostics diagnostics = ModelDiagnostics.open(
                ModelDiagnosticMode.VERBOSE, Optional.of(link.resolve("diagnostics")))) {
            assertThat(diagnostics.recorder().isOff()).isTrue();
        }
    }

    @Test
    void deterministicPathSeamRejectsOtherAndNoFollowMismatch() throws Exception {
        Path directory = temporary.resolve("deterministic").toAbsolutePath().normalize();
        BasicFileAttributes ordinary = Files.readAttributes(
                temporary, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes other = new DelegatingAttributes(ordinary) {
            @Override
            public boolean isOther() {
                return true;
            }
        };
        JsonlModelDiagnosticSink.PathAccess reparse = accessReturning(directory, other, directory);
        assertThatThrownBy(() -> new JsonlModelDiagnosticSink(directory, CLOCK, 1,
                JsonlModelDiagnosticSink.MAX_FILE_BYTES, reparse))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(directory.toString());

        JsonlModelDiagnosticSink.PathAccess mismatch = accessReturning(
                directory, ordinary, directory.resolveSibling("elsewhere"));
        assertThatThrownBy(() -> new JsonlModelDiagnosticSink(directory, CLOCK, 1,
                JsonlModelDiagnosticSink.MAX_FILE_BYTES, mismatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(directory.toString());
    }

    @Test
    void windowsJunctionIsRejectedWhenHostPermitsCreation() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path target = Files.createDirectories(temporary.resolve("junction-target"));
        Path junction = temporary.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString()).redirectErrorStream(true).start();
        assumeTrue(process.waitFor() == 0);

        try (ModelDiagnostics diagnostics = ModelDiagnostics.open(
                ModelDiagnosticMode.SAFE, Optional.of(junction.resolve("diagnostics")))) {
            assertThat(diagnostics.recorder().isOff()).isTrue();
        }
    }

    @Test
    void closeDrainsAcceptedEventsAndIsConcurrentIdempotent() throws Exception {
        Path directory = temporary.resolve("drain");
        JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 256);
        for (int index = 0; index < 200; index++) {
            sink.record(event(index + 1));
        }
        Thread first = Thread.ofPlatform().start(sink::close);
        Thread second = Thread.ofPlatform().start(sink::close);
        first.join();
        second.join();
        sink.record(event(999));
        sink.close();

        long lines = diagnosticFiles(directory).stream().mapToLong(file -> {
            try {
                return Files.readAllLines(file).size();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }).sum();
        assertThat(lines).isEqualTo(200);
        assertThat(sink.droppedCount()).isZero();
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(
                thread -> thread.isAlive() && thread.getName().equals("cc-java-model-diagnostics"));
    }

    @Test
    void interruptedCloseStillReapsWorkerAndCountsFailure() {
        Path directory = temporary.resolve("interrupted-close");
        JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 256);
        sink.record(event(1));
        Thread.currentThread().interrupt();
        try {
            sink.close();
            assertThat(sink.failureCount()).isPositive();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void appliesOwnerOnlyPosixPermissionsWhenSupported() throws Exception {
        Path directory = temporary.resolve("permissions");
        try (JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(directory, CLOCK, 256)) {
            sink.record(event(1));
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            assertThat(Files.getPosixFilePermissions(singleFile(directory))).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }

    private static JsonlModelDiagnosticSink.PathAccess accessReturning(
            Path existing, BasicFileAttributes attributes, Path realPath) {
        return new JsonlModelDiagnosticSink.PathAccess() {
            @Override
            public boolean exists(Path path) {
                return path.equals(existing);
            }

            @Override
            public boolean isSymbolicLink(Path path) {
                return false;
            }

            @Override
            public BasicFileAttributes attributes(Path path) {
                return attributes;
            }

            @Override
            public Path noFollowRealPath(Path path) {
                return realPath;
            }
        };
    }

    private static class DelegatingAttributes implements BasicFileAttributes {
        private final BasicFileAttributes delegate;

        private DelegatingAttributes(BasicFileAttributes delegate) {
            this.delegate = delegate;
        }

        @Override public FileTime lastModifiedTime() { return delegate.lastModifiedTime(); }
        @Override public FileTime lastAccessTime() { return delegate.lastAccessTime(); }
        @Override public FileTime creationTime() { return delegate.creationTime(); }
        @Override public boolean isRegularFile() { return delegate.isRegularFile(); }
        @Override public boolean isDirectory() { return delegate.isDirectory(); }
        @Override public boolean isSymbolicLink() { return delegate.isSymbolicLink(); }
        @Override public boolean isOther() { return delegate.isOther(); }
        @Override public long size() { return delegate.size(); }
        @Override public Object fileKey() { return delegate.fileKey(); }
    }

    private static ModelDiagnosticEvent event(int ordinal) {
        return new ModelDiagnosticEvent(
                ModelDiagnosticEvent.CURRENT_SCHEMA_VERSION,
                ModelDiagnosticKind.FAILURE,
                new UUID(0L, 1L),
                new UUID(0L, 2L),
                Math.max(1, ordinal % 1_000_000),
                1,
                ModelFailureStage.STREAM_TRANSPORT,
                ModelFailureReason.NETWORK_IO,
                ModelDiagnosticStatusClass.SERVER_ERROR,
                true,
                false,
                ordinal,
                NOW);
    }

    private static Path singleFile(Path directory) throws Exception {
        return diagnosticFiles(directory).getFirst();
    }

    private static List<Path> diagnosticFiles(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("model-diagnostics-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
