package io.github.liumaishenjian.ccjava.core.model;

import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.model.ModelCost;
import io.github.liumaishenjian.ccjava.domain.model.ModelPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 仅在 Usage 与可信价格同时存在时计算模型费用。
 *
 * @since 0.1.0
 */
public final class ModelCostCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    /** 创建无状态的模型费用计算器。 */
    public ModelCostCalculator() { }

    /**
     * 使用十进制精度计算费用，绝不把未知价格变成零。
     *
     * @param usage Provider 明确返回的 Token Usage
     * @param price 可信版本化价格
     * @return 同币种、同价格版本的模型费用
     */
    public ModelCost calculate(ModelUsage usage, ModelPrice price) {
        Objects.requireNonNull(usage, "usage 不能为空");
        Objects.requireNonNull(price, "price 不能为空");
        BigDecimal input = price.inputPerMillion()
                .multiply(BigDecimal.valueOf(usage.inputTokens()))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal output = price.outputPerMillion()
                .multiply(BigDecimal.valueOf(usage.outputTokens()))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        return new ModelCost(input.add(output), price.currency(), price.priceVersion());
    }
}
