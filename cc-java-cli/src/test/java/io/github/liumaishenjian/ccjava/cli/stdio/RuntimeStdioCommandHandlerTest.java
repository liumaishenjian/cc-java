package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import io.github.liumaishenjian.ccjava.tools.local.command.CommandShell;

class RuntimeStdioCommandHandlerTest {

    @TempDir
    Path workspace;

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
                                Optional.of("MODEL_SENTINEL"))))) {
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
        })) {
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
        Path file = Files.writeString(workspace.resolve("sample.txt"), "old\n");
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
                new HeadlessRuntimeOptions(
                        workspace, "fake-model", Duration.ofSeconds(3)))) {
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
    void commandApprovalShowsExactExecutionAndStreamsOutput() throws Exception {
        String command = CommandShell.current() == CommandShell.WINDOWS_POWERSHELL
                ? "Write-Output 'command-stream'; Set-Content -Path command.txt -Value ok"
                : "printf 'command-stream\\n'; printf 'ok\\n' > command.txt";
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));
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
                new HeadlessRuntimeOptions(
                        workspace, "fake-model", Duration.ofSeconds(10)))) {
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

            CapturedEvent output = awaitEvent(events, "tool.output");
            assertThat(output.payload().toString())
                    .contains("\"stream\":\"stdout\"", "command-stream")
                    .doesNotContain(workspace.toString());
            awaitTerminal(events);
        }

        assertThat(Files.readString(workspace.resolve("command.txt"))).contains("ok");
    }

    private CapturedEvent awaitTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        return awaitEvent(events, "run.completed");
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
