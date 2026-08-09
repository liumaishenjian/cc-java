package io.github.liumaishenjian.ccjava.domain.skill;

/**
 * Skill 的可信发现来源；优先级由 Core 显式定义，不能依赖文件遍历顺序。
 *
 * @since 0.11.0
 */
public enum SkillSource {
    USER,
    PROJECT,
    PLUGIN
}
