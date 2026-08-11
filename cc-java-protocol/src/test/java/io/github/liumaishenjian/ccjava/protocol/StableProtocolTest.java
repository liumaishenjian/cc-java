package io.github.liumaishenjian.ccjava.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StableProtocolTest {

    @Test
    void codecRoundTripsAndPayloadIsImmutable() throws Exception {
        StableProtocolCodec codec = new StableProtocolCodec();
        var payload = codec.objectNode().put("prompt", "untrusted");
        ProtocolEnvelope envelope = request(codec, "m1", "c1", 1, Optional.of("key1"), payload);
        payload.put("prompt", "mutated");
        assertThat(envelope.payload().get("prompt").asText()).isEqualTo("untrusted");
        var exposed = envelope.payload();
        exposed.put("prompt", "also-mutated");
        assertThat(envelope.payload().get("prompt").asText()).isEqualTo("untrusted");
        ProtocolEnvelope decoded = codec.decode(codec.encode(envelope));
        assertThat(decoded.type()).isEqualTo("run.start");
    }

    @Test
    void codecRejectsDuplicateUnknownOversizeAndNonIntegerSequence() {
        StableProtocolCodec codec = new StableProtocolCodec();
        assertRejected(codec, "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}");
        assertRejected(codec, "{\"schemaVersion\":\"1.0\",\"unknown\":1}");
        assertThatThrownBy(() -> codec.decode(new byte[StableProtocolCodec.MAX_LINE_BYTES + 1]))
                .isInstanceOf(ProtocolCodecException.class);
        String base = "{\"schemaVersion\":\"1.0\",\"messageKind\":\"REQUEST\","
                + "\"messageType\":\"run.start\",\"messageId\":\"m\","
                + "\"correlationId\":\"c\",\"sequence\":%s,\"payload\":{}}";
        assertRejected(codec, base.formatted("1.5"));
        assertRejected(codec, base.formatted("\"1\""));
        assertRejected(codec, base.formatted("9223372036854775808"));
        assertRejected(codec, base.formatted("0"));
    }

    @Test
    void initializeSupportsEmptyServerFeaturesAndIsOnce() throws Exception {
        CapabilityToken token = CapabilityToken.generate();
        ProtocolConnection empty = new ProtocolConnection(token, Set.of());
        assertThat(empty.initialize(token.reveal(), ProtocolVersion.V1_0, Set.of())).isEmpty();
        assertThatThrownBy(() -> empty.initialize(token.reveal(), ProtocolVersion.V1_0, Set.of()))
                .isInstanceOf(ProtocolCodecException.class);
        assertThat(token.toString()).doesNotContain(token.reveal());
    }

    @Test
    void acceptedRequestCorrelationIdempotencyAndDrainAreStrict() throws Exception {
        StableProtocolCodec codec = new StableProtocolCodec();
        CapabilityToken token = CapabilityToken.generate();
        ProtocolConnection connection = new ProtocolConnection(token, Set.of(ProtocolFeature.RUN));
        connection.initialize(token.reveal(), ProtocolVersion.V1_0, Set.of(ProtocolFeature.RUN));
        ProtocolEnvelope first = request(codec, "m1", "c1", 1, Optional.of("idem"), codec.objectNode());
        assertThat(connection.accept(first)).isEmpty();
        assertThat(connection.pendingRequests()).isOne();
        ProtocolEnvelope response = response(codec, "r1", "m1", 1);
        connection.recordResponse(first, response);
        assertThat(connection.pendingRequests()).isZero();

        ProtocolEnvelope duplicate = request(codec, "m2", "c1", 2, Optional.of("idem"), codec.objectNode());
        assertThat(connection.accept(duplicate)).contains("r1");
        connection.beginDrain();
        ProtocolEnvelope afterDrain = request(codec, "m3", "c3", 3, Optional.empty(), codec.objectNode());
        assertThatThrownBy(() -> connection.accept(afterDrain)).isInstanceOf(ProtocolCodecException.class);
        connection.close();
        connection.close();
        assertThat(connection.state()).isEqualTo(ProtocolConnectionState.CLOSED);
    }

    @Test
    void idempotencyKeyConflictsOnPayloadOrRequestIdentity() throws Exception {
        StableProtocolCodec codec = new StableProtocolCodec();
        CapabilityToken token = CapabilityToken.generate();
        ProtocolConnection payloadConflict = ready(token);
        var firstPayload = codec.objectNode().put("prompt", "first");
        ProtocolEnvelope first = request(codec, "m1", "c1", 1, Optional.of("idem"), firstPayload);
        payloadConflict.accept(first);
        payloadConflict.recordResponse(first, response(codec, "r1", "m1", 1));
        var changedPayload = codec.objectNode().put("prompt", "second");
        assertThatThrownBy(() -> payloadConflict.accept(
                request(codec, "m2", "c1", 2, Optional.of("idem"), changedPayload)))
                .isInstanceOf(ProtocolCodecException.class)
                .extracting(failure -> ((ProtocolCodecException) failure).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        ProtocolConnection correlationConflict = ready(token);
        ProtocolEnvelope accepted = request(codec, "m1", "c1", 1, Optional.of("idem"), codec.objectNode());
        correlationConflict.accept(accepted);
        correlationConflict.recordResponse(accepted, response(codec, "r1", "m1", 1));
        assertThatThrownBy(() -> correlationConflict.accept(
                request(codec, "m2", "different", 2, Optional.of("idem"), codec.objectNode())))
                .isInstanceOf(ProtocolCodecException.class)
                .extracting(failure -> ((ProtocolCodecException) failure).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void unknownDuplicateAndMismatchedResponseFailClosed() throws Exception {
        StableProtocolCodec codec = new StableProtocolCodec();
        CapabilityToken token = CapabilityToken.generate();
        ProtocolConnection unknown = ready(token);
        ProtocolEnvelope request = request(codec, "m1", "c1", 1, Optional.empty(), codec.objectNode());
        assertThatThrownBy(() -> unknown.recordResponse(request, response(codec, "r1", "m1", 1)))
                .isInstanceOf(ProtocolCodecException.class);

        ProtocolConnection mismatch = ready(token);
        mismatch.accept(request);
        assertThatThrownBy(() -> mismatch.recordResponse(request, response(codec, "r1", "other", 1)))
                .isInstanceOf(ProtocolCodecException.class);

        ProtocolConnection sequence = ready(token);
        ProtocolEnvelope outOfOrder = request(codec, "m2", "c2", 2, Optional.empty(), codec.objectNode());
        assertThatThrownBy(() -> sequence.accept(outOfOrder)).isInstanceOf(ProtocolCodecException.class);
    }

    private static ProtocolConnection ready(CapabilityToken token) throws Exception {
        ProtocolConnection connection = new ProtocolConnection(token, Set.of(ProtocolFeature.RUN));
        connection.initialize(token.reveal(), ProtocolVersion.V1_0, Set.of(ProtocolFeature.RUN));
        return connection;
    }

    private static ProtocolEnvelope request(
            StableProtocolCodec codec,
            String messageId,
            String correlationId,
            long sequence,
            Optional<String> idempotency,
            tools.jackson.databind.node.ObjectNode payload) {
        return new ProtocolEnvelope(
                ProtocolVersion.V1_0, ProtocolMessageKind.REQUEST, "run.start", messageId,
                correlationId, Optional.of("s1"), Optional.empty(), sequence, idempotency, payload);
    }

    private static ProtocolEnvelope response(
            StableProtocolCodec codec, String messageId, String correlationId, long sequence) {
        return new ProtocolEnvelope(
                ProtocolVersion.V1_0, ProtocolMessageKind.RESPONSE, "run.accepted", messageId,
                correlationId, Optional.of("s1"), Optional.empty(), sequence, Optional.empty(),
                codec.objectNode());
    }

    private static void assertRejected(StableProtocolCodec codec, String json) {
        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ProtocolCodecException.class);
    }
}
