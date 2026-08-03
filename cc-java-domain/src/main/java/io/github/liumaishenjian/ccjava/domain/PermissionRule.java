package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * S05 声明性 Permission Rule。
 *
 * <p>规则只表达行为、可信来源和 Tool/selector 范围。优先级不由列表顺序决定，
 * 而由 Core Policy Kernel 显式实现。Session Rule 必须使用具体 selector，且
 * {@code run_command} 永远不能获得 Tool-wide Session Allow。</p>
 *
 * @param source 可信规则来源
 * @param behavior ALLOW、ASK 或 DENY
 * @param selector Tool 与可选范围
 * @since 0.5.0
 */
public record PermissionRule(
        PermissionRuleSource source,
        PermissionDecision behavior,
        PermissionSelector selector) {

    /** 校验规则边界。 */
    public PermissionRule {
        source = Objects.requireNonNull(source, "source 不能为空");
        behavior = Objects.requireNonNull(behavior, "behavior 不能为空");
        selector = Objects.requireNonNull(selector, "selector 不能为空");
        if (source == PermissionRuleSource.SESSION && selector.toolWide()) {
            throw new IllegalArgumentException("SESSION 规则必须限定具体 selector");
        }
    }

    /**
     * 判断当前规则是否覆盖调用 selector。
     *
     * @param invocation 由可信 Tool Definition 和提取器生成的调用范围
     * @return Tool 名称、来源及具体范围均匹配时为 {@code true}
     */
    public boolean matches(PermissionSelector invocation) {
        return selector.matches(invocation);
    }
}
