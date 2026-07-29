package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StdioProtocolServerTest {

    @Test
    void runsInitializeStartAndShutdownWithExactlyOneRunTerminal()
            throws Exception {
        String input = """
                {"version":0,"type":"initialize","requestId":"req-1","sequence":1,"payload":{}}
                {"version":0,"type":"run.start","requestId":"req-2","sessionId":"session-1","sequence":2,"payload":{"prompt":"hello"}}
                {"version":0,"type":"shutdown","requestId":"req-3","sessionId":"session-1","sequence":3,"payload":{}}
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioProtocolServer server = new StdioProtocolServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output,
                new SynchronousHandler());

        StdioProtocolServer.ExitReason reason = server.run();
        List<JsonNode> events = parseLines(output);

        assertThat(reason).isEqualTo(StdioProtocolServer.ExitReason.SHUTDOWN);
        assertThat(events)
                .extracting(event -> event.get("sequence").longValue())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(events)
                .extracting(event -> event.get("type").stringValue())
                .containsExactly(
                        "initialized",
                        "run.started",
                        "model.text.delta",
                        "run.completed");
        assertThat(events.stream()
                .filter(event -> event.get("type").stringValue().startsWith("run."))
                .filter(event -> !event.get("type").stringValue().equals("run.started")))
                .hasSize(1);
    }

    @Test
    void invalidSequenceDoesNotAdvanceExpectedCommandSequence()
            throws Exception {
        String input = """
                {"version":0,"type":"initialize","requestId":"bad","sequence":2,"payload":{}}
                {"version":0,"type":"initialize","requestId":"req-1","sequence":1,"payload":{}}
                {"version":0,"type":"shutdown","requestId":"req-2","sessionId":"session-1","sequence":2,"payload":{}}
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioProtocolServer server = new StdioProtocolServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output,
                new SynchronousHandler());

        server.run();
        List<JsonNode> events = parseLines(output);

        assertThat(events)
                .extracting(event -> event.get("type").stringValue())
                .containsExactly("protocol.error", "initialized");
        assertThat(events.getFirst().get("payload").get("code").stringValue())
                .isEqualTo("INVALID_SEQUENCE");
        assertThat(events.get(1).get("sequence").longValue()).isEqualTo(2);
    }

    private List<JsonNode> parseLines(ByteArrayOutputStream output) {
        JsonMapper mapper = JsonMapper.builder().build();
        return output.toString(StandardCharsets.UTF_8)
                .lines()
                .map(line -> {
                    try {
                        return mapper.readTree(line);
                    } catch (Exception exception) {
                        throw new AssertionError("stdout 包含非 JSON 行", exception);
                    }
                })
                .toList();
    }

    private static final class SynchronousHandler
            implements StdioProtocol.CommandHandler {

        private final StdioProtocolCodec codec = new StdioProtocolCodec();
        private boolean initialized;

        @Override
        public StdioProtocol.Disposition handle(
                StdioProtocol.Command command,
                StdioProtocol.EventEmitter events) throws StdioProtocolException {
            return switch (command.type()) {
                case "initialize" -> initialize(command, events);
                case "run.start" -> start(command, events);
                case "shutdown" -> StdioProtocol.Disposition.SHUTDOWN;
                default -> throw new StdioProtocolException(
                        "INVALID_STATE",
                        command.requestId(),
                        "测试 Handler 不接受该命令");
            };
        }

        private StdioProtocol.Disposition initialize(
                StdioProtocol.Command command,
                StdioProtocol.EventEmitter events) {
            initialized = true;
            events.emit(
                    "initialized",
                    command.requestId(),
                    Optional.of("session-1"),
                    Optional.empty(),
                    codec.objectNode());
            return StdioProtocol.Disposition.CONTINUE;
        }

        private StdioProtocol.Disposition start(
                StdioProtocol.Command command,
                StdioProtocol.EventEmitter events) throws StdioProtocolException {
            if (!initialized) {
                throw new StdioProtocolException(
                        "INVALID_STATE",
                        command.requestId(),
                        "尚未初始化");
            }
            Optional<String> sessionId = Optional.of("session-1");
            Optional<String> runId = Optional.of("run-1");
            events.emit(
                    "run.started",
                    command.requestId(),
                    sessionId,
                    runId,
                    codec.objectNode());
            ObjectNode delta = codec.objectNode();
            delta.put("text", "alpha");
            events.emit(
                    "model.text.delta",
                    command.requestId(),
                    sessionId,
                    runId,
                    delta);
            ObjectNode completed = codec.objectNode();
            completed.put("stopReason", "completed");
            events.emit(
                    "run.completed",
                    command.requestId(),
                    sessionId,
                    runId,
                    completed);
            return StdioProtocol.Disposition.CONTINUE;
        }

        @Override
        public void close() {
        }
    }
}
