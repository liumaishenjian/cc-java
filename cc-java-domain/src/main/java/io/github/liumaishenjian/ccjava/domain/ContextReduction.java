package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 记录一个已提交到 Projection 的 Reduction 统计。
 *
 * <p>该值对象不保存被替换正文，只提供可解释、可序列化的策略与 Token 变化。</p>
 *
 * @param strategy 已应用策略
 * @param tokensBefore 应用前估算 Token
 * @param tokensAfter 应用后估算 Token
 * @param affectedMessages 被替换的消息数量
 * @since 0.7.0
 */
public record ContextReduction(
        ContextReductionStrategy strategy,
        long tokensBefore,
        long tokensAfter,
        int affectedMessages) {

    /**
     * 校验 Reduction 确实释放空间后创建统计。
     *
     * @throws NullPointerException 策略为空时
     * @throws IllegalArgumentException 计数非法或未释放 Token 时
     */
    public ContextReduction {
        strategy = Objects.requireNonNull(strategy, "strategy 不能为空");
        if (tokensBefore <= tokensAfter || tokensAfter < 0 || affectedMessages <= 0) {
            throw new IllegalArgumentException("Reduction 必须安全释放 Token 且影响至少一条消息");
        }
    }

    /**
     * 返回本次 Reduction 释放的估算 Token。
     *
     * @return 正数 Token 数
     */
    public long estimatedTokensFreed() {
        return tokensBefore - tokensAfter;
    }
}
