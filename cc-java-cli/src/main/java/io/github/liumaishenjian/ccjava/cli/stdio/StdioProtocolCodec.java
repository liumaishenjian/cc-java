package io.github.liumaishenjian.ccjava.cli.stdio;

import java.util.Optional;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 在 Jackson Tree Model 与项目内部 stdio v0 信封之间转换。
 *
 * <p>Codec 在 CLI Adapter 内手工校验必需字段，允许未知可选字段，从而同时满足
 * fail-closed 的主版本/类型约束和后续字段演进。Jackson 类型不会进入 Domain/Core。</p>
 *
 * @since 0.1.0
 */
public final class StdioProtocolCodec {

    /** 文本标识字段的最大 UTF-16 字符数。 */
    public static final int MAX_IDENTIFIER_CHARS = 128;

    private static final Set<String> COMMAND_TYPES = Set.of(
            "initialize",
            "run.start",
            "run.cancel",
            "approval.resolve",
            "checkpoint.list",
            "checkpoint.diff",
            "checkpoint.undo",
            "session.command",
            "shutdown");

    private final ObjectMapper mapper;

    /**
     * 创建启用重复字段检测的 JSON Codec。
     */
    public StdioProtocolCodec() {
        mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * 解析并校验一条 Client 命令。
     *
     * @param line 不包含换行符的 UTF-8 解码文本
     * @return 独立命令对象
     * @throws StdioProtocolException JSON 或信封不合法时
     */
    public StdioProtocol.Command decodeCommand(String line)
            throws StdioProtocolException {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception exception) {
            throw error("MALFORMED_JSON", "消息不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw error("INVALID_ENVELOPE", "消息根节点必须是 JSON Object");
        }

        int version = requiredInt(root, "version");
        if (version != StdioProtocol.VERSION) {
            throw error("UNSUPPORTED_VERSION", "不支持该协议主版本");
        }
        String type = requiredText(root, "type");
        String requestId = requiredText(root, "requestId");
        if (!COMMAND_TYPES.contains(type)) {
            throw new StdioProtocolException(
                    "UNKNOWN_COMMAND",
                    requestId,
                    "不支持该命令类型");
        }
        long sequence = requiredLong(root, "sequence");
        if (sequence < 1) {
            throw new StdioProtocolException(
                    "INVALID_SEQUENCE",
                    requestId,
                    "sequence 必须从 1 开始");
        }
        Optional<String> sessionId = optionalText(root, "sessionId", requestId);
        Optional<String> runId = optionalText(root, "runId", requestId);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new StdioProtocolException(
                    "INVALID_PAYLOAD",
                    requestId,
                    "payload 必须是 JSON Object");
        }
        if ("session.command".equals(type)) {
            validateSessionCommand(root, (ObjectNode) payloadNode, requestId);
        }

        return new StdioProtocol.Command(
                version,
                type,
                requestId,
                sessionId,
                runId,
                sequence,
                (ObjectNode) payloadNode);
    }

