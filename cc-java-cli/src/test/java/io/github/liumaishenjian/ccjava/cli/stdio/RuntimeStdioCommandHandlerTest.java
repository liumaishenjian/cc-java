package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;

class RuntimeStdioCommandHandlerTest {

    @TempDir
    Path temporaryRoot;

    private Path workspace() throws java.io.IOException {
        Path workspace = temporaryRoot.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    private HeadlessRuntimeOptions testOptions() throws java.io.IOException {
        return testOptions(Duration.ofSeconds(3));
    }

    private HeadlessRuntimeOptions testOptions(Duration timeout) throws java.io.IOException {
        return new HeadlessRuntimeOptions(
                workspace(),
                "fake-model",
                timeout,
                PermissionMode.DEFAULT,
                List.of(),
                SessionOpenRequest.create(),
                temporaryRoot.resolve("sessions"));
    }

    @Test
    void terminalContainsProviderUsageAndPrivacySafeTimingProjection()
            throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> new ModelTurn(
                        AssistantMessage.text("COMPLETION_SENTINEL"),
                        new ModelTurnMetadata(
                                ModelFinishReason.STOP,
                                Optional.of(new ModelUsage(12, 3, 15)),
                                Optional.of("MODEL_SENTINEL"))),
                testOptions())) {
            handler.handle(
                    codec.decodeCommand(
                            "{\"version\":0,\"type\":\"initialize\","
                                    + "\"requestId\":\"req-1\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(
                    codec.decodeCommand(
                            ("{\"version\":0,\"type\":\"run.start\","
                                    + "\"requestId\":\"req-2\",\"sessionId\":\"%s\","
                                    + "\"sequence\":2,"
                                    + "\"payload\":{\"prompt\":\"PROMPT_SENTINEL\"}}")
                                    .formatted(sessionId)),
                    emitter);

            CapturedEvent terminal = awaitTerminal(events);
            ObjectNode telemetry = (ObjectNode) terminal.payload().get("telemetry");
            assertThat(telemetry).isNotNull();
            assertThat(telemetry.get("elapsedMillis").longValue()).isGreaterThanOrEqualTo(0);
            assertThat(telemetry.get("usageReportedTurns").intValue()).isOne();
            assertThat(telemetry.get("usageMissingTurns").intValue()).isZero();
            assertThat(telemetry.get("modelTurns").size()).isOne();
            assertThat(telemetry.get("toolCalls").isEmpty()).isTrue();
            assertThat(telemetry.get("totalUsage").get("inputTokens").longValue())
                    .isEqualTo(12);
            assertThat(telemetry.get("totalUsage").get("outputTokens").longValue())
                    .isEqualTo(3);
            assertThat(telemetry.get("totalUsage").get("totalTokens").longValue())
                    .isEqualTo(15);
            assertThat(telemetry.toString())
                    .doesNotContain(
                            "PROMPT_SENTINEL",
                            "COMPLETION_SENTINEL",
                            "MODEL_SENTINEL",
                            "finalText",
                            "apiKey",
                            "baseUrl");
            assertThat(terminal.payload().get("finalText").stringValue())
                    .isEqualTo("COMPLETION_SENTINEL");
        }
    }

    @Test
    void terminalProjectsOnlyWhitelistedModelFailureFields() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        ModelFailureSummary summary = new ModelFailureSummary(
                ModelFailureCategory.PROVIDER_UNAVAILABLE,
                Optional.of(ModelHttpStatusClass.SERVER_ERROR),
                1,
                false);

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.RETRYABLE,
                    "SECRET_PROVIDER_RESPONSE https://secret.invalid sk-secret",
                    summary);
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"PROMPT_SECRET\"}}").formatted(sessionId)), emitter);

            CapturedEvent terminal = awaitAnyTerminal(events);
            assertThat(terminal.type()).isEqualTo("run.failed");
            assertThat(terminal.payload().toString())
                    .contains(
                            "\"category\":\"provider_unavailable\"",
                            "\"statusClass\":\"5xx\"",
                            "\"attempts\":1",
                            "\"receivedOutput\":false")
                    .doesNotContain(
                            "SECRET_PROVIDER_RESPONSE",
                            "secret.invalid",
                            "sk-secret",
                            "PROMPT_SECRET");
        }
    }

    @Test
    void projectsToolLifecycleWithoutArgumentsContentOrAbsolutePaths() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (turns.incrementAndGet() == 1) {
                return new ModelTurn(
                        AssistantMessage.tools(List.of(new ToolCall(
                                "call-1", "read_file",
                                new JsonObject(java.util.Map.of("path", "MISSING_SECRET_PATH"))))),
                        ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"PROMPT_SECRET\"}}").formatted(sessionId)), emitter);
            awaitTerminal(events);
        }

        CapturedEvent started = events.stream()
                .filter(event -> event.type().equals("tool.started"))
                .findFirst().orElseThrow();
        CapturedEvent failed = events.stream()
                .filter(event -> event.type().equals("tool.failed"))
                .findFirst().orElseThrow();
        assertThat(started.payload().toString())
                .contains("read_file", "ordinal")
                .doesNotContain("MISSING_SECRET_PATH", "PROMPT_SECRET", "arguments", "content");
        assertThat(failed.payload().toString())
                .contains(
                        "sensitive_path",
                        "returnedCharacters",
                        "\"returnedItems\":0",
                        "\"truncationReason\":\"none\"")
                .doesNotContain("MISSING_SECRET_PATH", "PROMPT_SECRET", "arguments", "content");
    }

    @Test
    void extractsOnlyFixedSearchModeForPresentation() {
        ToolCall files = new ToolCall(
                "call-files",
                "search_text",
                new JsonObject(java.util.Map.of(
                        "query", "PRIVATE_QUERY",
                        "path", "PRIVATE_PATH",
                        "mode", "files")));
        ToolCall defaults = new ToolCall(
                "call-content",
                "search_text",
                new JsonObject(java.util.Map.of("query", "PRIVATE_QUERY")));
        ToolCall invalid = new ToolCall(
                "call-invalid",
                "search_text",
                new JsonObject(java.util.Map.of("query", "PRIVATE_QUERY", "mode", "raw")));

        assertThat(RuntimeStdioCommandHandler.safeToolMode(files)).contains("files");
        assertThat(RuntimeStdioCommandHandler.safeToolMode(defaults)).contains("content");
        assertThat(RuntimeStdioCommandHandler.safeToolMode(invalid)).isEmpty();
        assertThat(RuntimeStdioCommandHandler.safeToolMode(new ToolCall(
                "call-read",
                "read_file",
                new JsonObject(java.util.Map.of("path", "PRIVATE_PATH"))))).isEmpty();
    }

    @Test
    void approvalEventShowsSafePatchSummaryAndMatchingAllowWritesFile()
            throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> turns.incrementAndGet() == 1
                        ? new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-patch",
                                        "apply_patch",
                                        new JsonObject(java.util.Map.of(
                                                "path", "sample.txt",
                                                "oldText", "old",
                                                "newText", "new"))))),
                                ModelTurnMetadata.unknown())
                        : ModelTurn.text("done"),
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch\"}}").formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().toString())
                    .contains(
                            "\"target\":\"sample.txt\"",
                            "\"operation\":\"modify\"",
                            "\"removedLines\":1",
                            "\"addedLines\":1")
                    .doesNotContain("\"oldText\"", "\"newText\"", "\"arguments\"");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            awaitTerminal(events);
        }

        assertThat(Files.readString(file)).isEqualTo("new\n");
    }

    @Test
    void allowSessionSkipsSecondApprovalForSameScope() throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> switch (turns.incrementAndGet()) {
                    case 1 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-patch-1",
                                    "apply_patch",
                                    new JsonObject(java.util.Map.of(
                                            "path", "sample.txt",
                                            "oldText", "old",
                                            "newText", "middle"))))),
                            ModelTurnMetadata.unknown());
                    case 2 -> new ModelTurn(
                            AssistantMessage.tools(List.of(new ToolCall(
                                    "call-patch-2",
                                    "apply_patch",
                                    new JsonObject(java.util.Map.of(
                                            "path", "sample.txt",
                                            "oldText", "middle",
                                            "newText", "new"))))),
                            ModelTurnMetadata.unknown());
                    default -> ModelTurn.text("done");
                },
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch twice\"}}")
                    .formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().get("sessionScope").booleanValue()).isTrue();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_session\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            awaitTerminal(events);
        }

        assertThat(Files.readString(file)).isEqualTo("new\n");
        assertThat(events).filteredOn(event -> event.type().equals("approval.requested"))
                .hasSize(1);
    }

    @Test
    void exposesCheckpointListDiffAndExplicitUndoThroughStdio() throws Exception {
        Path file = Files.writeString(workspace().resolve("sample.txt"), "old\n");
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> turns.incrementAndGet() == 1
                        ? new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-patch",
                                        "apply_patch",
                                        new JsonObject(java.util.Map.of(
                                                "path", "sample.txt",
                                                "oldText", "old",
                                                "newText", "new"))))),
                                ModelTurnMetadata.unknown())
                        : ModelTurn.text("done"),
                testOptions())) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"patch\"}}").formatted(sessionId)), emitter);
            CapturedEvent approval = awaitEvent(events, "approval.requested");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}").formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);
            awaitTerminal(events);

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.list\","
                    + "\"requestId\":\"list\",\"sessionId\":\"%s\",\"sequence\":4,"
                    + "\"payload\":{}}").formatted(sessionId)), emitter);
            CapturedEvent listed = awaitEvent(events, "checkpoint.listed");
            String checkpointId = listed.payload()
                    .get("checkpoints").get(0).get("checkpointId").stringValue();
            assertThat(listed.payload().toString())
                    .contains("completed_present", "\"undoable\":true", "sample.txt")
                    .doesNotContain(workspace().toString(), temporaryRoot.resolve("sessions").toString());

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.diff\","
                    + "\"requestId\":\"diff\",\"sessionId\":\"%s\",\"sequence\":5,"
                    + "\"payload\":{\"checkpointId\":\"%s\"}}").formatted(
                            sessionId, checkpointId)), emitter);
            CapturedEvent diffed = awaitEvent(events, "checkpoint.diffed");
            assertThat(diffed.payload().toString())
                    .contains("changed", "checkpoint/sample.txt", "workspace/sample.txt");

            assertThatThrownBy(() -> handler.handle(codec.decodeCommand((
                    "{\"version\":0,\"type\":\"checkpoint.undo\","
                            + "\"requestId\":\"undo-denied\",\"sessionId\":\"%s\",\"sequence\":6,"
                            + "\"payload\":{\"checkpointId\":\"%s\",\"confirmed\":false}}")
                            .formatted(sessionId, checkpointId)), emitter))
                    .isInstanceOf(io.github.liumaishenjian.ccjava.cli.session.SessionOpenException.class);
            assertThat(Files.readString(file)).isEqualTo("new\n");

            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"checkpoint.undo\","
                    + "\"requestId\":\"undo\",\"sessionId\":\"%s\",\"sequence\":7,"
                    + "\"payload\":{\"checkpointId\":\"%s\",\"confirmed\":true}}")
                    .formatted(sessionId, checkpointId)), emitter);
            CapturedEvent undone = awaitEvent(events, "checkpoint.undone");
            assertThat(undone.payload().toString()).contains("restored", "sample.txt");
            assertThat(Files.readString(file)).isEqualTo("old\n");
        }
    }

    @Test
    void commandApprovalShowsExactExecutionAndStreamsOutput() throws Exception {
        String command = CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Write-Output 'command-stream'; Set-Content -Path command.txt -Value ok"
                : "printf 'command-stream\\n'; printf 'ok\\n' > command.txt";
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminalReceived = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
            if (type.equals("run.completed") || type.equals("run.failed") || type.equals("run.cancelled")) {
                terminalReceived.countDown();
            }
        };
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                request -> turns.incrementAndGet() == 1
                        ? new ModelTurn(
                                AssistantMessage.tools(List.of(new ToolCall(
                                        "call-command",
                                        "run_command",
                                        new JsonObject(java.util.Map.of(
                                                "command", command,
                                                "timeoutSeconds", 5))))),
                                ModelTurnMetadata.unknown())
                        : ModelTurn.text("done"),
                testOptions(Duration.ofSeconds(10)))) {
            handler.handle(codec.decodeCommand(
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.start\","
                    + "\"requestId\":\"run\",\"sessionId\":\"%s\",\"sequence\":2,"
                    + "\"payload\":{\"prompt\":\"run verification\"}}")
                    .formatted(sessionId)), emitter);

            CapturedEvent approval = awaitEvent(events, "approval.requested");
            assertThat(approval.payload().get("command").stringValue()).isEqualTo(command);
            assertThat(approval.payload().toString())
                    .contains("\"operation\":\"execute\"", "\"workingDirectory\":\".\"")
                    .contains("\"shell\":\"" + CommandShell.current().id() + "\"");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"approval.resolve\","
                    + "\"requestId\":\"approve\",\"sessionId\":\"%s\",\"runId\":\"%s\","
                    + "\"sequence\":3,\"payload\":{\"approvalId\":\"%s\","
                    + "\"decision\":\"allow_once\"}}")
                    .formatted(
                            sessionId,
                            approval.runId().orElseThrow(),
                            approval.payload().get("approvalId").stringValue())), emitter);

            assertThat(terminalReceived.await(15, TimeUnit.SECONDS)).isTrue();
            CapturedEvent output = events.stream()
                    .filter(event -> event.type().equals("tool.output"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "命令完成前未收到 stdout 事件: " + eventDiagnostics(events)));
            assertThat(output.payload().toString())
                    .contains("\"stream\":\"stdout\"", "command-stream")
                    .doesNotContain(workspace().toString());
            awaitTerminal(events);
        }

        assertThat(Files.readString(workspace().resolve("command.txt"))).contains("ok");
    }

    @Test
    void queuesSteeringUntilTheCurrentRunHasReachedItsTerminalBoundary() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
                return ModelTurn.text("first");
            }
            return ModelTurn.text("second");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "steering prompt")), emitter);

            assertThat(events).filteredOn(event -> event.type().equals("steering.queued")).hasSize(1);
            assertThat(events).filteredOn(event -> event.type().equals("run.started")).hasSize(1);
            assertThat(events.toString()).doesNotContain("steering prompt");
            releaseFirst.countDown();
            awaitTerminalCount(events, 2);
        }
        List<CapturedEvent> terminals = events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal).toList();
        assertThat(terminals).hasSize(2);
        assertThat(terminals.get(0).payload().get("finalText").stringValue()).isEqualTo("first");
        assertThat(terminals.get(1).payload().get("finalText").stringValue()).isEqualTo("second");
        assertThat(calls).hasValue(2);
    }

    @Test
    void clearDiscardsQueuedSteeringWithoutPersistingItsText() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("first");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "UNSENT_STEERING_SECRET")), emitter);
            try (var paths = Files.walk(temporaryRoot.resolve("sessions"))) {
                assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .map(path -> {
                            try {
                                return Files.readString(path);
                            } catch (java.io.IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }).toList().toString()).doesNotContain("UNSENT_STEERING_SECRET");
            }
            handler.handle(codec.decodeCommand(sessionCommand("clear", sessionId, 4, "clear-steering", "clear", "{}")), emitter);
            releaseFirst.countDown();
            awaitTerminal(events);
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().toString()).contains("\"reason\":\"clear\"");
        assertThat(events.toString()).doesNotContain("UNSENT_STEERING_SECRET");
    }

    @Test
    void cancellationDiscardsQueuedSteering() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("unexpected");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first-request", sessionId, 2, "first prompt")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("steering-request", sessionId, 3, "CANCELLED_STEERING")), emitter);
            CapturedEvent started = awaitEvent(events, "run.started");
            handler.handle(codec.decodeCommand(("{\"version\":0,\"type\":\"run.cancel\",\"requestId\":\"cancel\","
                    + "\"sessionId\":\"%s\",\"runId\":\"%s\",\"sequence\":4,\"payload\":{}}")
                    .formatted(sessionId, started.runId().orElseThrow())), emitter);
            releaseFirst.countDown();
            awaitAnyTerminal(events);
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().toString()).contains("\"reason\":\"cancelled\"");
        assertThat(events.toString()).doesNotContain("CANCELLED_STEERING");
    }

    @Test
    void sessionCommandResumeSwitchesToCleanCandidateWithOnlySafeIdentifiers() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        io.github.liumaishenjian.ccjava.domain.SessionId candidateId;
        try (io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession candidate =
                     new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                             ignored -> ModelTurn.text("candidate"), io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                             testOptions())) {
            candidateId = candidate.open();
            candidate.run("candidate history");
        }
        String previousId;
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            previousId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"resume\",\"sessionId\":\"%s\",\"sequence\":2,\"payload\":{\"protocolVersion\":0,\"commandId\":\"resume-command\",\"intent\":\"resume\",\"arguments\":{\"sessionId\":\"%s\"}}}";
            handler.handle(codec.decodeCommand(request.formatted(previousId, candidateId.value())), emitter);
        }
        CapturedEvent result = events.stream().filter(event -> event.type().equals("session.command.result"))
                .findFirst().orElseThrow();
        assertThat(result.sessionId()).contains(candidateId.value());
        assertThat(result.payload().toString()).contains("succeeded", "ok", "previousSessionId", "resumedSessionId",
                        candidateId.value(), previousId)
                .doesNotContain(temporaryRoot.toString(), "candidate history", "session.jsonl");
    }

    @Test
    void sessionCommandEmitsOnePrivacySafeTerminalForDuplicateCommandId() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"command\",\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"same-command\",\"intent\":\"doctor\",\"arguments\":{}}}";
            handler.handle(codec.decodeCommand(request.formatted(sessionId, 2)), emitter);
            handler.handle(codec.decodeCommand(request.formatted(sessionId, 3)), emitter);
        }
        assertThat(events).filteredOn(event -> event.type().equals("session.command.result")).hasSize(1);
        CapturedEvent result = events.stream().filter(event -> event.type().equals("session.command.result")).findFirst().orElseThrow();
        assertThat(result.payload().toString()).contains("same-command", "doctor", "succeeded", "ok")
                .doesNotContain("apiKey", "baseUrl", "prompt", "absolute");
    }

    @Test
    void sessionCommandEmitsOneBudgetTerminalThenShutsDownWithoutTrackingUnlimitedIds() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            String request = "{\"version\":0,\"type\":\"session.command\",\"requestId\":\"command-%d\",\"sessionId\":\"%s\",\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"command-%d\",\"intent\":\"doctor\",\"arguments\":{}}}";
            for (int index = 1; index <= 256; index++) {
                assertThat(handler.handle(codec.decodeCommand(request.formatted(index, sessionId, index + 1, index)), emitter))
                        .isEqualTo(StdioProtocol.Disposition.CONTINUE);
            }
            assertThat(handler.handle(codec.decodeCommand(request.formatted(257, sessionId, 258, 257)), emitter))
                    .isEqualTo(StdioProtocol.Disposition.SHUTDOWN);
        }
        var results = events.stream().filter(event -> event.type().equals("session.command.result")).toList();
        assertThat(results).hasSize(257);
        assertThat(results.getLast().payload().toString()).contains("request_budget_exhausted", "command-257");
    }

    @Test
    void sessionCommandRejectsSessionMismatchAndActiveRunWithoutMutation() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> ModelTurn.text("done"), testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"session.command\",\"requestId\":\"bad\",\"sessionId\":\"other\",\"sequence\":2,\"payload\":{\"protocolVersion\":0,\"commandId\":\"bad-command\",\"intent\":\"context\",\"arguments\":{}}}"), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        }
        assertThat(events).filteredOn(event -> event.type().equals("session.command.result")).isEmpty();
    }

    @Test
    void consumesMultipleQueuedSteeringInStrictFifoOrder() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
            }
            return ModelTurn.text("done-" + calls.get());
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("second", sessionId, 3, "second")), emitter);
            handler.handle(codec.decodeCommand(runStart("third", sessionId, 4, "third")), emitter);
            releaseFirst.countDown();
            awaitTerminalCount(events, 3);
        }
        assertThat(events.stream().filter(event -> event.type().equals("run.started"))
                .map(CapturedEvent::runId).toList()).hasSize(3);
        assertThat(events.stream().filter(event -> isTerminal(event))
                .map(event -> event.payload().get("finalText").stringValue()).toList())
                .containsExactly("done-1", "done-2", "done-3");
        assertThat(events.stream().filter(event -> event.type().equals("steering.queued"))
                .map(event -> event.payload().get("queueDepth").intValue()).toList())
                .containsExactly(1, 2);
    }

    @Test
    void rejectsTheOneHundredAndFirstQueuedSteeringWithoutChangingTheQueue() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            for (int index = 1; index <= RuntimeStdioCommandHandler.MAX_STEERING_MESSAGES; index++) {
                handler.handle(codec.decodeCommand(runStart("queued-" + index, sessionId, index + 2, "queued")), emitter);
            }
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("overflow", sessionId, 103, "overflow")), emitter))
                    .isInstanceOf(StdioProtocolException.class)
                    .hasMessageContaining("steering 队列已满");
            handler.handle(codec.decodeCommand(sessionCommand("clear", sessionId, 104, "clear", "clear", "{}")), emitter);
            releaseFirst.countDown();
            awaitTerminal(events);
        }
        assertThat(events).filteredOn(event -> event.type().equals("steering.queued")).hasSize(100);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(100);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded"))
                .allSatisfy(event -> assertThat(event.payload().get("reason").stringValue()).isEqualTo("clear"));
    }

    @Test
    void shutdownAndCloseDiscardQueuedSteeringExactlyOnce() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            assertThat(handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"shutdown\",\"requestId\":\"stop\",\"sequence\":4,\"payload\":{}}"), emitter))
                    .isEqualTo(StdioProtocol.Disposition.SHUTDOWN);
            releaseFirst.countDown();
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().get("reason").stringValue()).isEqualTo("shutdown");
    }

    @Test
    void closeDiscardsQueuedSteeringExactlyOnce() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions());
        try {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            releaseFirst.countDown();
            handler.close();
        } finally {
            releaseFirst.countDown();
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("steering.discarded")).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("steering.discarded")).findFirst().orElseThrow()
                .payload().get("reason").stringValue()).isEqualTo("shutdown");
    }

    @Test
    void discardedEmissionFailureStillCancelsRunClearsQueueAndClosesResources() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("steering.discarded")) {
                throw new IllegalStateException("discard transport closed");
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions());
        try {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "queued")), emitter);
            assertThatThrownBy(handler::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("discard transport closed");
            releaseFirst.countDown();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("later", sessionId, 4, "later")), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        } finally {
            releaseFirst.countDown();
            try {
                handler.close();
            } catch (RuntimeException ignored) {
                // First close has already asserted the transport failure; cleanup remains idempotent.
            }
        }
        assertThat(calls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type().equals("run.started")).hasSize(1);
        assertThat(events.toString()).doesNotContain("later");
    }

    @Test
    void eventEmitterFailureDiscardsUnsentSteeringAndPreventsLaterRuns() throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) -> {
            if (type.equals("steering.queued")) {
                throw new IllegalStateException("transport closed");
            }
            events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
        };
        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(request -> {
            calls.incrementAndGet();
            firstEntered.countDown();
            awaitLatch(releaseFirst);
            return ModelTurn.text("done");
        }, testOptions())) {
            handler.handle(codec.decodeCommand("{\"version\":0,\"type\":\"initialize\",\"requestId\":\"init\",\"sequence\":1,\"payload\":{}}"), emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(codec.decodeCommand(runStart("first", sessionId, 2, "first")), emitter);
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("queued", sessionId, 3, "UNSENT")), emitter))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transport closed");
            releaseFirst.countDown();
            assertThatThrownBy(() -> handler.handle(codec.decodeCommand(runStart("later", sessionId, 4, "later")), emitter))
                    .isInstanceOf(StdioProtocolException.class);
        }
        assertThat(calls).hasValue(1);
        assertThat(events.toString()).doesNotContain("UNSENT");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Fake Model 等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String runStart(String requestId, String sessionId, long sequence, String prompt) {
        return ("{\"version\":0,\"type\":\"run.start\",\"requestId\":\"%s\",\"sessionId\":\"%s\","
                + "\"sequence\":%d,\"payload\":{\"prompt\":\"%s\"}}")
                .formatted(requestId, sessionId, sequence, prompt);
    }

    private static String sessionCommand(
            String requestId, String sessionId, long sequence, String commandId, String intent, String arguments) {
        return ("{\"version\":0,\"type\":\"session.command\",\"requestId\":\"%s\",\"sessionId\":\"%s\","
                + "\"sequence\":%d,\"payload\":{\"protocolVersion\":0,\"commandId\":\"%s\","
                + "\"intent\":\"%s\",\"arguments\":%s}}")
                .formatted(requestId, sessionId, sequence, commandId, intent, arguments);
    }

    private static boolean isTerminal(CapturedEvent event) {
        return event.type().equals("run.completed")
                || event.type().equals("run.failed")
                || event.type().equals("run.cancelled");
    }

    private void awaitTerminalCount(List<CapturedEvent> events, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (events.stream().filter(RuntimeStdioCommandHandlerTest::isTerminal).count() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到预期数量的 stdio Run 终态事件");
    }

    private static String eventDiagnostics(List<CapturedEvent> events) {
        return events.stream()
                .map(event -> event.type() + "[status=" + stringField(event, "status")
                        + ", errorCode=" + stringField(event, "errorCode")
                        + ", stopReason=" + stringField(event, "stopReason")
                        + ", stream=" + stringField(event, "stream")
                        + ", toolName=" + stringField(event, "toolName") + "]")
                .toList()
                .toString();
    }

    private static String stringField(CapturedEvent event, String field) {
        var value = event.payload().get(field);
        return value != null && value.isString() ? value.stringValue() : "-";
    }

    private CapturedEvent awaitTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        return awaitEvent(events, "run.completed");
    }

    private CapturedEvent awaitAnyTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals("run.completed")
                            || event.type().equals("run.failed")
                            || event.type().equals("run.cancelled"))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到 stdio Run 终态事件");
    }

    private CapturedEvent awaitEvent(List<CapturedEvent> events, String type)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> matched = events.stream()
                    .filter(event -> event.type().equals(type))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到 stdio 事件: " + type);
    }

    private record CapturedEvent(
            String type,
            Optional<String> sessionId,
            Optional<String> runId,
            ObjectNode payload) {
    }
}
