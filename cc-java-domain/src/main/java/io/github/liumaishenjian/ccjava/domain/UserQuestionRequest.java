package io.github.liumaishenjian.ccjava.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Plan Runtime 向 Surface 发出的单选结构化问题。
 *
 * <p>{@code callId} 绑定原始 Tool Call；答案必须以相同 ID 返回，保证暂停后继续同一
 * Session/Run 时不会接受迟到或跨 Run 输入。本批只支持单选，避免退化成 y/n 文本解析。</p>
 *
 * @param callId Tool Call 关联 ID
 * @param question 用户可见问题
 * @param options 两到四个封闭选项
 * @since 0.1.0
 */
public record UserQuestionRequest(String callId, String question, List<UserQuestionOption> options) {
    /** 验证关联 ID、问题预算及选项唯一性。 */
    public UserQuestionRequest {
        callId = text(callId, "callId", 128);
        question = text(question, "question", 1_000);
        options = List.copyOf(Objects.requireNonNull(options, "options 不能为空"));
        if (options.size() < 2 || options.size() > 4) {
            throw new IllegalArgumentException("options 必须包含 2 到 4 项");
        }
        HashSet<String> ids = new HashSet<>();
        if (options.stream().anyMatch(option -> !ids.add(option.optionId()))) {
            throw new IllegalArgumentException("optionId 不能重复");
        }
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
