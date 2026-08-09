package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/** @param skillId 历史 Skill @param snapshotId 历史 catalog digest @param contentDigest 历史内容 digest @since 0.11.0 */
public record SkillRecoveryRecord(SkillId skillId, String snapshotId, String contentDigest) {
    public SkillRecoveryRecord {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
    }
}
