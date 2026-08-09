package io.github.liumaishenjian.ccjava.domain.skill;

/**
 * Skill 允许的调用入口。
 *
 * @since 0.11.0
 */
public enum SkillInvocationPolicy {
    EXPLICIT,
    MODEL,
    BOTH;

    /** @return 是否允许用户显式调用 */
    public boolean allowsExplicit() { return this != MODEL; }

    /** @return 是否允许模型调用 */
    public boolean allowsModel() { return this != EXPLICIT; }
}
