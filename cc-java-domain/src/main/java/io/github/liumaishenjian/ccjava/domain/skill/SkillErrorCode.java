package io.github.liumaishenjian.ccjava.domain.skill;

/**
 * Skill 基础设施的封闭失败分类；不得携带物理路径、正文或异常原文。
 *
 * @since 0.11.0
 */
public enum SkillErrorCode {
    INVALID_METADATA, LIMIT_EXCEEDED, CONFLICT, UNREADABLE, IDENTITY_CHANGED,
    RESOURCE_REJECTED, UNKNOWN_SKILL, INVOCATION_NOT_ALLOWED, ALREADY_ACTIVATED,
    NESTED_INVOCATION, RECOVERY_MISMATCH, CANCELLED
}
