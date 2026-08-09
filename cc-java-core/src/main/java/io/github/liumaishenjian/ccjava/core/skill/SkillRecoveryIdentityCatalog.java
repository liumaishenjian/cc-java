package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryIdentity;
import java.util.Optional;

/**
 * 提供当前 Session 已冻结且可精确校验的 Skill 恢复身份。
 *
 * <p>实现位于架构边缘并负责文件与 Plugin snapshot 身份；缺失身份必须 Fail Closed。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface SkillRecoveryIdentityCatalog {
    /** @return 当前 Skill 的冻结身份；不可验证时为空 */
    Optional<SkillRecoveryIdentity> find(SkillId skillId);

    /** @return 不发布任何可恢复身份的空实现 */
    static SkillRecoveryIdentityCatalog none() { return ignored -> Optional.empty(); }
}
