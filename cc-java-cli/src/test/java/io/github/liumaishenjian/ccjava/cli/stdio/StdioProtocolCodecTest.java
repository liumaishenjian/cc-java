package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StdioProtocolCodecTest {

    private final StdioProtocolCodec codec = new StdioProtocolCodec();

    @Test
    void decodesValidCommandAndIgnoresUnknownOptionalFields() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":{},"futureField":{"enabled":true}}
                """);

        assertThat(command.type()).isEqualTo("initialize");
        assertThat(command.requestId()).isEqualTo("req-1");
        assertThat(command.sequence()).isEqualTo(1);
        assertThat(command.sessionId()).isEmpty();
        assertThat(command.payload()).isEmpty();
    }

    @Test
    void decodesApprovalResolveAsAFirstClassCommand() throws Exception {
        StdioProtocol.Command command = codec.decodeCommand("""
                {"version":0,"type":"approval.resolve","requestId":"approve-1",
                 "sessionId":"session-1","runId":"run-1","sequence":3,
                 "payload":{"approvalId":"approval-1","decision":"allow_once"}}
                """);

        assertThat(command.type()).isEqualTo("approval.resolve");
        assertThat(command.sessionId()).contains("session-1");
        assertThat(command.runId()).contains("run-1");
        assertThat(command.payload().get("approvalId").stringValue())
                .isEqualTo("approval-1");
    }

    @Test
    void rejectsMalformedDuplicateAndUnknownProtocolInputs() {
        assertProtocolError("{", "MALFORMED_JSON");
        assertProtocolError(
                """
                {"version":0,"version":0,"type":"initialize",
                 "requestId":"req-1","sequence":1,"payload":{}}
                """,
                "MALFORMED_JSON");
        assertProtocolError(
                """
                {"version":1,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":{}}
                """,
                "UNSUPPORTED_VERSION");
        assertProtocolError(
                """
                {"version":0,"type":"future.command","requestId":"req-1",
                 "sequence":1,"payload":{}}
                """,
                "UNKNOWN_COMMAND");
        assertProtocolError(
                """
                {"version":0,"type":"initialize","requestId":"req-1",
                 "sequence":1,"payload":[]}
                """,
                "INVALID_PAYLOAD");
    }

    @Test
    void encodesEventWithoutNullOptionalIds() throws Exception {
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", 0);
        StdioProtocol.Event event = new StdioProtocol.Event(
                0,
                "initialized",
                "req-1",
                Optional.of("session-1"),
                Optional.empty(),
                1,
                payload);

        String json = codec.encodeEvent(event);
        JsonNode root = JsonMapper.builder().build().readTree(json);

        assertThat(root.get("type").stringValue()).isEqualTo("initialized");
        assertThat(root.get("sessionId").stringValue()).isEqualTo("session-1");
        assertThat(root.has("runId")).isFalse();
        assertThat(root.get("sequence").longValue()).isEqualTo(1);
    }

    private void assertProtocolError(String input, String expectedCode) {
        assertThatThrownBy(() -> codec.decodeCommand(input))
                .isInstanceOf(StdioProtocolException.class)
                .extracting(exception -> ((StdioProtocolException) exception).code())
                .isEqualTo(expectedCode);
    }
}
