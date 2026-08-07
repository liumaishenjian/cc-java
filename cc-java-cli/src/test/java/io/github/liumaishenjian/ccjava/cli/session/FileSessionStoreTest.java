package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssueKind;
import io.github.liumaishenjian.ccjava.core.ToolResolutionReason;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 S06 文件 Session Adapter 的恢复边界和本机单 Writer lease。 */
class FileSessionStoreTest {

    private static final SessionSpec SPEC = new SessionSpec(
            "test instructions",
            Map.of("model", "fake-model"));

    @TempDir
    Path temporaryRoot;

    @Test
    void roundTripsResolvedAndCompletedResultsWithoutTokenRecords() throws IOException {
        Path workspace = workspace("round-trip");
        Path storeRoot = storeRoot("round-trip");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId firstRun = new RunId("run-1");
            ToolCall denied = new ToolCall("call-denied", "write_file", JsonObject.empty());
            store.runStarted(sessionId, firstRun, new UserMessage("first"));
            store.assistantAppended(sessionId, firstRun, AssistantMessage.tools(java.util.List.of(denied)));
            store.toolResolved(
                    sessionId,
                    firstRun,
                    1,
                    ToolResult.denied("call-denied", "write_file", "denied"),
                    ToolResolutionReason.PERMISSION_DENIED);
            store.runCompleted(sessionId, firstRun, StopReason.COMPLETED);

            RunId secondRun = new RunId("run-2");
            ToolCall read = new ToolCall("call-read", "read_file", JsonObject.empty());
            store.runStarted(sessionId, secondRun, new UserMessage("second"));
            store.assistantAppended(sessionId, secondRun, AssistantMessage.tools(java.util.List.of(read)));
            store.toolStarted(
                    sessionId,
                    secondRun,
                    1,
                    read.id(),
                    read.name(),
                    ToolEffect.READ_WORKSPACE);
            store.toolCompleted(
                    sessionId,
                    secondRun,
                    1,
                    ToolResult.success(read.id(), read.name(), "bounded result"));
            store.assistantAppended(sessionId, secondRun, AssistantMessage.text("done"));
            store.runCompleted(sessionId, secondRun, StopReason.COMPLETED);
            store.close(sessionId);
        }

        try (FileSessionStore resumedStore = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumed = resumedStore.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);

