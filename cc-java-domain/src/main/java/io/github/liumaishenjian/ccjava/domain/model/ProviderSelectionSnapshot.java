package io.github.liumaishenjian.ccjava.domain.model;

import java.util.Objects;

/**
 * 单个 Run 启动时固定的非秘密 Provider 选择。
 *
 * <p>快照只携带已校验 identity；不持久化 secret、profile generation 或 store 信息。
 * active Run 不因后续默认值变化而替换该快照。</p>
 *
 * @param providerId Provider identity
 * @param profileId credential profile identity
 * @param modelId Provider catalog 中的精确模型 identity
 * @since 0.1.0
 */
public record ProviderSelectionSnapshot(String providerId, String profileId, String modelId) {
    /** 校验三个 identity，防止控制字符进入事件或错误面。 */
    public ProviderSelectionSnapshot {
        providerId = id(providerId, "providerId");
        profileId = id(profileId, "profileId");
        modelId = model(modelId);
    }

    private static String id(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " 格式无效");
        }
        return value;
    }

    private static String model(String value) {
        Objects.requireNonNull(value, "modelId 不能为空");
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 256 || bytes > 1024
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("modelId 格式无效");
        }
        return value;
    }
}
