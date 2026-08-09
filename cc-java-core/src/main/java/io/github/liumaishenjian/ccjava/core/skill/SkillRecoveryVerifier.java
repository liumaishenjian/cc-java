package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 比较历史 Skill digest 与当前 snapshot；不恢复 Hook 或 Tool Scope。
 *
 * @since 0.11.0
 */
public final class SkillRecoveryVerifier {
    public SkillRecoveryResult verify(SkillCatalogSnapshot snapshot, List<SkillRecoveryRecord> records) {
        List<SkillId> mismatches = new ArrayList<>();
        for (SkillRecoveryRecord record : records) {
            var found = snapshot.entries().stream().filter(e -> e.id().equals(record.skillId())).findFirst();
            if (!snapshot.snapshotId().equals(record.snapshotId()) || found.isEmpty()
                    || !found.get().contentDigest().equals(record.contentDigest())) mismatches.add(record.skillId());
        }
        return new SkillRecoveryResult(mismatches.isEmpty(), mismatches);
    }
}
