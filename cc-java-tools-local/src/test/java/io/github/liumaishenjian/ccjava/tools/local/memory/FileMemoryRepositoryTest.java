package io.github.liumaishenjian.ccjava.tools.local.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.MemoryKind;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationResult;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationStatus;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryRepositoryTest {

    @TempDir
    Path temporary;

    @Test
    void roundTripsCreateUpdateDeleteAndRebuildsIndex() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("memory"));
        FileMemoryRepository repository = repository(root);

        for (MemoryKind kind : MemoryKind.values()) {
            String name = "topic-" + kind.name().toLowerCase().replace('_', '-');
            MemoryTopic candidate = MemoryTopic.candidate(
                    name,
                    kind,
                    "hook " + kind.name(),
                    "body " + kind.name(),
                    LocalDate.of(2026, 8, 4));

            MemoryMutationResult created = repository.saveTopic(candidate, Optional.empty());

            assertThat(created.status()).isEqualTo(MemoryMutationStatus.CREATED);
            assertThat(created.diagnostics()).isEmpty();
            assertThat(repository.loadTopic(name)).contains(created.topic().orElseThrow());
        }
        assertThat(Files.readString(root.resolve("MEMORY.md")))
                .contains("topic-user-profile.md", "topic-reference-pointer.md");

        MemoryTopic current = repository.loadTopic("topic-project-state").orElseThrow();
        MemoryTopic updated = current.updated(
                MemoryKind.WORKING_GUIDANCE,
                "updated hook",
                "updated body",
                LocalDate.of(2026, 8, 5));
        MemoryMutationResult update = repository.saveTopic(
                updated, Optional.of(current.contentDigest()));

        assertThat(update.status()).isEqualTo(MemoryMutationStatus.UPDATED);
        assertThat(update.topic().orElseThrow().body()).isEqualTo("updated body");
        MemoryMutationResult deleted = repository.deleteTopic(
                "topic-project-state", update.topic().orElseThrow().contentDigest());
        assertThat(deleted.status()).isEqualTo(MemoryMutationStatus.DELETED);
        assertThat(repository.loadTopic("topic-project-state")).isEmpty();
        assertThat(Files.readString(root.resolve("MEMORY.md")))
                .doesNotContain("topic-project-state.md");
    }

    @Test
    void rejectsDuplicateCreateAndStaleUpdateOrDelete() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("memory-conflict"));
        FileMemoryRepository repository = repository(root);
        MemoryTopic candidate = topic("conflict-topic", "one");
        MemoryTopic created = repository.saveTopic(candidate, Optional.empty())
                .topic().orElseThrow();

        assertRejected(
                repository.saveTopic(candidate, Optional.empty()),
                MemoryMutationDiagnosticKind.TOPIC_ALREADY_EXISTS);
        MemoryTopic firstUpdate = created.updated(
                MemoryKind.PROJECT_STATE,
                "updated",
                "two",
                LocalDate.of(2026, 8, 5));
        MemoryTopic persisted = repository.saveTopic(
                firstUpdate, Optional.of(created.contentDigest()))
                .topic().orElseThrow();

        assertRejected(
                repository.saveTopic(firstUpdate, Optional.of(created.contentDigest())),
                MemoryMutationDiagnosticKind.DIGEST_CONFLICT);
        assertRejected(
                repository.deleteTopic("conflict-topic", created.contentDigest()),
                MemoryMutationDiagnosticKind.DIGEST_CONFLICT);
        assertThat(repository.loadTopic("conflict-topic")).contains(persisted);
    }

    @Test
    void rejectsSecretCandidatesWithoutEchoingCandidate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("memory-secrets"));
        FileMemoryRepository repository = repository(root);
        String[] candidates = {
            "api_key=fake-secret-value",
            "token: fake-token-value",
            "password=fake-password-value",
            "Authorization: Bearer fake-bearer-value",
            "-----BEGIN PRIVATE KEY-----",
            "provider_endpoint=https://provider.invalid/v1"
        };

        for (int index = 0; index < candidates.length; index++) {
            String candidate = candidates[index];
            MemoryMutationResult result = repository.saveTopic(
                    topic("secret-topic-" + index, candidate), Optional.empty());

            assertRejected(result, MemoryMutationDiagnosticKind.SECRET_CANDIDATE_REJECTED);
            assertThat(result.toString()).doesNotContain(candidate, "fake-secret-value");
            assertThat(Files.exists(root.resolve("secret-topic-" + index + ".md"))).isFalse();
        }
    }

    @Test
    void rejectsUpdateAndDeleteWhenTargetIsReplacedBeforeCommit() throws Exception {
        Path updateRoot = Files.createDirectory(temporary.resolve("update-race"));
        FileMemoryRepository initial = repository(updateRoot);
        MemoryTopic beforeUpdate = initial.saveTopic(topic("race-topic", "before"), Optional.empty())
                .topic().orElseThrow();
        FileMemoryRepository updateRepository = repository(
                updateRoot,
                (phase, target) -> {
                    if (phase == FileMemoryRepository.MutationPhase.UPDATE) {
                        replaceSameLength(target, "before", "raced!");
                    }
                },
                FileMemoryRepositoryTest::atomicMove);

        MemoryMutationResult update = updateRepository.saveTopic(
                beforeUpdate.updated(
                        MemoryKind.PROJECT_STATE,
                        "changed",
                        "before",
                        LocalDate.of(2026, 8, 5)),
                Optional.of(beforeUpdate.contentDigest()));

        assertRejected(update, MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT);
        assertThat(Files.readString(updateRoot.resolve("race-topic.md"))).contains("raced!");
        assertNoInternalTemps(updateRoot);

        Path deleteRoot = Files.createDirectory(temporary.resolve("delete-race"));
        MemoryTopic beforeDelete = repository(deleteRoot)
                .saveTopic(topic("race-topic", "before"), Optional.empty())
                .topic().orElseThrow();
        FileMemoryRepository deleteRepository = repository(
                deleteRoot,
                (phase, target) -> {
                    if (phase == FileMemoryRepository.MutationPhase.DELETE) {
                        replaceSameLength(target, "before", "raced!");
                    }
                },
                FileMemoryRepositoryTest::atomicMove);

        MemoryMutationResult delete = deleteRepository.deleteTopic(
                "race-topic", beforeDelete.contentDigest());

        assertRejected(delete, MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT);
        assertThat(Files.exists(deleteRoot.resolve("race-topic.md"))).isTrue();
    }

    @Test
    void createDoesNotInvokeMoverAfterAtomicPublication() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("create-publication"));
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                (source, target, replace) -> {
                    if (!target.getFileName().toString().equals("MEMORY.md")) {
                        throw new AssertionError("create 不得在硬链接发布后调用 mover");
                    }
                    atomicMove(source, target, replace);
                });

        MemoryMutationResult result = repository.saveTopic(
                topic("published-topic", "body"), Optional.empty());

        assertThat(result.status()).isEqualTo(MemoryMutationStatus.CREATED);
        assertThat(repository.loadTopic("published-topic")).contains(result.topic().orElseThrow());
        assertNoInternalTemps(root);
    }

    @Test
    void cleanupFailureAfterPublicationStillReturnsCreatedAndRebuildsIndex() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("create-cleanup-failure"));
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                FileMemoryRepositoryTest::atomicMove,
                Files::createLink,
                staged -> {
                    throw new IOException("injected cleanup failure");
                });

        MemoryMutationResult result = repository.saveTopic(
                topic("durable-topic", "body"), Optional.empty());

        assertThat(result.status()).isEqualTo(MemoryMutationStatus.CREATED);
        MemoryTopic persisted = result.topic().orElseThrow();
        assertThat(repository.loadTopic("durable-topic")).contains(persisted);
        assertThat(Files.readString(root.resolve("MEMORY.md")))
                .contains("durable-topic.md");
        try (var entries = Files.list(root)) {
            assertThat(entries
                    .map(path -> path.getFileName().toString())
                    .filter(FileMemoryRepository::isInternalTemporaryName)
                    .toList()).hasSize(1);
        }
    }

    @Test
    void racingMoverCannotOverwriteAtomicallyPublishedCreate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("create-old-window"));
        Path target = root.resolve("published-topic.md");
        java.util.concurrent.atomic.AtomicBoolean moverCalled =
                new java.util.concurrent.atomic.AtomicBoolean();
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                (source, moveTarget, replace) -> {
                    if (!moveTarget.getFileName().toString().equals("MEMORY.md")) {
                        moverCalled.set(true);
                        Files.writeString(moveTarget, "racing-content");
                    }
                    atomicMove(source, moveTarget, replace);
                });

        MemoryMutationResult result = repository.saveTopic(
                topic("published-topic", "body"), Optional.empty());

        assertThat(result.status()).isEqualTo(MemoryMutationStatus.CREATED);
        assertThat(moverCalled).isFalse();
        assertThat(Files.readString(target)).doesNotContain("racing-content");
    }

    @Test
    void rejectsRacingCreateBeforeAtomicContentCommit() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("create-link-race"));
        Path target = root.resolve("race-topic.md");
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                FileMemoryRepositoryTest::atomicMove,
                (link, existing) -> {
                    Files.writeString(link, "racing-content");
                    throw new java.nio.file.FileAlreadyExistsException(link.toString());
                });

        MemoryMutationResult result = repository.saveTopic(
                topic("race-topic", "body"), Optional.empty());

        assertRejected(result, MemoryMutationDiagnosticKind.TOPIC_ALREADY_EXISTS);
        assertThat(Files.readString(target)).isEqualTo("racing-content");
        assertNoInternalTemps(root);
    }

    @Test
    void restoresDigestBoundDeleteWhenClaimedObjectChanges() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("delete-claimed-race"));
        MemoryTopic existing = repository(root)
                .saveTopic(topic("claimed-topic", "before"), Optional.empty())
                .topic().orElseThrow();
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                (source, target, replace) -> {
                    atomicMove(source, target, replace);
                    if (source.getFileName().toString().equals("claimed-topic.md")
                            && FileMemoryRepository.isInternalTemporaryName(
                                    target.getFileName().toString())) {
                        replaceSameLength(target, "before", "raced!");
                    }
                });

        MemoryMutationResult result = repository.deleteTopic(
                "claimed-topic", existing.contentDigest());

        assertRejected(result, MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT);
        assertThat(Files.readString(root.resolve("claimed-topic.md"))).contains("raced!");
        assertNoInternalTemps(root);
    }

    @Test
    void retainsRecoverableTombstoneOnDeleteRestoreCollision() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("delete-restore-collision"));
        Path target = root.resolve("claimed-topic.md");
        MemoryTopic existing = repository(root)
                .saveTopic(topic("claimed-topic", "before"), Optional.empty())
                .topic().orElseThrow();
        FileMemoryRepository repository = repository(
                root,
                (phase, ignored) -> { },
                (source, moveTarget, replace) -> {
                    atomicMove(source, moveTarget, replace);
                    if (source.equals(target)
                            && FileMemoryRepository.isInternalTemporaryName(
                                    moveTarget.getFileName().toString())) {
                        replaceSameLength(moveTarget, "before", "raced!");
                        Files.writeString(target, "collision-content");
                    }
                });

        MemoryMutationResult result = repository.deleteTopic(
                "claimed-topic", existing.contentDigest());

        assertRejected(result, MemoryMutationDiagnosticKind.FILE_CHANGED_DURING_COMMIT);
        assertThat(Files.readString(target)).isEqualTo("collision-content");
        try (var entries = Files.list(root)) {
            assertThat(entries
                    .filter(path -> FileMemoryRepository.isInternalTemporaryName(
                            path.getFileName().toString()))
                    .toList()).hasSize(1);
        }
    }

    @Test
    void reportsUnsupportedHardLinkWithoutLeavingHalfFile() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("link-failure"));
        FileMemoryRepository repository = repository(
                root,
                (phase, target) -> { },
                FileMemoryRepositoryTest::atomicMove,
                (target, existing) -> {
                    throw new UnsupportedOperationException("injected");
                });

        MemoryMutationResult result = repository.saveTopic(
                topic("atomic-topic", "body"), Optional.empty());

        assertRejected(result, MemoryMutationDiagnosticKind.IO_FAILURE);
        assertThat(Files.exists(root.resolve("atomic-topic.md"))).isFalse();
        assertNoInternalTemps(root);
    }

    @Test
    void keepsCommittedMutationAndReportsIndexFailure() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("index-failure"));
        FileMemoryRepository repository = repository(
                root,
                (phase, target) -> {
                    if (phase == FileMemoryRepository.MutationPhase.INDEX) {
                        throw new IOException("injected index failure");
                    }
                },
                FileMemoryRepositoryTest::atomicMove);

        MemoryMutationResult result = repository.saveTopic(
                topic("durable-topic", "body"), Optional.empty());

        assertThat(result.status()).isEqualTo(MemoryMutationStatus.CREATED);
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(MemoryMutationDiagnosticKind.INDEX_REBUILD_FAILED);
        assertThat(repository.loadTopic("durable-topic")).contains(result.topic().orElseThrow());
        assertThat(Files.exists(root.resolve("MEMORY.md"))).isFalse();
        assertNoInternalTemps(root);
    }

    @Test
    void rebuildsCorruptIndexFromCommittedTopics() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("corrupt-index"));
        FileMemoryRepository repository = repository(root);
        repository.saveTopic(topic("healthy-topic", "body"), Optional.empty());
        Files.write(root.resolve("MEMORY.md"), new byte[] {(byte) 0xC3, (byte) 0x28});

        repository.loadIndex();

        assertThat(Files.readString(root.resolve("MEMORY.md")))
                .contains("healthy-topic.md")
                .doesNotContain("�");
    }

    @Test
    void enforcesSerializedSizeAndTwoHundredTopicLimit() throws Exception {
        Path sizeRoot = Files.createDirectory(temporary.resolve("size-limit"));
        FileMemoryRepository sizeRepository = repository(sizeRoot);
        MemoryTopic oversized = topic(
                "large-topic",
                "x".repeat(FileMemoryCatalogAdapter.MAX_TOPIC_BYTES - 1));

        assertRejected(
                sizeRepository.saveTopic(oversized, Optional.empty()),
                MemoryMutationDiagnosticKind.CONTENT_LIMIT_EXCEEDED);
        assertThat(Files.exists(sizeRoot.resolve("large-topic.md"))).isFalse();

        Path countRoot = Files.createDirectory(temporary.resolve("count-limit"));
        for (int index = 0; index < FileMemoryCatalogAdapter.MAX_TOPICS; index++) {
            Files.writeString(
                    countRoot.resolve("existing-" + String.format("%03d", index) + ".md"),
                    "not required to be valid for the directory-entry budget");
        }
        FileMemoryRepository countRepository = repository(countRoot);

        assertRejected(
                countRepository.saveTopic(topic("new-topic", "body"), Optional.empty()),
                MemoryMutationDiagnosticKind.TOPIC_LIMIT_REACHED);
        assertThat(Files.exists(countRoot.resolve("new-topic.md"))).isFalse();
    }

    @Test
    void rejectsInvalidUtf8AndSymbolicLinkTargets() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("unsafe-targets"));
        Files.write(root.resolve("invalid-utf8.md"), new byte[] {(byte) 0xC3, (byte) 0x28});
        FileMemoryRepository repository = repository(root);
        assertThat(repository.loadTopic("invalid-utf8")).isEmpty();
        assertRejected(
                repository.deleteTopic("invalid-utf8", "0".repeat(64)),
                MemoryMutationDiagnosticKind.DIGEST_CONFLICT);

        Path outside = Files.writeString(temporary.resolve("outside.md"), "outside");
        Path link = root.resolve("linked-topic.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("当前环境不能创建 Symlink");
        }
        assertThat(repository.loadTopic("linked-topic")).isEmpty();
        assertRejected(
                repository.deleteTopic("linked-topic", "0".repeat(64)),
                MemoryMutationDiagnosticKind.UNSAFE_PATH);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionTarget() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("junction-root"));
        Path target = Files.createDirectory(temporary.resolve("junction-target"));
        Path junction = root.resolve("linked-topic.md");
        Process process;
        try {
            process = new ProcessBuilder(
                    "cmd.exe", "/u", "/d", "/c", "mklink", "/J",
                    junction.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException unavailable) {
            Assumptions.abort("当前 Windows 策略禁止启动 Junction 探测进程");
            return;
        }
        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            String output = new String(outputBytes, java.nio.charset.StandardCharsets.UTF_16LE)
                    .trim();
            String normalized = output.toLowerCase(Locale.ROOT);
            if (normalized.contains("access is denied")
                    || normalized.contains("access denied")
                    || output.contains("拒绝访问")
                    || output.contains("客户端没有所需的特权")
                    || normalized.contains("privilege is not held")) {
                Assumptions.abort("当前 Windows 策略禁止创建 Junction");
            }
            throw new AssertionError("Junction 创建失败: exit=" + exit + ", output=" + output);
        }
        assertThat(Files.isDirectory(junction)).isTrue();
        try {
            FileMemoryRepository repository = repository(root);
            assertRejected(
                    repository.deleteTopic("linked-topic", "0".repeat(64)),
                    MemoryMutationDiagnosticKind.UNSAFE_PATH);
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void excludesOnlyStrictInternalTempNamesAndBoundsAttackerFlood() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("temp-flood"));
        Files.writeString(
                root.resolve(".cc-java-memory-" + "a".repeat(32) + ".tmp"),
                "orphan internal stage");
        for (int index = 0; index < 250; index++) {
            Files.writeString(
                    root.resolve(".cc-java-memory-attacker-" + index + ".md"),
                    "attacker-controlled entry");
        }
        FileMemoryCatalogAdapter adapter = new FileMemoryCatalogAdapter(root);

        var catalog = adapter.rebuild();

        assertThat(catalog.entries()).isEmpty();
        assertThat(catalog.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .contains(io.github.liumaishenjian.ccjava.domain.MemoryDiagnosticKind.TOPIC_LIMIT_REACHED);
        assertThat(catalog.diagnostics()).hasSize(201);
        assertThat(FileMemoryRepository.isInternalTemporaryName(
                ".cc-java-memory-" + "a".repeat(32) + ".tmp")).isTrue();
        assertThat(FileMemoryRepository.isInternalTemporaryName(
                ".cc-java-memory-attacker-hidden.md")).isFalse();
    }

    private static FileMemoryRepository repository(Path root) {
        return new FileMemoryRepository(root);
    }

    private static FileMemoryRepository repository(
            Path root,
            FileMemoryRepository.MutationObserver observer,
            FileMemoryRepository.AtomicMover mover) {
        return new FileMemoryRepository(root, new SecretCandidatePolicy(), observer, mover);
    }

    private static FileMemoryRepository repository(
            Path root,
            FileMemoryRepository.MutationObserver observer,
            FileMemoryRepository.AtomicMover mover,
            FileMemoryRepository.CreateLinker linker) {
        return new FileMemoryRepository(
                root, new SecretCandidatePolicy(), observer, mover, linker);
    }

    private static FileMemoryRepository repository(
            Path root,
            FileMemoryRepository.MutationObserver observer,
            FileMemoryRepository.AtomicMover mover,
            FileMemoryRepository.CreateLinker linker,
            FileMemoryRepository.StagedCleaner cleaner) {
        return new FileMemoryRepository(
                root, new SecretCandidatePolicy(), observer, mover, linker, cleaner);
    }

    private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
        if (replace) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void replaceSameLength(Path target, String before, String after)
            throws IOException {
        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(after.getBytes(StandardCharsets.UTF_8))
                .hasSameSizeAs(before.getBytes(StandardCharsets.UTF_8));
        Path replacement = target.resolveSibling("replacement.tmp");
        Files.writeString(replacement, content.replace(before, after), StandardCharsets.UTF_8);
        Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertNoInternalTemps(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(FileMemoryRepository::isInternalTemporaryName);
        }
    }

    private static MemoryTopic topic(String name, String body) {
        return MemoryTopic.candidate(
                name,
                MemoryKind.PROJECT_STATE,
                "safe hook",
                body,
                LocalDate.of(2026, 8, 4));
    }

    private static void assertRejected(
            MemoryMutationResult result,
            MemoryMutationDiagnosticKind kind) {
        assertThat(result.status()).isEqualTo(MemoryMutationStatus.REJECTED);
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.kind())
                .containsExactly(kind);
    }
}
