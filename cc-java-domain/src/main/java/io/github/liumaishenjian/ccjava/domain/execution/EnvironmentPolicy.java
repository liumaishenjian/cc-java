package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Map;
import java.util.Objects;

/**
 * 从空集合构造的显式非 Secret 环境。调用者不得把继承环境当作默认值。
 *
 * @param variables 允许注入的名称和值
 * @since 0.13.0
 */
public record EnvironmentPolicy(Map<String, String> variables) {
    /** 冻结环境 allowlist 与继承策略。 */
    public EnvironmentPolicy {
        variables = Map.copyOf(Objects.requireNonNull(variables));
    }

    /**
     * 创建不注入任何变量的环境策略。
     *
     * @return 空环境策略
     */
    public static EnvironmentPolicy empty() {
        return new EnvironmentPolicy(Map.of());
    }
}
