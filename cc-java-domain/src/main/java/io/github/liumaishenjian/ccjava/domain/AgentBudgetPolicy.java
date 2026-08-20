package io.github.liumaishenjian.ccjava.domain;

/**
 * 单次 Agent Run 的回合与 Tool 数量治理策略。
 *
 * @since 0.15.0
 */
public enum AgentBudgetPolicy {
    /** 调用方给出的数值是不可续租的硬上限。 */ EXPLICIT_HARD,
    /** 普通交互使用进展感知软检查点，并仍受更高绝对上限约束。 */ INTERACTIVE_ADAPTIVE
}
