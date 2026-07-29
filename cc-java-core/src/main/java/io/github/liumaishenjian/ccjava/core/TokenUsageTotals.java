package io.github.liumaishenjian.ccjava.core;

/**
 * 多个已完成模型回合中由 Provider 明确报告的 Token Usage 总和。
 *
 * <p>使用 {@code long} 避免跨回合汇总时把单回合 {@code int} 计数静默溢出。
 * 该类型不包含价格，也不根据字符数估算 Token。</p>
 *
 * @param inputTokens Provider 报告的输入 Token 总数
 * @param outputTokens Provider 报告的输出 Token 总数
 * @param totalTokens Provider 报告的总 Token 总数
 * @since 0.1.0
 */
public record TokenUsageTotals(
        long inputTokens,
        long outputTokens,
        long totalTokens) {

    /**
     * 校验所有汇总值均为非负数。
     */
    public TokenUsageTotals {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Token Usage 汇总不能为负数");
        }
    }
}
