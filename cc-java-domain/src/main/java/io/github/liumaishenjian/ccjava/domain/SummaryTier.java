package io.github.liumaishenjian.ccjava.domain;

/**
 * S07 条件式模型摘要的 Reduction 层级。
 *
 * <p>C3 只归纳满足 rolling window Gate 的已完成历史；C4 仅在 C3 仍不能满足容量且
 * 完整摘要前提成立时使用。层级顺序属于协议，调用者不得先尝试 C4 再回退 C3。</p>
 *
 * @since 0.7.0
 */
public enum SummaryTier {

    /** C3：归纳已完成的滚动历史窗口。 */
    C3_ROLLING(ContextReductionStrategy.ROLLING_MEMORY),

    /** C4：归纳完整且受保护边界之外的历史。 */
    C4_FULL(ContextReductionStrategy.FULL_SUMMARY);

    private final ContextReductionStrategy strategy;

    SummaryTier(ContextReductionStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * 返回投影统计使用的 Reduction 策略。
     *
     * @return 与该层级一一对应的策略
     */
    public ContextReductionStrategy strategy() {
        return strategy;
    }
}