            assertThat(resumed.readOnly()).isFalse();
            assertThat(resumed.issues()).isEmpty();
            assertThat(resumed.session().messages()).hasSize(7);
            assertThat(resumed.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .extracting(message -> ((ToolResultMessage) message).result().callId())
                    .containsExactly("call-denied", "call-read");
            assertThat(Files.readAllLines(journal(storeRoot, sessionId), StandardCharsets.UTF_8))
                    .noneMatch(line -> line.contains("token") || line.contains("chunk"));
        }
    }

    @Test
    void forkCopiesCompletedHistoryWithoutChangingSourceAndResumesCleanly() throws IOException {
        Path workspace = workspace("fork");
        Path storeRoot = storeRoot("fork");
        SessionId sourceId;
        java.util.List<io.github.liumaishenjian.ccjava.domain.AgentMessage> sourceMessages;
        byte[] sourceBytes;
        SessionId forkId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sourceId = store.create(SPEC).id();
            RunId runId = new RunId("run-fork-source");
            ToolCall call = new ToolCall("call-fork-read", "read_file", JsonObject.empty());
            store.runStarted(sourceId, runId, new UserMessage("inspect"));
            store.assistantAppended(sourceId, runId, AssistantMessage.tools(java.util.List.of(call)));
            store.toolStarted(
                    sourceId,
                    runId,
                    1,
                    call.id(),
                    call.name(),
                    ToolEffect.READ_WORKSPACE);
            store.toolCompleted(
                    sourceId,
                    runId,
                    1,
                    ToolResult.success(call.id(), call.name(), "fork-result"));
            store.assistantAppended(sourceId, runId, AssistantMessage.text("source done"));
            store.runCompleted(sourceId, runId, StopReason.COMPLETED);
            sourceMessages = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.INSPECT,
                            java.util.Optional.of(sourceId)),
                    SPEC).session().messages();
            sourceBytes = Files.readAllBytes(journal(storeRoot, sourceId));

            SessionOpenResult forked = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.FORK,
                            java.util.Optional.of(sourceId)),
                    SPEC);
            forkId = forked.session().id();
            assertThat(forkId).isNotEqualTo(sourceId);
            assertThat(forked.parentSessionId()).contains(sourceId);
            assertThat(forked.issues()).isEmpty();
            assertThat(forked.session().messages()).isEqualTo(sourceMessages);
            assertThat(forked.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .hasSize(1);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
            store.close(forkId);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
        }

        try (FileSessionStore resumedStore = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumedFork = resumedStore.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(forkId)),
                    SPEC);
            assertThat(resumedFork.parentSessionId()).contains(sourceId);
            assertThat(resumedFork.issues()).isEmpty();
            assertThat(resumedFork.session().messages()).isEqualTo(sourceMessages);
            assertThat(resumedFork.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .hasSize(1);
            assertThat(Files.readAllBytes(journal(storeRoot, sourceId))).isEqualTo(sourceBytes);
        }
    }

    @Test
    void continueSkipsNewerDamagedOrUnfinishedSession() throws IOException {
        Path workspace = workspace("continue-clean");
        Path storeRoot = storeRoot("continue-clean");
        SessionId cleanId;
        SessionId damagedId;
        SessionId unfinishedId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            cleanId = store.create(SPEC).id();
            store.close(cleanId);
            damagedId = store.create(SPEC).id();
            store.close(damagedId);
            unfinishedId = store.create(SPEC).id();
            store.runStarted(
                    unfinishedId,
                    new RunId("run-unfinished-latest"),
                    new UserMessage("unfinished"));
        }
        Files.writeString(
                journal(storeRoot, damagedId),
                "{\"schemaMajor\":1",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Files.setLastModifiedTime(
                journal(storeRoot, cleanId),
                FileTime.from(Instant.parse("2026-08-03T00:00:00Z")));
        Files.setLastModifiedTime(
                journal(storeRoot, damagedId),
                FileTime.from(Instant.parse("2026-08-03T00:01:00Z")));
        Files.setLastModifiedTime(
                journal(storeRoot, unfinishedId),
                FileTime.from(Instant.parse("2026-08-03T00:02:00Z")));

        try (FileSessionStore continuing = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = continuing.open(
                    SessionOpenRequest.continueLatest(),
                    SPEC);
            assertThat(result.session().id()).isEqualTo(cleanId);
            assertThat(result.mode()).isEqualTo(SessionOpenMode.CONTINUE);
            assertThat(result.issues()).isEmpty();
        }
    }

    @Test
    void sameStoreDuplicateResumePreservesOriginalWriter() throws IOException {
        Path workspace = workspace("same-store-resume");
        Path storeRoot = storeRoot("same-store-resume");
        SessionId sessionId = createAndClose(storeRoot, workspace);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            SessionOpenResult original = store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SESSION_ACTIVE"));

            RunId runId = new RunId("run-after-duplicate-resume");
            store.runStarted(sessionId, runId, new UserMessage("still writable"));
            store.assistantAppended(sessionId, runId, AssistantMessage.text("still active"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            assertThat(store.find(sessionId)).contains(original.session());
            store.close(sessionId);
        }

        try (FileSessionStore reopened = store(storeRoot, workspace, 200)) {
            SessionOpenResult resumed = reopened.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(resumed.issues()).isEmpty();
            assertThat(resumed.session().messages())
                    .containsSubsequence(
                            new UserMessage("still writable"),
                            AssistantMessage.text("still active"));
        }
    }

    @Test
    void rejectsWorkspaceMismatch() throws IOException {
        Path firstWorkspace = workspace("workspace-a");
        Path secondWorkspace = workspace("workspace-b");
        Path storeRoot = storeRoot("workspace-binding");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, firstWorkspace, 1)) {
            sessionId = store.create(SPEC).id();
            store.close(sessionId);
        }

        try (FileSessionStore otherWorkspace = store(storeRoot, secondWorkspace, 100)) {
            assertThatThrownBy(() -> otherWorkspace.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_MISMATCH"));
        }
    }

    @Test
    void enforcesSingleWriterWhileInspectRemainsReadOnly() throws IOException {
        Path workspace = workspace("lease");
        Path storeRoot = storeRoot("lease");
        try (FileSessionStore writer = store(storeRoot, workspace, 1);
             FileSessionStore contender = store(storeRoot, workspace, 100)) {
            SessionId sessionId = writer.create(SPEC).id();

            assertThatThrownBy(() -> contender.open(
                    new SessionOpenRequest(
                            SessionOpenMode.RESUME,
                            java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SESSION_ACTIVE"));

            SessionOpenResult inspected = contender.open(
                    new SessionOpenRequest(
                            SessionOpenMode.INSPECT,
                            java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.readOnly()).isTrue();
            assertThat(inspected.session().isFenced()).isTrue();
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(SessionRecoveryIssueKind.READ_ONLY_INSPECT);
            assertThat(contender.find(sessionId)).isEmpty();
            assertThat(writer.find(sessionId)).isPresent();
        }
    }

    @Test
    void damagedTrailingRecordIsInspectWarningButNotWritable() throws IOException {
        Path workspace = workspace("damaged-tail");
        Path storeRoot = storeRoot("damaged-tail");
        SessionId sessionId = createAndClose(storeRoot, workspace);
        Files.writeString(
                journal(storeRoot, sessionId),
                "{\"schemaMajor\":1",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            SessionOpenResult inspected = store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(
                            SessionRecoveryIssueKind.DAMAGED_TAIL,
                            SessionRecoveryIssueKind.READ_ONLY_INSPECT);
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }
    }

    @Test
    void rejectsInteriorCorruption() throws IOException {
        Path workspace = workspace("interior-corruption");
        Path storeRoot = storeRoot("interior-corruption");
        SessionId sessionId = createAndClose(storeRoot, workspace);
        Files.writeString(
                journal(storeRoot, sessionId),
                "not-json\n{}\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (FileSessionStore store = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MALFORMED_RECORD"));
        }
    }

    @Test
    void rejectsUnknownMajorAndDuplicateSchemaField() throws IOException {
        Path workspace = workspace("schema");
        Path unknownRoot = storeRoot("unknown-major");
        SessionId unknownId = createAndClose(unknownRoot, workspace);
        Path unknownJournal = journal(unknownRoot, unknownId);
        String unknown = Files.readString(unknownJournal, StandardCharsets.UTF_8)
                .replaceFirst("\\\"schemaMajor\\\":1", "\\\"schemaMajor\\\":2");
        Files.writeString(unknownJournal, unknown, StandardCharsets.UTF_8);

        try (FileSessionStore store = store(unknownRoot, workspace, 100)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(unknownId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_VERSION"));
        }

        Path duplicateRoot = storeRoot("duplicate-schema");
        SessionId duplicateId = createAndClose(duplicateRoot, workspace);
        Path duplicateJournal = journal(duplicateRoot, duplicateId);
        String duplicate = Files.readString(duplicateJournal, StandardCharsets.UTF_8)
                .replaceFirst(
                        "\\{\\\"schemaMajor\\\":1,",
                        "{\\\"schemaMajor\\\":1,\\\"schemaMajor\\\":1,");
        Files.writeString(duplicateJournal, duplicate, StandardCharsets.UTF_8);

        try (FileSessionStore store = store(duplicateRoot, workspace, 200)) {
            assertThatThrownBy(() -> store.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(duplicateId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MALFORMED_RECORD"));
        }
    }

    @Test
    void startedWithoutCompletedIsFencedAndNeverCreatesToolResult() throws IOException {
        Path workspace = workspace("incomplete-tool");
        Path storeRoot = storeRoot("incomplete-tool");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-incomplete");
            ToolCall call = new ToolCall("call-write", "write_file", JsonObject.empty());
            store.runStarted(sessionId, runId, new UserMessage("write"));
            store.assistantAppended(sessionId, runId, AssistantMessage.tools(java.util.List.of(call)));
            store.toolStarted(
                    sessionId,
                    runId,
                    1,
                    call.id(),
                    call.name(),
                    ToolEffect.WRITE_WORKSPACE);
        }

        try (FileSessionStore recovery = store(storeRoot, workspace, 100)) {
            SessionOpenResult inspected = recovery.open(
                    new SessionOpenRequest(SessionOpenMode.INSPECT, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(inspected.session().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .isEmpty();
            assertThat(inspected.issues())
                    .extracting(issue -> issue.kind())
                    .contains(
                            SessionRecoveryIssueKind.UNFINISHED_RUN,
                            SessionRecoveryIssueKind.POTENTIAL_SIDE_EFFECT);
            assertThatThrownBy(() -> recovery.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RECOVERY_REQUIRED"));
        }
    }

    @Test
    void recordEncodingFailureReleasesWriterAndAllowsSameIdRetry() throws IOException {
        Path workspace = workspace("encoding-failure-release");
        Path storeRoot = storeRoot("encoding-failure-release");
        SessionSpec oversized = new SessionSpec("x".repeat(1_048_577), Map.of());
        SessionId sessionId = new SessionId("session-test-1");

        try (FileSessionStore failing = store(storeRoot, workspace, 1)) {
            assertThatThrownBy(() -> failing.create(oversized))
                    .isInstanceOfSatisfying(
                            SessionOpenException.class,
                            failure -> assertThat(failure.code()).isEqualTo("LIMIT_EXCEEDED"));
            assertThat(failing.find(sessionId)).isEmpty();

            try (FileSessionStore retry = store(storeRoot, workspace, 1)) {
                assertThat(retry.create(SPEC).id()).isEqualTo(sessionId);
            }
        }
    }

    @Test
    void writerCloseReleasesLeaseForNextStore() throws IOException {
        Path workspace = workspace("release");
        Path storeRoot = storeRoot("release");
        SessionId sessionId;
        try (FileSessionStore first = store(storeRoot, workspace, 1)) {
            sessionId = first.create(SPEC).id();
            first.close(sessionId);
        }

        try (FileSessionStore second = store(storeRoot, workspace, 100)) {
            SessionOpenResult resumed = second.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(resumed.session().id()).isEqualTo(sessionId);
            assertThat(resumed.readOnly()).isFalse();
        }
    }

    @Test
    void persistsEscapeHeavyOneMibPromptWithoutTruncation() throws IOException {
        Path workspace = workspace("escape-heavy");
        Path root = storeRoot("escape-heavy");
        String prompt = "".repeat(1_048_576);
        SessionId id;
        try (FileSessionStore store = store(root, workspace, 1)) {
            id = store.create(SPEC).id();
            store.runStarted(id, new RunId("run-large"), new UserMessage(prompt));
            store.runCompleted(id, new RunId("run-large"), StopReason.COMPLETED);
            store.close(id);
        }
        assertThat(Files.size(journal(root, id))).isGreaterThan(6L * 1_048_576L);
        try (FileSessionStore resumed = store(root, workspace, 10)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(id)), SPEC);
            assertThat(((UserMessage) result.session().messages().getLast()).content()).isEqualTo(prompt);
        }
    }

    @Test
    void persistsAndReplaysUserFileAttachmentsAcrossResumeAndFork() throws IOException {
        Path workspace = workspace("attachments");
        Path storeRoot = storeRoot("attachments");
        UserMessage withAttachment = new UserMessage(
                "explain @src/App.java",
                java.util.List.of(new io.github.liumaishenjian.ccjava.domain.UserFileAttachment(
                        "src/App.java",
                        "line one\nline two",
                        "a".repeat(64),
                        3,
                        4,
                        true)));
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-attach");
            store.runStarted(sessionId, runId, withAttachment);
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(result.issues()).isEmpty();
            assertThat(result.session().messages()).containsExactly(withAttachment);
            resumed.close(sessionId);

            SessionOpenResult forked = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.FORK, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(forked.session().messages()).containsExactly(withAttachment);
            resumed.close(forked.session().id());
        }
    }

    @Test
    void replaysLegacyRunStartedRecordsWithoutAttachmentsField() throws IOException {
        Path workspace = workspace("legacy-attachments");
        Path storeRoot = storeRoot("legacy-attachments");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-legacy");
            store.runStarted(sessionId, runId, new UserMessage("legacy prompt"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        // 模拟本切片之前写出的记录：run.started 完全没有 attachments 字段。
        Path journal = journal(storeRoot, sessionId);
        String stripped = Files.readString(journal, StandardCharsets.UTF_8)
                .replace(",\"attachments\":[]", "");
        assertThat(stripped).doesNotContain("attachments");
        Files.writeString(journal, stripped, StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            SessionOpenResult result = resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC);
            assertThat(result.issues()).isEmpty();
            assertThat(result.session().messages())
                    .containsExactly(new UserMessage("legacy prompt"));
            assertThat(((UserMessage) result.session().messages().getFirst()).attachments()).isEmpty();
            resumed.close(sessionId);
        }
    }

    @Test
    void rejectsRunStartedRecordWithOversizedAttachmentList() throws IOException {
        Path workspace = workspace("attachment-limit");
        Path storeRoot = storeRoot("attachment-limit");
        SessionId sessionId;
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-limit");
            store.runStarted(sessionId, runId, new UserMessage("prompt"));
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        Path journal = journal(storeRoot, sessionId);
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < 9; index++) {
            if (index > 0) {
                items.append(',');
            }
            items.append("{\"protocolPath\":\"src/F").append(index)
                    .append(".java\",\"textSnapshot\":\"x\",\"sha256Digest\":\"")
                    .append("b".repeat(64))
                    .append("\",\"startLine\":1,\"endLine\":1,\"truncated\":false}");
        }
        Files.writeString(
                journal,
                Files.readString(journal, StandardCharsets.UTF_8)
                        .replace("\"attachments\":[]", "\"attachments\":[" + items + "]"),
                StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOf(SessionOpenException.class);
        }
    }

    @Test
    void rejectsUnknownAttachmentSchemaField() throws IOException {
        Path workspace = workspace("attachment-schema");
        Path storeRoot = storeRoot("attachment-schema");
        SessionId sessionId;
        UserMessage message = new UserMessage(
                "prompt",
                java.util.List.of(new io.github.liumaishenjian.ccjava.domain.UserFileAttachment(
                        "src/App.java", "x", "c".repeat(64), 1, 1, false)));
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            sessionId = store.create(SPEC).id();
            RunId runId = new RunId("run-schema");
            store.runStarted(sessionId, runId, message);
            store.runCompleted(sessionId, runId, StopReason.COMPLETED);
            store.close(sessionId);
        }
        Path journal = journal(storeRoot, sessionId);
        Files.writeString(
                journal,
                Files.readString(journal, StandardCharsets.UTF_8)
                        .replace("\"truncated\":false}",
                                "\"truncated\":false,\"unexpected\":true}"),
                StandardCharsets.UTF_8);

        try (FileSessionStore resumed = store(storeRoot, workspace, 100)) {
            assertThatThrownBy(() -> resumed.open(
                    new SessionOpenRequest(SessionOpenMode.RESUME, java.util.Optional.of(sessionId)),
                    SPEC))
                    .isInstanceOf(SessionOpenException.class);
        }
    }

    private SessionId createAndClose(Path storeRoot, Path workspace) {
        try (FileSessionStore store = store(storeRoot, workspace, 1)) {
            SessionId id = store.create(SPEC).id();
            store.close(id);
            return id;
        }
    }

    private Path workspace(String name) throws IOException {
        Path path = temporaryRoot.resolve("workspaces").resolve(name);
        Files.createDirectories(path);
        return path;
    }

    private Path storeRoot(String name) {
        return temporaryRoot.resolve("stores").resolve(name);
    }

    private Path journal(Path storeRoot, SessionId sessionId) {
        return storeRoot.resolve(sessionId.value()).resolve("session.jsonl");
    }

    private FileSessionStore store(Path root, Path workspace, int firstId) {
        return new FileSessionStore(
                root,
                workspace,
                new TestIds(firstId),
                new LifecycleDispatcher(
                        Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                        AgentEventSink.noop()),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC));
    }

    private static final class TestIds implements AgentIdGenerator {
        private final AtomicInteger sessionIds;
        private final AtomicInteger runIds;

        private TestIds(int firstId) {
            sessionIds = new AtomicInteger(firstId);
            runIds = new AtomicInteger(firstId);
        }

        @Override
        public SessionId newSessionId() {
            return new SessionId("session-test-" + sessionIds.getAndIncrement());
        }

        @Override
        public RunId newRunId() {
            return new RunId("run-test-" + runIds.getAndIncrement());
        }
    }
}
