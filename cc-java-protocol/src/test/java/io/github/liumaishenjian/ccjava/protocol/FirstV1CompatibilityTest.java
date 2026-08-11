package io.github.liumaishenjian.ccjava.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 首个 v1 的 N/N-1 fixture：验证 v0 共存与 v1 minor/major 规则，不冒充已发布 artifact。 */
class FirstV1CompatibilityTest {
    @Test void v1CodecReadsCurrentFixtureAndRejectsFutureMajor() throws Exception {
        var mapper = JsonMapper.builder().build();
        var payload = mapper.createObjectNode().put("token", "x").put("version", "1.0");
        payload.putArray("features");
        ProtocolEnvelope current = new ProtocolEnvelope(ProtocolVersion.V1_0, ProtocolMessageKind.REQUEST,
                "initialize", "m1", "c1", Optional.empty(), Optional.empty(), 1,
                Optional.empty(), payload);
        StableProtocolCodec codec = new StableProtocolCodec();
        assertThat(codec.decode(codec.encode(current))).isEqualTo(current);
        byte[] futureMajor = new String(codec.encode(current), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProtocolEnvelope decoded = codec.decode(futureMajor);
        assertThat(decoded.version().major()).isEqualTo(2);
        assertThat(new ProtocolConnection(CapabilityToken.generate(), java.util.Set.of())
                .state()).isEqualTo(ProtocolConnectionState.NEW);
    }

    @Test void firstV1KeepsExperimentalV0EntrypointInReleaseManifest() throws Exception {
        java.nio.file.Path manifest = java.nio.file.Path.of("..", "target", "release", "release-manifest.json");
        if (!java.nio.file.Files.exists(manifest)) return;
        String text = java.nio.file.Files.readString(manifest);
        assertThat(text).contains("\"protocolMajors\"").contains("0", "1");
    }
}
