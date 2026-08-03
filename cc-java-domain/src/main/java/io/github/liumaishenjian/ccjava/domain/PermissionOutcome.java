package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Policy Kernel 或审批收敛后产生的类型化 Permission Outcome。
 *
 * <p>{@link #decision()} 只决定 Allow/Ask/Deny；固定原因、可选规则来源与 selector
 * 独立保存，用于解释优先级和生成有界生命周期。ASK 必须携带可展示的具体 scope；
 * 最终执行前仍不得跳过 Tool Adapter 的安全校验。</p>
 *
 * @param decision 权限行为
 * @param reason 稳定原因
 * @param ruleSource 可选规则来源
 * @param selector 已规范化调用范围
 * @since 0.5.0
 */
public record PermissionOutcome(
        PermissionDecision decision,
        PermissionReason reason,
        Optional<PermissionRuleSource> ruleSource,
        PermissionSelector selector) {

    /** 校验 outcome。 */
    public PermissionOutcome {
        decision = Objects.requireNonNull(decision, "decision 不能为空");
        reason = Objects.requireNonNull(reason, "reason 不能为空");
        ruleSource = Objects.requireNonNull(ruleSource, "ruleSource 不能为空");
        selector = Objects.requireNonNull(selector, "selector 不能为空");
    }

    /**
     * 创建无规则来源的 outcome。
     *
     * @param decision 最终或初始权限行为
     * @param reason 稳定原因
     * @param selector 已规范化调用范围
     * @return 不携带规则来源的 outcome
     */
    public static PermissionOutcome of(
            PermissionDecision decision,
            PermissionReason reason,
            PermissionSelector selector) {
        return new PermissionOutcome(decision, reason, Optional.empty(), selector);
    }

    /**
     * 创建由匹配规则产生的 outcome。
     *
     * @param rule 实际命中的可信规则
     * @param reason 与优先级分支对应的稳定原因
     * @param selector 当前调用的完整规范化范围
     * @return 携带规则来源的 outcome
     */
    public static PermissionOutcome fromRule(
            PermissionRule rule,
            PermissionReason reason,
            PermissionSelector selector) {
        Objects.requireNonNull(rule, "rule 不能为空");
        return new PermissionOutcome(
                rule.behavior(),
                reason,
                Optional.of(rule.source()),
                selector);
    }
}
