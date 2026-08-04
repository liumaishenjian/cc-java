package io.github.liumaishenjian.ccjava.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一次 C3/C4 摘要管线的不可变终态。
 *
 * <p>{@code previousProjection} 是进入摘要管线的 C1/C2 完整候选。跳过、候选拒绝、失败与取消
 * 必须返回与它深度相等的 {@code projection}；只有通过所有 Adoption Gate 的候选才能追加恰好
 * 一个 C3/C4 Reduction 并返回 {@link Status#ADOPTED}。该结果不修改 Canonical Transcript。</p>
 *
 * @param status 摘要管线终态
 * @param previousProjection 进入 C3/C4 前的不可变 Projection
 * @param projection 最终可见 Projection
 * @param attemptedTiers 本次实际调用摘要 Port 的层级，最多按 C3、C4 各一次
 * @param diagnostics 有界结构化诊断
 * @param adoptedCandidate 已提交候选；仅 ADOPTED 存在
 * @since 0.7.0
 */
public record SummaryOutcome(
        Status status,
        ContextProjection previousProjection,
        ContextProjection projection,
        List<SummaryTier> attemptedTiers,
        List<SummaryDiagnostic> diagnostics,
        Optional<SummaryCandidate> adoptedCandidate) {

    /** C3/C4 摘要管线终态。 */
    public enum Status {
        /** 候选通过 Gate，并产生满足容量的新 Projection。 */
        ADOPTED,
        /** 没有候选提交，继续保留先前 Projection。 */
        UNCHANGED,
        /** 提交前取消，继续保留先前 Projection。 */
        CANCELLED
    }

    /** 单次结果允许的诊断数量硬上限。 */
    public static final int MAX_DIAGNOSTICS = 16;

    /**
     * 防御性复制并校验“未提交不变化、已提交恰好一步”的终态不变量。
     *
     * @throws NullPointerException 任一必填引用或 Optional 容器为空时
     * @throws IllegalArgumentException 状态、Projection、尝试顺序、诊断或候选不一致时
     */
    public SummaryOutcome {
        status = Objects.requireNonNull(status, "status 不能为空");
        previousProjection = Objects.requireNonNull(
                previousProjection, "previousProjection 不能为空");
        projection = Objects.requireNonNull(projection, "projection 不能为空");
        attemptedTiers = List.copyOf(Objects.requireNonNull(
                attemptedTiers, "attemptedTiers 不能为空"));
        diagnostics = List.copyOf(Objects.requireNonNull(
                diagnostics, "diagnostics 不能为空"));
        adoptedCandidate = Objects.requireNonNull(
                adoptedCandidate, "adoptedCandidate 不能为空");
        validateAttempts(attemptedTiers);
        if (diagnostics.size() > MAX_DIAGNOSTICS) {
            throw new IllegalArgumentException("diagnostics 超过数量上限");
        }
        if (previousProjection.sourceRevision() != projection.sourceRevision()) {
            throw new IllegalArgumentException("摘要不能改变 Projection sourceRevision");
        }
        if (status == Status.ADOPTED) {
            validateAdopted(
                    previousProjection,
                    projection,
                    attemptedTiers,
                    diagnostics,
                    adoptedCandidate.orElseThrow(() ->
                            new IllegalArgumentException("ADOPTED 必须携带候选")));
        } else {
            if (adoptedCandidate.isPresent()) {
                throw new IllegalArgumentException("未提交终态不能携带 adoptedCandidate");
            }
            if (!projection.equals(previousProjection)) {
                throw new IllegalArgumentException("未提交终态必须保留 previousProjection");
            }
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("未提交终态必须携带结构化诊断");
            }
            boolean cancelled = diagnostics.stream()
                    .anyMatch(diagnostic -> diagnostic.kind() == SummaryDiagnostic.Kind.CANCELLED);
            if ((status == Status.CANCELLED) != cancelled) {
                throw new IllegalArgumentException("CANCELLED 状态必须与取消诊断一致");
            }
        }
    }

    private static void validateAttempts(List<SummaryTier> attemptedTiers) {
        if (attemptedTiers.size() > SummaryTier.values().length) {
            throw new IllegalArgumentException("每个层级最多尝试一次");
        }
        Set<SummaryTier> unique = new HashSet<>();
        int previousOrdinal = -1;
        for (SummaryTier tier : attemptedTiers) {
            SummaryTier checked = Objects.requireNonNull(tier, "attemptedTier 不能为空");
            if (!unique.add(checked) || checked.ordinal() <= previousOrdinal) {
                throw new IllegalArgumentException("摘要层级必须按 C3-C4 顺序且各最多一次");
            }
            previousOrdinal = checked.ordinal();
        }
    }

    private static void validateAdopted(
            ContextProjection previous,
            ContextProjection adopted,
            List<SummaryTier> attemptedTiers,
            List<SummaryDiagnostic> diagnostics,
            SummaryCandidate candidate) {
        if (attemptedTiers.isEmpty()
                || attemptedTiers.getLast() != candidate.tier()) {
            throw new IllegalArgumentException("提交候选必须对应最后实际尝试层级");
        }
        if (candidate.sourceRevision() != adopted.sourceRevision()) {
            throw new IllegalArgumentException("提交候选 revision 必须匹配 Projection");
        }
        if (!adopted.usage().fits()
                || adopted.usage().totalTokens() >= previous.usage().totalTokens()) {
            throw new IllegalArgumentException("提交后的 Projection 必须满足容量并降低 Token");
        }
        List<ContextReduction> before = previous.appliedReductions();
        List<ContextReduction> after = adopted.appliedReductions();
        if (after.size() != before.size() + 1
                || !after.subList(0, before.size()).equals(before)) {
            throw new IllegalArgumentException("摘要只能在先前 Reduction 后追加一步");
        }
        ContextReduction summaryReduction = after.getLast();
        if (summaryReduction.strategy() != candidate.tier().strategy()
                || summaryReduction.tokensBefore() != previous.usage().totalTokens()
                || summaryReduction.tokensAfter() != adopted.usage().totalTokens()
                || summaryReduction.affectedMessages() != candidate.sourceMessageIds().size()) {
            throw new IllegalArgumentException("摘要 Reduction 统计必须与候选和 Projection 一致");
        }
        if (diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.kind() == SummaryDiagnostic.Kind.CANCELLED)) {
            throw new IllegalArgumentException("已提交结果不能携带取消诊断");
        }
    }
}
