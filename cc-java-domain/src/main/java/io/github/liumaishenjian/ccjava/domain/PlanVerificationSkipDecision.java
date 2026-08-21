package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 由可信用户决定入口签发、只能消费一次的 verification skip 能力。
 *
 * <p>该值明确绑定 Session、Plan、用户批准的正文 revision 与单个 requirement。调用者即使
 * 构造字段相同的实例，也必须通过 Runtime 持有的一次性签发登记；模型文本或任意
 * {@code decision-*} 字符串不能获得授权。进程重启会使未消费决定失效，采取 fail closed。</p>
 *
 * @param decisionId 不透明随机决定身份
 * @param sessionId 所属 Session
 * @param planId 所属 Plan
 * @param approvedPlanRevision 用户批准的正文 revision
 * @param requirementId 允许跳过的唯一 requirement
 * @since 0.1.0
 */
public record PlanVerificationSkipDecision(
        String decisionId,
        SessionId sessionId,
        String planId,
        long approvedPlanRevision,
        String requirementId) {
    private static final Pattern DECISION = Pattern.compile("decision-[A-Za-z0-9-]{1,119}");
    private static final Pattern REQUIREMENT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** 验证有界身份与完整绑定；授权性仍由 Runtime 一次性登记决定。 */
    public PlanVerificationSkipDecision {
        decisionId = Objects.requireNonNull(decisionId, "decisionId 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        planId = Objects.requireNonNull(planId, "planId 不能为空");
        requirementId = Objects.requireNonNull(requirementId, "requirementId 不能为空");
        if (!DECISION.matcher(decisionId).matches() || planId.isBlank() || planId.length() > 128
                || approvedPlanRevision < 1 || !REQUIREMENT.matcher(requirementId).matches()) {
            throw new IllegalArgumentException("verification skip 决定绑定无效");
        }
    }
}
