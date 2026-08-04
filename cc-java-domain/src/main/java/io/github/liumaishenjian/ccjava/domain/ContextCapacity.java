package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 定义一次模型请求可使用的 Context 容量边界。
 *
 * <p>Core 只把 {@link #availableInputTokens()} 用作请求输入硬预算；输出保留和安全余量
 * 不得被 Projection 占用。</p>
 *
 * @param modelId 模型稳定标识
 * @param maximumInputTokens 模型声明的最大输入 Token
 * @param reservedOutputTokens 为模型输出预留的 Token
 * @param safetyMarginTokens 对估算误差保留的安全余量
 * @since 0.7.0
 */
public record ContextCapacity(
        String modelId,
        long maximumInputTokens,
        long reservedOutputTokens,
        long safetyMarginTokens) {

    /**
     * 校验容量边界后创建值对象。
     *
     * @throws NullPointerException 模型标识为空时
     * @throws IllegalArgumentException 标识为空白、计数非法或保留空间耗尽输入容量时
     */
    public ContextCapacity {
        Objects.requireNonNull(modelId, "modelId 不能为空");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空白");
        }
        if (maximumInputTokens <= 0 || reservedOutputTokens < 0 || safetyMarginTokens < 0) {
            throw new IllegalArgumentException("Context 容量计数非法");
        }
        long reserved;
        try {
            reserved = Math.addExact(reservedOutputTokens, safetyMarginTokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("输出保留和安全余量之和溢出", exception);
        }
        if (reserved >= maximumInputTokens) {
            throw new IllegalArgumentException("输出保留和安全余量必须小于最大输入容量");
        }
    }

    /**
     * 返回 Projection 实际可占用的输入预算。
     *
     * @return 扣除输出保留与安全余量后的 Token 数
     */
    public long availableInputTokens() {
        return maximumInputTokens - reservedOutputTokens - safetyMarginTokens;
    }
}
