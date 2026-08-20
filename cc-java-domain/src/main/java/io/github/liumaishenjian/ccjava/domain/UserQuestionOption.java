package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 结构化用户问题中的单个封闭选项。
 *
 * @param optionId 只用于 call-scoped 关联的稳定标识
 * @param label 用户可见的短标签
 * @param description 用户可见的补充说明
 * @since 0.1.0
 */
public record UserQuestionOption(String optionId, String label, String description) {
    /** 验证选项的有界、可显示文本。 */
    public UserQuestionOption {
        optionId = text(optionId, "optionId", 64);
        label = text(label, "label", 120);
        description = text(description, "description", 500);
    }

    private static String text(String value, String name, int max) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
