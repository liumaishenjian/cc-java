package io.github.liumaishenjian.ccjava.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * stable v1 单行 UTF-8 JSON codec，严格拒绝重复/未知信封字段与超限输入。
 *
 * <p>数字字段必须是可无损表达的 JSON 整数；不接受浮点、字符串或 Jackson 的数值强制转换。</p>
 *
 * @since 0.1.0
 */
public final class StableProtocolCodec {
    /** 单条 NDJSON 消息允许的最大 UTF-8 字节数。 */
    public static final int MAX_LINE_BYTES = 1_048_576;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "messageKind", "messageType", "messageId", "correlationId",
            "sessionId", "runId", "sequence", "idempotencyKey", "payload");
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** 创建使用 strict JSON 信封契约的 stable codec。 */
    public StableProtocolCodec() { }

    /**
     * 解码并严格校验一条 v1 消息。
     *
     * @param utf8 一条完整且不含 framing 换行的 UTF-8 JSON
     * @return 已校验并防御性复制 payload 的信封
     * @throws ProtocolCodecException 大小、编码、JSON 或字段契约非法时
     */
    public ProtocolEnvelope decode(byte[] utf8) throws ProtocolCodecException {
        if (utf8 == null || utf8.length == 0 || utf8.length > MAX_LINE_BYTES) {
            throw new ProtocolCodecException("MESSAGE_SIZE");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
            JsonNode node = mapper.readTree(text);
            if (node == null || !node.isObject()) {
                throw new ProtocolCodecException("ENVELOPE");
            }
            ObjectNode root = (ObjectNode) node;
            for (String name : root.propertyNames()) {
                if (!FIELDS.contains(name)) {
                    throw new ProtocolCodecException("UNKNOWN_FIELD");
                }
            }
            ProtocolVersion version = version(required(root, "schemaVersion"));
            long sequence = positiveLong(root, "sequence");
            return new ProtocolEnvelope(
                    version,
                    ProtocolMessageKind.valueOf(required(root, "messageKind")),
                    required(root, "messageType"),
                    required(root, "messageId"),
                    required(root, "correlationId"),
                    optional(root, "sessionId"),
                    optional(root, "runId"),
                    sequence,
                    optional(root, "idempotencyKey"),
                    object(root, "payload"));
        } catch (ProtocolCodecException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProtocolCodecException("MALFORMED");
        }
    }

    /**
     * 编码一条单行 stable v1 消息。
     *
     * @param envelope 已校验的协议信封
     * @return 不含 framing 换行的 UTF-8 JSON
     */
    public byte[] encode(ProtocolEnvelope envelope) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", envelope.version().major() + "." + envelope.version().minor());
        root.put("messageKind", envelope.kind().name());
        root.put("messageType", envelope.type());
        root.put("messageId", envelope.messageId());
        root.put("correlationId", envelope.correlationId());
        envelope.sessionId().ifPresent(value -> root.put("sessionId", value));
        envelope.runId().ifPresent(value -> root.put("runId", value));
        root.put("sequence", envelope.sequence());
        envelope.idempotencyKey().ifPresent(value -> root.put("idempotencyKey", value));
        root.set("payload", envelope.payload());
        byte[] bytes = mapper.writeValueAsBytes(root);
        if (bytes.length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("编码消息超限");
        }
        return bytes;
    }

    /**
     * 创建与 codec 使用相同 Jackson 版本的空 Payload。
     *
     * @return 可由调用方填充的空 JSON Object
     */
    public ObjectNode objectNode() {
        return mapper.createObjectNode();
    }

    private static ProtocolVersion version(String text) throws ProtocolCodecException {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("0|[1-9][0-9]*")
                || !parts[1].matches("0|[1-9][0-9]*")) {
            throw new ProtocolCodecException("VERSION");
        }
        try {
            return new ProtocolVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (IllegalArgumentException failure) {
            throw new ProtocolCodecException("VERSION");
        }
    }

    private static long positiveLong(ObjectNode root, String name) throws ProtocolCodecException {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProtocolCodecException("FIELD_" + name);
        }
        long parsed = value.longValue();
        if (parsed < 1) {
            throw new ProtocolCodecException("FIELD_" + name);
        }
        return parsed;
    }

    private static String required(ObjectNode root, String name) throws ProtocolCodecException {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > 128
                || value.asText().chars().anyMatch(Character::isISOControl)) {
            throw new ProtocolCodecException("FIELD_" + name);
        }
        return value.asText();
    }

    private static Optional<String> optional(ObjectNode root, String name) throws ProtocolCodecException {
        JsonNode value = root.get(name);
        return value == null ? Optional.empty() : Optional.of(required(root, name));
    }

    private static ObjectNode object(ObjectNode root, String name) throws ProtocolCodecException {
        JsonNode value = root.get(name);
        if (!(value instanceof ObjectNode object)) {
            throw new ProtocolCodecException("FIELD_" + name);
        }
        return object;
    }
}
