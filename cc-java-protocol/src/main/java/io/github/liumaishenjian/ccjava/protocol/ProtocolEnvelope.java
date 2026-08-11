package io.github.liumaishenjian.ccjava.protocol;

import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * stable v1 的项目自有消息信封。
 *
 * <p>Payload 在构造和读取两端都使用深拷贝，调用方不能在消息通过校验后改变其语义。</p>
 *
 * @param version 协议版本
 * @param kind 消息类别
 * @param type 类型化消息名
 * @param messageId 消息身份
 * @param correlationId 请求/响应关联
 * @param sessionId 可选 Session ID
 * @param runId 可选 Run ID
 * @param sequence 连接内严格单调序号
 * @param idempotencyKey 可选幂等键
 * @param payload 有界 JSON object
 * @since 0.1.0
 */
public record ProtocolEnvelope(
        ProtocolVersion version,
        ProtocolMessageKind kind,
        String type,
        String messageId,
        String correlationId,
        Optional<String> sessionId,
        Optional<String> runId,
        long sequence,
        Optional<String> idempotencyKey,
        ObjectNode payload) {

    /** 校验连接信封身份、序号和可选关联字段，并深拷贝 payload。 */
    public ProtocolEnvelope {
        version = Objects.requireNonNull(version, "version 不能为空");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        type = id(type, "type");
        messageId = id(messageId, "messageId");
        correlationId = id(correlationId, "correlationId");
        sessionId = optional(sessionId);
        runId = optional(runId);
        idempotencyKey = optional(idempotencyKey);
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence 必须为正数");
        }
        payload = Objects.requireNonNull(payload, "payload 不能为空").deepCopy();
    }

    /**
     * 返回不可影响信封内部状态的 Payload 副本。
     *
     * @return 深拷贝后的 JSON Object payload
     */
    @Override
    public ObjectNode payload() {
        return payload.deepCopy();
    }

    private static Optional<String> optional(Optional<String> value) {
        Objects.requireNonNull(value, "Optional 不能为空");
        return value.map(identifier -> id(identifier, "identifier"));
    }

    private static String id(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 非法");
        }
        return value;
    }
}
