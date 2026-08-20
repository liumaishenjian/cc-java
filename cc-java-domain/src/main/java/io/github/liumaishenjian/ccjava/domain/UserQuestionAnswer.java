package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * Surface 对结构化 Plan 问题的单选答案。
 *
 * @param callId 必须匹配等待中的 Tool Call
 * @param optionId 用户选择的已声明选项
 * @since 0.1.0
 */
public record UserQuestionAnswer(String callId, String optionId) {
    /** 验证答案关联字段。 */
    public UserQuestionAnswer {
        callId = text(callId, "callId", 128);
        optionId = text(optionId, "optionId", 64);
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
