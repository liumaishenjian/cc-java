package io.github.liumaishenjian.ccjava.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 由可信版本化配置提供的每百万 Token 价格，不允许用零值表示未知。
 *
 * @param inputPerMillion 输入价格
 * @param outputPerMillion 输出价格
 * @param currency 币种
 * @param priceVersion 价格配置版本
 * @since 0.1.0
 */
public record ModelPrice(
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        Currency currency,
        String priceVersion) {
    /** 校验输入/输出价格非负，并固定币种与有界版本标识。 */
    public ModelPrice {
        inputPerMillion = nonNegative(inputPerMillion, "inputPerMillion");
        outputPerMillion = nonNegative(outputPerMillion, "outputPerMillion");
        currency = Objects.requireNonNull(currency, "currency 不能为空");
        priceVersion = Objects.requireNonNull(priceVersion, "priceVersion 不能为空");
        if (priceVersion.isBlank() || priceVersion.length() > 64) {
            throw new IllegalArgumentException("priceVersion 非法");
        }
    }
    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.signum() < 0) throw new IllegalArgumentException(name + " 不能为负数");
        return value;
    }
}
