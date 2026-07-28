package io.github.liumaishenjian.ccjava.model.springai;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter 边界内使用的最小 JSON 编解码器。
 *
 * <p>只转换 Tool 参数与结构化失败结果，不启用多态类型、外部模块或宽松解析。
 * JSON 解析成功后仍必须由 Domain 的 {@code JsonObject} 再次校验值边界。</p>
 *
 * @since 0.1.0
 */
final class JsonCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonCodec() {
    }

    /**
     * 编码一个普通 JSON 值。
     *
     * @param value 待编码值
     * @return JSON 文本
     * @throws IllegalArgumentException 无法编码时
     */
    static String write(Object value) {
        Objects.requireNonNull(value, "value 不能为空");
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("无法编码 Tool JSON", exception);
        }
    }

    /**
     * 解码为不带多态类型信息的普通 Java 值。
     *
     * @param json JSON 文本
     * @return Map、List、String、Boolean 或 Number
     * @throws IllegalArgumentException JSON 不完整或无效时
     */
    static Object read(String json) {
        Objects.requireNonNull(json, "json 不能为空");
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("无法解析 Tool JSON", exception);
        }
    }
}