    /**
     * 把事件编码成不含换行符的 JSON。
     *
     * @param event 已分配输出序号的事件
     * @return 单行 JSON
     */
    public String encodeEvent(StdioProtocol.Event event) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", event.version());
        root.put("type", event.type());
        root.put("requestId", event.requestId());
        event.sessionId().ifPresent(value -> root.put("sessionId", value));
        event.runId().ifPresent(value -> root.put("runId", value));
        root.put("sequence", event.sequence());
        root.set("payload", event.payload());
        return mapper.writeValueAsString(root);
    }

    /**
     * 创建供 Adapter 组装事件数据的空 Object。
     *
     * @return 新 ObjectNode
     */
    public ObjectNode objectNode() {
        return mapper.createObjectNode();
    }

    /**
     * 创建供 Adapter 组装事件数据的空 Array。
     *
     * @return 新 ArrayNode
     */
    public ArrayNode arrayNode() {
        return mapper.createArrayNode();
    }

    private void validateSessionCommand(JsonNode root, ObjectNode payload, String requestId)
            throws StdioProtocolException {
        Set<String> envelope = Set.of("version", "type", "requestId", "sessionId", "runId", "sequence", "payload");
        if (root.properties().stream().anyMatch(entry -> !envelope.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "session.command 包含未知信封字段");
        }
        if (root.get("sessionId") == null || root.get("runId") != null) {
            throw new StdioProtocolException("INVALID_ENVELOPE", requestId, "session.command 必须携带 Session 且不能携带 Run");
        }
        Set<String> fields = Set.of("protocolVersion", "commandId", "intent", "arguments");
        if (payload.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "session.command payload 包含未知字段");
        }
        JsonNode protocolVersion = payload.get("protocolVersion");
        if (protocolVersion == null || !protocolVersion.isIntegralNumber()
                || !protocolVersion.canConvertToInt()
                || protocolVersion.intValue() != StdioProtocol.VERSION) {
            throw new StdioProtocolException("UNSUPPORTED_VERSION", requestId, "session.command protocolVersion 不受支持");
        }
        String commandId = requiredPayloadText(payload, "commandId", requestId);
        if (invalidIdentifier(commandId)) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "commandId 非法");
        }
        String intent = requiredPayloadText(payload, "intent", requestId);
        JsonNode arguments = payload.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, "arguments 必须是 JSON Object");
        }
        validateSessionCommandArguments(intent, (ObjectNode) arguments, requestId);
    }

    private String requiredPayloadText(ObjectNode payload, String field, String requestId) throws StdioProtocolException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString()) {
            throw new StdioProtocolException("INVALID_PAYLOAD", requestId, field + " 必须是字符串");
        }
        return value.stringValue();
    }

    private void validateSessionCommandArguments(String intent, ObjectNode arguments, String requestId)
            throws StdioProtocolException {
        Set<String> allowed = switch (intent) {
            case "help", "clear", "context", "doctor" -> Set.of();
            case "compact" -> Set.of("anchors");
            case "model" -> Set.of("name");
            case "permissions" -> Set.of("mode");
            case "resume" -> Set.of("sessionId");
            default -> throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "未知 session.command intent");
        };
        if (arguments.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new StdioProtocolException("UNKNOWN_FIELD", requestId, "arguments 包含未知字段");
        }
        if ((intent.equals("help") || intent.equals("clear") || intent.equals("context") || intent.equals("doctor"))
                && !arguments.isEmpty()) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "该 intent 不接受 arguments");
        }
        if (intent.equals("compact")) {
            JsonNode anchors = arguments.get("anchors");
            if (anchors == null || !anchors.isArray() || anchors.size() > 16
                    || java.util.stream.StreamSupport.stream(anchors.spliterator(), false)
                    .anyMatch(value -> !value.isString() || invalidCompactAnchor(value.stringValue()))) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "compact anchors 非法");
            }
        }
        if (intent.equals("model") && invalidCommandText(requiredPayloadText(arguments, "name", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "model name 非法");
        }
        if (intent.equals("permissions") && !arguments.isEmpty()) {
            String mode = requiredPayloadText(arguments, "mode", requestId);
            if (!mode.equals("DEFAULT") && !mode.equals("PLAN") && !mode.equals("ACCEPT_EDITS")) {
                throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "permissions mode 非法");
            }
        }
        if (intent.equals("resume") && invalidCommandText(requiredPayloadText(arguments, "sessionId", requestId))) {
            throw new StdioProtocolException("INVALID_ARGUMENT", requestId, "resume sessionId 非法");
        }
    }

    private static boolean invalidIdentifier(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > MAX_IDENTIFIER_CHARS
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean invalidCommandText(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > 256
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean invalidCompactAnchor(String value) {
        return value.isBlank() || value.codePointCount(0, value.length()) > 512
                || value.chars().anyMatch(Character::isISOControl);
    }

    private int requiredInt(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw error("INVALID_ENVELOPE", field + " 必须是整数");
        }
        return value.intValue();
    }

    private long requiredLong(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw error("INVALID_ENVELOPE", field + " 必须是整数");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode root, String field)
            throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            throw error("INVALID_ENVELOPE", field + " 必须是字符串");
        }
        return checkedText(value.stringValue(), field, StdioProtocol.UNAVAILABLE_REQUEST_ID);
    }

    private Optional<String> optionalText(
            JsonNode root,
            String field,
            String requestId) throws StdioProtocolException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new StdioProtocolException(
                    "INVALID_ENVELOPE",
                    requestId,
                    field + " 必须是字符串");
        }
        return Optional.of(checkedText(value.stringValue(), field, requestId));
    }

    private String checkedText(String value, String field, String requestId)
            throws StdioProtocolException {
        if (value.isBlank() || value.length() > MAX_IDENTIFIER_CHARS) {
            throw new StdioProtocolException(
                    "INVALID_ENVELOPE",
                    requestId,
                    field + " 为空或超过长度限制");
        }
        return value;
    }

    private StdioProtocolException error(String code, String message) {
        return new StdioProtocolException(
                code,
                StdioProtocol.UNAVAILABLE_REQUEST_ID,
                message);
    }
}
