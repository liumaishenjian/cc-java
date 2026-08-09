package io.github.liumaishenjian.ccjava.domain.skill;

/**
 * Skill 允许的调用入口。
 *
 * @since 0.11.0
 */
public enum SkillInvocationPolicy {
    /** 只允许用户显式调用。 */
    EXPLICIT,
    /** 只允许模型 Tool 调用。 */
    MODEL,
    /** 两种入口均允许。 */
    BOTH;

    /**
     * 判断用户显式入口是否可用。
     *
     * @return 是否允许显式调用
     */
    public boolean allowsExplicit() { return this != MODEL; }

    /**
     * 判断模型 Tool 入口是否可用。
     *
     * @return 是否允许模型调用
     */
    public boolean allowsModel() { return this != EXPLICIT; }
}
