package io.github.liumaishenjian.ccjava.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 基于 Provider 明确 Usage 与可信价格计算的金额。
 *
 * @param amount 金额
 * @param currency 币种
 * @param priceVersion 价格版本
 * @since 0.1.0
 */
public record ModelCost(BigDecimal amount, Currency currency, String priceVersion) {
    /** 校验金额非负并固定币种与价格版本。 */
    public ModelCost {
        amount = Objects.requireNonNull(amount, "amount 不能为空");
        if (amount.signum() < 0) throw new IllegalArgumentException("amount 不能为负数");
        currency = Objects.requireNonNull(currency, "currency 不能为空");
        priceVersion = Objects.requireNonNull(priceVersion, "priceVersion 不能为空");
    }
}
