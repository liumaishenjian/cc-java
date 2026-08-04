package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 按来源解释一次 Context Projection 的 Token 占用。
 *
 * <p>{@code totalTokens} 必须等于各来源之和；{@code remainingTokens} 相对请求容量计算，
 * 超出预算时允许为负数。该类型不包含 Prompt、源码或 Tool 正文。</p>
 *
 * @param systemTokens System Context Token
 * @param instructionTokens 项目或目录指令 Token
 * @param transcriptTokens 非 Tool 规范历史 Token
 * @param toolTokens Tool Call 参数和 Tool Result Token
 * @param memoryTokens 文件记忆投影 Token
 * @param totalTokens 各来源 Token 总数
 * @param remainingTokens 请求预算减去总数后的余量
 * @param estimateKind 计数来源
 * @since 0.7.0
 */
public record ContextUsage(
        long systemTokens,
        long instructionTokens,
        long transcriptTokens,
        long toolTokens,
        long memoryTokens,
        long totalTokens,
        long remainingTokens,
        ContextEstimateKind estimateKind) {

    /**
     * 校验分类统计后创建 Usage。
     *
     * @throws NullPointerException 估算类型为空时
     * @throws IllegalArgumentException 分类为负数或总数不一致时
     */
    public ContextUsage {
        estimateKind = Objects.requireNonNull(estimateKind, "estimateKind 不能为空");
        if (systemTokens < 0
                || instructionTokens < 0
                || transcriptTokens < 0
                || toolTokens < 0
                || memoryTokens < 0) {
            throw new IllegalArgumentException("Context 分类 Token 不能为负数");
        }
        long expected = Math.addExact(
                Math.addExact(systemTokens, instructionTokens),
                Math.addExact(Math.addExact(transcriptTokens, toolTokens), memoryTokens));
        if (totalTokens != expected) {
            throw new IllegalArgumentException("totalTokens 必须等于各来源 Token 之和");
        }
    }

    /**
     * 判断当前 Projection 是否在输入预算内。
     *
     * @return 余量非负时为 {@code true}
     */
    public boolean fits() {
        return remainingTokens >= 0;
    }
}
