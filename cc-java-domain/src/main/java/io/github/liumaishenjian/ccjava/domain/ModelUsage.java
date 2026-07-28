package io.github.liumaishenjian.ccjava.domain;

/**
 * Provider 明确返回的单个或多个模型回合 Token Usage。
 *
 * <p>该类型不得承载 Runtime 根据字符数或分词器自行估算的数值。Provider
 * 没有返回完整 Usage 时，调用方应使用空的 {@code Optional}，而不是构造
 * 全零或猜测值。费用计算不属于 S02，本类型只保存 Token 计数。</p>
 *
 * @param inputTokens  Provider 报告的输入 Token 数
 * @param outputTokens Provider 报告的输出 Token 数
 * @param totalTokens  Provider 报告的总 Token 数
 * @since 0.1.0
 */
public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {

    /** 用于安全聚合的零值；不能用它表示 Provider 缺失 Usage。 */
    public static final ModelUsage ZERO = new ModelUsage(0, 0, 0);

    /**
     * 校验 Provider Usage 不包含负数。
     *
     * @param inputTokens Provider 报告的输入 Token 数
     * @param outputTokens Provider 报告的输出 Token 数
     * @param totalTokens Provider 报告的总 Token 数
     * @throws IllegalArgumentException 任一计数小于 0 时
     */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Model Usage 计数不能小于 0");
        }
    }

    /**
     * 精确合并两个均由 Provider 报告的 Usage。
     *
     * @param other 另一个完整 Usage
     * @return 各字段分别相加后的新值
     * @throws NullPointerException {@code other} 为空时
     * @throws ArithmeticException 任一字段溢出 {@code long} 时
     */
    public ModelUsage plus(ModelUsage other) {
        java.util.Objects.requireNonNull(other, "other 不能为空");
        return new ModelUsage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(totalTokens, other.totalTokens));
    }
}
