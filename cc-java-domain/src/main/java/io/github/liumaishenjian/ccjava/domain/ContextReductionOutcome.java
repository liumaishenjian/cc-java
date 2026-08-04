package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * Context Planner/Reducer 的唯一结构化结果。
 *
 * <p>失败和取消同样返回未伪造成功的 Projection 快照；调用者必须根据 {@code status}
 * 决定是否可发送给模型。原因码与统计均不包含不可信正文。</p>
 *
 * @param status Reduction 终态
 * @param projection 最后一个完整、不可变的候选 Projection
 * @param initialUsage Reduction 前 Usage
 * @param finalUsage Reduction 后 Usage
 * @param reason 隐私安全原因码
 * @since 0.7.0
 */
public record ContextReductionOutcome(
        ContextReductionStatus status,
        ContextProjection projection,
        ContextUsage initialUsage,
        ContextUsage finalUsage,
        ContextReductionReason reason) {

    /**
     * 校验 Outcome 的 Usage 与 Projection 一致。
     *
     * @throws NullPointerException 任一引用为空时
     * @throws IllegalArgumentException 最终 Usage 与 Projection 不一致时
     */
    public ContextReductionOutcome {
        status = Objects.requireNonNull(status, "status 不能为空");
        projection = Objects.requireNonNull(projection, "projection 不能为空");
        initialUsage = Objects.requireNonNull(initialUsage, "initialUsage 不能为空");
        finalUsage = Objects.requireNonNull(finalUsage, "finalUsage 不能为空");
        reason = Objects.requireNonNull(reason, "reason 不能为空");
        if (!projection.usage().equals(finalUsage)) {
            throw new IllegalArgumentException("finalUsage 必须与 projection.usage 一致");
        }
        boolean changed = !projection.appliedReductions().isEmpty();
        if (status == ContextReductionStatus.REDUCED) {
            if (!changed || !finalUsage.fits() || finalUsage.totalTokens() >= initialUsage.totalTokens()) {
                throw new IllegalArgumentException("REDUCED 必须提交策略、满足预算并降低总 Token");
            }
        } else {
            if (changed || !initialUsage.equals(finalUsage)) {
                throw new IllegalArgumentException("未提交 Reduction 的终态不能报告策略或 Usage 变化");
            }
            if (status == ContextReductionStatus.UNCHANGED && !finalUsage.fits()) {
                throw new IllegalArgumentException("UNCHANGED 必须已经满足预算");
            }
        }
    }
}
