package io.github.liumaishenjian.ccjava.domain.skill;

/** Skill 基础设施的封闭失败分类；不得携带物理路径、正文或异常原文。 @since 0.11.0 */
public enum SkillErrorCode {
    /** Frontmatter、名称或 schema 非法。 */ INVALID_METADATA,
    /** 文件、行、项或投影预算超限。 */ LIMIT_EXCEEDED,
    /** 多来源发布了同一规范身份。 */ CONFLICT,
    /** 文件或编码不可安全读取。 */ UNREADABLE,
    /** 调用时 identity 或 digest 已变化。 */ IDENTITY_CHANGED,
    /** 资源路径、类型、链接或内容被拒绝。 */ RESOURCE_REJECTED,
    /** Catalog 中不存在该 Skill。 */ UNKNOWN_SKILL,
    /** 调用入口不符合 metadata 策略。 */ INVOCATION_NOT_ALLOWED,
    /** 当前 Run 已成功激活该 Skill。 */ ALREADY_ACTIVATED,
    /** 正在准备或已激活的 Skill 尝试嵌套调用。 */ NESTED_INVOCATION,
    /** 恢复记录与当前受信 snapshot 不匹配。 */ RECOVERY_MISMATCH,
    /** 调用或 Scope 已取消/关闭。 */ CANCELLED
}
