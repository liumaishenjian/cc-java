package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillContentSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;

/** 调用时重验 identity/digest 并懒加载正文的 Port。 @since 0.11.0 */
@FunctionalInterface
public interface SkillContentLoader {
    SkillContentSnapshot load(SkillDescriptor descriptor, String snapshotId, CancellationToken cancellationToken);
}
