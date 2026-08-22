package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 表示一次 Plan 确定性验证失败后可安全反馈给执行 Runtime 的有界原因。
 *
 * <p>该值只携带 planning 阶段已批准的 requirement 身份、种类、locator 与封闭原因码，
 * 不包含文件正文、Tool 输出、异常文本、物理路径、Prompt 或 Secret。</p>
 *
 * @param requirementId 失败 requirement 的稳定身份
 * @param kind 交付物或验证要求
 * @param locator 已批准的相对交付物路径或可信 BUILT_IN Tool 名
 * @param reason 封闭验证原因码
 * @since 0.1.0
 */
public record PlanEvidenceCorrectionFailure(
        String requirementId,
        PlanEvidenceKind kind,
        String locator,
        String reason) {

    /** 校验全部字段均来自既有有界 requirement 与固定原因码。 */
    public PlanEvidenceCorrectionFailure {
        requirementId = requireText(requirementId, "requirementId");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        locator = requireText(locator, "locator");
        reason = requireText(reason, "reason");
        if (requirementId.length() > 128 || locator.length() > 512 || reason.length() > 64) {
            throw new IllegalArgumentException("Plan evidence correction 字段超过上限");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }
}
