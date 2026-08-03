package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.CheckpointDiff;
import io.github.liumaishenjian.ccjava.domain.CheckpointId;
import io.github.liumaishenjian.ccjava.domain.CheckpointPhase;
import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.CheckpointUndoResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证普通文件 Checkpoint、显式 Diff、compare-before-restore Undo 与安全冲突。 */
class FileCheckpointCoordinatorTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void restoresExistingFileAndMakesRepeatedUndoIdempotent() throws Exception {
        Fixture fixture = fixture("existing");
        try {
            Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
            ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");

            CheckpointId checkpoint = fixture.checkpoints.create(
                    invocation, new CheckpointTarget("sample.txt", true));
            fixture.toolStarted(invocation);
            Files.writeString(file, "after\n");
            ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
            fixture.checkpoints.complete(invocation, checkpoint, result);
            fixture.toolCompleted(invocation, result);
            fixture.finishRun();

            assertThat(fixture.checkpoints.diff(fixture.sessionId, checkpoint).status())
                    .isEqualTo(CheckpointDiff.Status.CHANGED);
            assertThat(fixture.checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.RESTORED);
            assertThat(Files.readString(file)).isEqualTo("before\n");
            assertThat(fixture.checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.ALREADY_RESTORED);
            assertThat(Files.readString(file)).isEqualTo("before\n");
            assertThat(Files.readString(fixture.journalPath(), StandardCharsets.UTF_8))
                    .contains("checkpoint.created", "checkpoint.completed", "checkpoint.undo.completed")
                    .doesNotContain(fixture.workspace.toString(), fixture.storeRoot.toString());
        } finally {
            fixture.close();
        }
    }

    @Test
    void deletesAgentCreatedFileOnlyWhenDigestStillMatches() throws Exception {
        Fixture fixture = fixture("new-file");
        try {
            ToolInvocation invocation = fixture.start("call-write", "write_file", "created.txt");
            CheckpointId checkpoint = fixture.checkpoints.create(
                    invocation, new CheckpointTarget("created.txt", false));
            fixture.toolStarted(invocation);
            Path file = Files.writeString(fixture.workspace.resolve("created.txt"), "agent\n");
            ToolResult result = ToolResult.success("call-write", "write_file", "created");
            fixture.checkpoints.complete(invocation, checkpoint, result);
            fixture.toolCompleted(invocation, result);
            fixture.finishRun();

            assertThat(fixture.checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.RESTORED);
            assertThat(file).doesNotExist();
        } finally {
            fixture.close();
        }
    }

    @Test
    void refusesUndoAfterUserChangesPostImage() throws Exception {
        Fixture fixture = fixture("conflict");
        try {
            Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
            ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
            CheckpointId checkpoint = fixture.checkpoints.create(
                    invocation, new CheckpointTarget("sample.txt", true));
            fixture.toolStarted(invocation);
            Files.writeString(file, "agent\n");
            ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
            fixture.checkpoints.complete(invocation, checkpoint, result);
            fixture.toolCompleted(invocation, result);
            fixture.finishRun();
            Files.writeString(file, "user\n");

            assertThat(fixture.checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.CONFLICT);
            assertThat(Files.readString(file)).isEqualTo("user\n");
            assertThat(Files.readString(fixture.journalPath(), StandardCharsets.UTF_8))
                    .doesNotContain("checkpoint.undo.completed");
        } finally {
            fixture.close();
        }
    }

    @Test
    void recordsKnownAbsentPostStateForFailedNewFileWithoutFencing() throws Exception {
        Fixture fixture = fixture("known-absent");
        try {
            ToolInvocation invocation = fixture.start("call-write", "write_file", "missing.txt");
            CheckpointId checkpoint = fixture.checkpoints.create(
                    invocation, new CheckpointTarget("missing.txt", false));
            fixture.toolStarted(invocation);
            ToolResult result = ToolResult.failure(
                    "call-write",
                    "write_file",
                    io.github.liumaishenjian.ccjava.domain.ToolError.of(
                            io.github.liumaishenjian.ccjava.domain.ToolErrorCode.EXECUTION_FAILED,
                            "failed"));
            fixture.checkpoints.complete(invocation, checkpoint, result);
            fixture.toolCompleted(invocation, result);
            fixture.finishRun();

            assertThat(fixture.checkpoints.list(fixture.sessionId))
                    .singleElement()
                    .extracting(summary -> summary.phase())
                    .isEqualTo(CheckpointPhase.COMPLETED_ABSENT);
            assertThat(Files.readString(fixture.journalPath(), StandardCharsets.UTF_8))
                    .contains("\"postState\":\"ABSENT\"", "tool.completed");
            assertThat(fixture.checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.RESTORED);
        } finally {
            fixture.close();
        }
    }

    @Test
    void requiresExplicitConfirmation() throws Exception {
        Fixture fixture = fixture("gates");
        try {
            Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
            ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
            CheckpointId checkpoint = fixture.checkpoints.create(
                    invocation, new CheckpointTarget("sample.txt", true));
            fixture.toolStarted(invocation);
            Files.writeString(file, "after\n");
            ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
            fixture.checkpoints.complete(invocation, checkpoint, result);
            fixture.toolCompleted(invocation, result);
            fixture.finishRun();

            assertThatThrownBy(() -> fixture.checkpoints.undo(
                            fixture.sessionId, checkpoint, false))
                    .isInstanceOf(SessionOpenException.class)
                    .extracting(failure -> ((SessionOpenException) failure).code())
                    .isEqualTo("UNDO_CONFIRMATION_REQUIRED");
            assertThat(Files.readString(file)).isEqualTo("after\n");
        } finally {
            fixture.close();
        }
    }

    @Test
    void undoPreparedPhaseBlocksResumeAndSecondUndoAfterRestart() throws Exception {
        Fixture fixture = fixture("undo-prepared");
        Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
        ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
        FileCheckpointCoordinator crashing = new FileCheckpointCoordinator(
                fixture.storeRoot,
                new WorkspaceGuard(fixture.workspace),
                fixture.store,
                new FileCheckpointCoordinator.FaultInjector() {
                    @Override
                    public void afterUndoPrepared() {
                        throw new SessionOpenException("TEST_CRASH", "simulated crash");
                    }
                });
        CheckpointId checkpoint = crashing.create(
                invocation, new CheckpointTarget("sample.txt", true));
        fixture.toolStarted(invocation);
        Files.writeString(file, "after\n");
        ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
        crashing.complete(invocation, checkpoint, result);
        fixture.toolCompleted(invocation, result);
        fixture.finishRun();

        assertThatThrownBy(() -> crashing.undo(fixture.sessionId, checkpoint, true))
                .isInstanceOfSatisfying(
                        SessionOpenException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TEST_CRASH"));
        assertThat(Files.readString(file)).isEqualTo("after\n");
        fixture.close();

        try (FileSessionStore reopened = fixture.store(100)) {
            SessionOpenResult inspected = reopened.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, Optional.of(fixture.sessionId)),
                    fixture.spec);
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind
                            .CHECKPOINT_UNDO_UNCERTAIN);
            assertThatThrownBy(() -> reopened.open(
                            new SessionOpenRequest(
                                    SessionOpenMode.RESUME,
                                    Optional.of(fixture.sessionId)),
                            fixture.spec))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }

        try (FileSessionStore reader = fixture.store(200)) {
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    fixture.storeRoot, new WorkspaceGuard(fixture.workspace), reader);
            assertThat(checkpoints.list(fixture.sessionId))
                    .extracting(summary -> summary.phase())
                    .containsExactly(CheckpointPhase.UNDO_PREPARED);
            assertThatThrownBy(() -> checkpoints.undo(fixture.sessionId, checkpoint, true))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SESSION_FENCED"));
        }
    }

    @Test
    void retainsDurablePreImageWhenCreatedJournalResultIsUncertain() throws Exception {
        Fixture fixture = fixture("created-uncertain");
        try {
            Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
            ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
            FileCheckpointCoordinator uncertain = fixture.coordinator(
                    new FileCheckpointCoordinator.FaultInjector() {
                        @Override
                        public void beforeCheckpointCreatedJournal() {
                            throw new SessionOpenException("TEST_JOURNAL", "simulated journal failure");
                        }
                    });

            assertThatThrownBy(() -> uncertain.create(
                            invocation, new CheckpointTarget("sample.txt", true)))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("CHECKPOINT_JOURNAL_UNCERTAIN"));
            assertThat(uncertain.list(fixture.sessionId))
                    .singleElement()
                    .extracting(summary -> summary.phase())
                    .isEqualTo(CheckpointPhase.CREATE_JOURNAL_UNCERTAIN);
            Path directory = fixture.onlyCheckpointDirectory();
            assertThat(directory.resolve("pre-image.bin")).isRegularFile();
            assertThat(Files.readAllBytes(directory.resolve("pre-image.bin")))
                    .isEqualTo("before\n".getBytes(StandardCharsets.UTF_8));
        } finally {
            fixture.close();
        }
    }

    @Test
    void preservesPostJournalUncertainPhaseAndRejectsUndo() throws Exception {
        Fixture fixture = fixture("post-uncertain");
        try {
            Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
            ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
            FileCheckpointCoordinator uncertain = fixture.coordinator(
                    new FileCheckpointCoordinator.FaultInjector() {
                        @Override
                        public void beforeCheckpointCompletedJournal() {
                            throw new SessionOpenException("TEST_JOURNAL", "simulated journal failure");
                        }
                    });
            CheckpointId checkpoint = uncertain.create(
                    invocation, new CheckpointTarget("sample.txt", true));
            fixture.toolStarted(invocation);
            Files.writeString(file, "after\n");

            assertThatThrownBy(() -> uncertain.complete(
                            invocation,
                            checkpoint,
                            ToolResult.success("call-patch", "apply_patch", "modified")))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("CHECKPOINT_JOURNAL_UNCERTAIN"));
            assertThat(uncertain.list(fixture.sessionId))
                    .singleElement()
                    .extracting(summary -> summary.phase())
                    .isEqualTo(CheckpointPhase.POST_JOURNAL_UNCERTAIN);
            assertThatThrownBy(() -> uncertain.undo(fixture.sessionId, checkpoint, true))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("CHECKPOINT_STATE_UNCERTAIN"));
            assertThat(Files.readString(file)).isEqualTo("after\n");
        } finally {
            fixture.close();
        }
    }

    @Test
    void undoJournalUncertainBlocksResumeAndAutomaticRetry() throws Exception {
        Fixture fixture = completedFixture("undo-journal-uncertain", "after\n");
        Path file = fixture.workspace.resolve("sample.txt");
        CheckpointId checkpoint = fixture.checkpoints.list(fixture.sessionId).getFirst().id();
        FileCheckpointCoordinator uncertain = fixture.coordinator(
                new FileCheckpointCoordinator.FaultInjector() {
                    @Override
                    public void beforeUndoCompletedJournal() {
                        throw new SessionOpenException("TEST_JOURNAL", "simulated journal failure");
                    }
                });

        assertThatThrownBy(() -> uncertain.undo(fixture.sessionId, checkpoint, true))
                .isInstanceOfSatisfying(
                        SessionOpenException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_JOURNAL_UNCERTAIN"));
        assertThat(Files.readString(file)).isEqualTo("before\n");
        assertThat(uncertain.list(fixture.sessionId))
                .singleElement()
                .extracting(summary -> summary.phase())
                .isEqualTo(CheckpointPhase.UNDO_JOURNAL_UNCERTAIN);
        fixture.close();

        try (FileSessionStore reopened = fixture.store(500)) {
            assertThatThrownBy(() -> reopened.open(
                            new SessionOpenRequest(
                                    SessionOpenMode.RESUME,
                                    Optional.of(fixture.sessionId)),
                            fixture.spec))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }
    }

    @Test
    void finalDigestRecheckRejectsExistingFileRaceAfterStagedForce() throws Exception {
        Fixture fixture = completedFixture("existing-race", "after\n");
        try {
            Path file = fixture.workspace.resolve("sample.txt");
            CheckpointId checkpoint = fixture.checkpoints.list(fixture.sessionId).getFirst().id();
            FileCheckpointCoordinator racing = fixture.coordinator(
                    new FileCheckpointCoordinator.FaultInjector() {
                        @Override
                        public void afterUndoStaged() {
                            try {
                                Files.writeString(file, "user-race\n");
                            } catch (java.io.IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        }
                    });

            assertThatThrownBy(() -> racing.undo(fixture.sessionId, checkpoint, true))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_CONFLICT"));
            assertThat(Files.readString(file)).isEqualTo("user-race\n");
            assertThat(racing.list(fixture.sessionId))
                    .singleElement()
                    .extracting(summary -> summary.phase())
                    .isEqualTo(CheckpointPhase.UNDO_PREPARED);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsSymlinkTargetAndCorruptedPreImageWithoutRestoring() throws Exception {
        Fixture fixture = completedFixture("link-and-backup", "after\n");
        try {
            CheckpointId checkpoint = fixture.checkpoints.list(fixture.sessionId).getFirst().id();
            Path file = fixture.workspace.resolve("sample.txt");
            Path outside = Files.writeString(temporaryRoot.resolve("outside.txt"), "outside\n");
            Files.delete(file);
            try {
                Files.createSymbolicLink(file, outside);
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException failure) {
                Assumptions.abort("当前环境不能创建 Symlink: " + failure.getClass().getSimpleName());
            }
            assertThatThrownBy(() -> fixture.checkpoints.undo(fixture.sessionId, checkpoint, true))
                    .isInstanceOf(SessionOpenException.class);
            assertThat(Files.readString(outside)).isEqualTo("outside\n");
        } finally {
            fixture.close();
        }

        Fixture corrupt = completedFixture("corrupt-backup", "after\n");
        try {
            CheckpointId checkpoint = corrupt.checkpoints.list(corrupt.sessionId).getFirst().id();
            Files.writeString(
                    corrupt.onlyCheckpointDirectory().resolve("pre-image.bin"),
                    "corrupt\n",
                    StandardCharsets.UTF_8);
            assertThatThrownBy(() -> corrupt.checkpoints.undo(corrupt.sessionId, checkpoint, true))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_CORRUPT"));
            assertThat(Files.readString(corrupt.workspace.resolve("sample.txt"))).isEqualTo("after\n");
        } finally {
            corrupt.close();
        }
    }

    @Test
    void rejectsInvalidDirectoryNameMetadataIdAndDigest() throws Exception {
        Fixture invalidDirectory = fixture("invalid-directory");
        invalidDirectory.close();
        Path invalidRoot = invalidDirectory.storeRoot
                .resolve(invalidDirectory.sessionId.value())
                .resolve("checkpoints")
                .resolve("not-a-checkpoint");
        Files.createDirectories(invalidRoot);
        Files.writeString(invalidRoot.resolve("metadata.json"), "{}", StandardCharsets.UTF_8);
        try (FileSessionStore reader = invalidDirectory.store(100)) {
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    invalidDirectory.storeRoot,
                    new WorkspaceGuard(invalidDirectory.workspace),
                    reader);
            assertThatThrownBy(() -> checkpoints.list(invalidDirectory.sessionId))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_CORRUPT"));
        }

        Fixture invalidMetadata = completedFixture("invalid-metadata", "after\n");
        Path metadata = invalidMetadata.onlyMetadata();
        String original = Files.readString(metadata, StandardCharsets.UTF_8);
        invalidMetadata.close();
        Files.writeString(
                metadata,
                original.replaceFirst("checkpoint-[^\\\"]+", "checkpoint-other"),
                StandardCharsets.UTF_8);
        try (FileSessionStore reader = invalidMetadata.store(200)) {
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    invalidMetadata.storeRoot,
                    new WorkspaceGuard(invalidMetadata.workspace),
                    reader);
            assertThatThrownBy(() -> checkpoints.list(invalidMetadata.sessionId))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_CORRUPT"));
        }

        Fixture invalidDigest = completedFixture("invalid-digest", "after\n");
        Path digestMetadata = invalidDigest.onlyMetadata();
        String digestText = Files.readString(digestMetadata, StandardCharsets.UTF_8);
        invalidDigest.close();
        Files.writeString(
                digestMetadata,
                digestText.replaceFirst("[0-9a-f]{64}", "A".repeat(64)),
                StandardCharsets.UTF_8);
        try (FileSessionStore reader = invalidDigest.store(300)) {
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    invalidDigest.storeRoot,
                    new WorkspaceGuard(invalidDigest.workspace),
                    reader);
            assertThatThrownBy(() -> checkpoints.list(invalidDigest.sessionId))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_CORRUPT"));
        }
    }

    @Test
    void rejectsEnumerationBeyondCheckpointLimitBeforeMaterializingAllMetadata() throws Exception {
        Fixture fixture = fixture("limit");
        fixture.close();
        Path checkpointsRoot = fixture.storeRoot
                .resolve(fixture.sessionId.value())
                .resolve("checkpoints");
        Files.createDirectories(checkpointsRoot);
        for (int index = 0; index <= 1_000; index++) {
            Files.createDirectory(checkpointsRoot.resolve("checkpoint-limit-" + index));
        }

        try (FileSessionStore reader = fixture.store(100)) {
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    fixture.storeRoot, new WorkspaceGuard(fixture.workspace), reader);
            assertThatThrownBy(() -> checkpoints.list(fixture.sessionId))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CHECKPOINT_LIMIT"));
        }
    }

    @Test
    void persistedCheckpointCanBeListedAndUndoneAfterStoreReopen() throws Exception {
        Fixture fixture = fixture("reopen");
        Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
        ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
        CheckpointId checkpoint = fixture.checkpoints.create(
                invocation, new CheckpointTarget("sample.txt", true));
        fixture.toolStarted(invocation);
        Files.writeString(file, "after\n");
        ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
        fixture.checkpoints.complete(invocation, checkpoint, result);
        fixture.toolCompleted(invocation, result);
        fixture.finishRun();
        fixture.close();

        try (FileSessionStore reopened = fixture.store(100)) {
            reopened.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(fixture.sessionId)),
                    fixture.spec);
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    fixture.storeRoot, new WorkspaceGuard(fixture.workspace), reopened);
            assertThat(checkpoints.list(fixture.sessionId))
                    .extracting(summary -> summary.id())
                    .containsExactly(checkpoint);
            assertThat(checkpoints.list(fixture.sessionId))
                    .extracting(summary -> summary.phase())
                    .containsExactly(CheckpointPhase.COMPLETED_PRESENT);
            assertThat(checkpoints.undo(fixture.sessionId, checkpoint, true).status())
                    .isEqualTo(CheckpointUndoResult.Status.RESTORED);
            assertThat(Files.readString(file)).isEqualTo("before\n");
        }
    }

    private Fixture fixture(String name) throws Exception {
        Path workspace = temporaryRoot.resolve(name).resolve("workspace");
        Path storeRoot = temporaryRoot.resolve(name).resolve("store");
        Files.createDirectories(workspace);
        return new Fixture(workspace, storeRoot);
    }

    private Fixture completedFixture(String name, String postContent) throws Exception {
        Fixture fixture = fixture(name);
        Path file = Files.writeString(fixture.workspace.resolve("sample.txt"), "before\n");
        ToolInvocation invocation = fixture.start("call-patch", "apply_patch", "sample.txt");
        CheckpointId checkpoint = fixture.checkpoints.create(
                invocation, new CheckpointTarget("sample.txt", true));
        fixture.toolStarted(invocation);
        Files.writeString(file, postContent);
        ToolResult result = ToolResult.success("call-patch", "apply_patch", "modified");
        fixture.checkpoints.complete(invocation, checkpoint, result);
        fixture.toolCompleted(invocation, result);
        fixture.finishRun();
        return fixture;
    }

    private static final class Fixture {
        private final Path workspace;
        private final Path storeRoot;
        private final SessionSpec spec = new SessionSpec("test", Map.of());
        private final FileSessionStore store;
        private final FileCheckpointCoordinator checkpoints;
        private final SessionId sessionId;
        private RunId runId;
        private boolean runActive;

        private Fixture(Path workspace, Path storeRoot) throws Exception {
            this.workspace = workspace;
            this.storeRoot = storeRoot;
            store = store(1);
            sessionId = store.create(spec).id();
            checkpoints = new FileCheckpointCoordinator(
                    storeRoot, new WorkspaceGuard(workspace), store);
        }

        private ToolInvocation start(String callId, String toolName, String path) {
            runId = new RunId("run-" + callId);
            runActive = true;
            store.runStarted(sessionId, runId, new UserMessage("write"));
            ToolCall call = new ToolCall(callId, toolName, new JsonObject(Map.of("path", path)));
            store.assistantAppended(
                    sessionId,
                    runId,
                    io.github.liumaishenjian.ccjava.domain.AssistantMessage.tools(
                            java.util.List.of(call)));
            return new ToolInvocation(sessionId, runId, 1, call);
        }

        private void toolStarted(ToolInvocation invocation) {
            store.toolStarted(
                    sessionId,
                    runId,
                    invocation.ordinal(),
                    invocation.call().id(),
                    invocation.call().name(),
                    ToolEffect.WRITE_WORKSPACE);
        }

        private void toolCompleted(ToolInvocation invocation, ToolResult result) {
            store.toolCompleted(sessionId, runId, invocation.ordinal(), result);
        }

        private void finishRun() {
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            runActive = false;
        }

        private void finishRunIfActive() {
            if (runActive) {
                finishRun();
            }
        }

        private void close() {
            if (store.find(sessionId).isPresent()) {
                store.close(sessionId);
            }
            store.close();
        }

        private Path journalPath() {
            return storeRoot.resolve(sessionId.value()).resolve("session.jsonl");
        }

        private Path onlyMetadata() throws java.io.IOException {
            return onlyCheckpointDirectory().resolve("metadata.json");
        }

        private Path onlyCheckpointDirectory() throws java.io.IOException {
            Path checkpointsRoot = storeRoot.resolve(sessionId.value()).resolve("checkpoints");
            try (var directories = Files.list(checkpointsRoot)) {
                return directories.findFirst().orElseThrow();
            }
        }

        private FileCheckpointCoordinator coordinator(
                FileCheckpointCoordinator.FaultInjector faults) throws java.io.IOException {
            return new FileCheckpointCoordinator(
                    storeRoot,
                    new WorkspaceGuard(workspace),
                    store,
                    faults);
        }

        private FileSessionStore store(int firstId) {
            return new FileSessionStore(
                    storeRoot,
                    workspace,
                    new TestIds(firstId),
                    new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop()),
                    Clock.systemUTC());
        }
    }

    private static final class TestIds implements AgentIdGenerator {
        private final AtomicInteger ids;

        private TestIds(int first) {
            ids = new AtomicInteger(first);
        }

        @Override
        public SessionId newSessionId() {
            return new SessionId("session-checkpoint-" + ids.getAndIncrement());
        }

        @Override
        public RunId newRunId() {
            return new RunId("run-checkpoint-" + ids.getAndIncrement());
        }
    }
}
