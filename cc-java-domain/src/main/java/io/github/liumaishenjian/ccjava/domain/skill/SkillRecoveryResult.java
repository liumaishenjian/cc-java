package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;

/**
 * 恢复 digest 校验结果；它不恢复 Run Scope、Hook 或 Tool visibility。
 *
 * @param matched 是否全部匹配
 * @param mismatches 不匹配的逻辑 Skill 身份
 * @since 0.11.0
 */
public record SkillRecoveryResult(boolean matched, List<SkillId> mismatches) {
    /** 固定不匹配 Skill 的不可变顺序。 */
    public SkillRecoveryResult {
        mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
    }
}
