package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 隔离 Adapter 所需的 JSON 序列化与 Tool Call 参数解析。
 *
 * <p>Domain 不依赖 Jackson；非法或非 Object 参数被转换为结构化模型失败。</p>
 */
final class SpringAiJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private SpringAiJson() {
    }

    static JsonObject readArguments(String arguments) throws ModelGatewayException {
        try {
            Map<String, Object> values = MAPPER.readValue(arguments, MAP_TYPE);
            return new JsonObject(values);
        } catch (RuntimeException exception) {
            throw new ModelGatewayException("Provider returned invalid Tool Call arguments");
        }
    }

    static String write(Map<String, Object> values) {
        return MAPPER.writeValueAsString(values);
    }
}
