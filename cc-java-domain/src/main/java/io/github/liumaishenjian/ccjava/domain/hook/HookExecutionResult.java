package io.github.liumaishenjian.ccjava.domain.hook;

import java.util.Objects;
import java.util.Optional;

/**
 * 单个绑定的脱敏执行结果。
 *
 * <p>Reason 和 additionalContext 都是有界、非敏感摘要。原始 stdout/stderr、命令
 * 行和完整模型输入不进入该规范结果。</p>
 *
 * @param handlerId 项目内稳定 Handler ID
 * @param disposition Handler 意见
 * @param status Handler 执行状态
 * @param reason 可选稳定原因摘要
 * @param additionalContext 可选有界上下文增量
 * @since 0.1.0
 */
public record HookExecutionResult(
        String handlerId,
        HookDisposition disposition,
        HookExecutionStatus status,
        Optional<String> reason,
        Optional<String> additionalContext) {

    /** 单个摘要字段的字符上限。 */
    public static final int MAX_TEXT_CHARACTERS = 2_048;

    /**
     * 校验并冻结单个 Handler 结果。
     */
    public HookExecutionResult {
        handlerId = requireText(handlerId, "handlerId");
        disposition = Objects.requireNonNull(disposition, "disposition 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        reason = bounded(reason, "reason");
        additionalContext = bounded(additionalContext, "additionalContext");
    }

    /**
     * 创建成功且不改变决策的结果。
     *
     * @param handlerId 产生结果的稳定 Handler ID
     * @return COMPLETED/CONTINUE 且无正文摘要的结果
     */
    public static HookExecutionResult continued(String handlerId) {
        return new HookExecutionResult(
                handlerId,
                HookDisposition.CONTINUE,
                HookExecutionStatus.COMPLETED,
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<String> bounded(Optional<String> value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        return value.map(text -> {
            Objects.requireNonNull(text, field + " 不能包含 null");
            if (text.codePointCount(0, text.length()) > MAX_TEXT_CHARACTERS) {
                throw new IllegalArgumentException(field + " 超过字符上限");
            }
            return text;
        });
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
