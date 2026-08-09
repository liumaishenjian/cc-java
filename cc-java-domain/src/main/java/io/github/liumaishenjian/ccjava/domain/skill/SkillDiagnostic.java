package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/** @param skillId 可选逻辑身份 @param code 固定失败分类 @since 0.11.0 */
public record SkillDiagnostic(SkillId skillId, SkillErrorCode code) {
    public SkillDiagnostic { code = Objects.requireNonNull(code, "code 不能为空"); }
}
