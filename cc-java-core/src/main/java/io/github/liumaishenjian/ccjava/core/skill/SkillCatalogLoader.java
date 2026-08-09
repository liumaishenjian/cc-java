package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;

/** 从固定 Adapter roots 建立 metadata-only snapshot 的 Port。 @since 0.11.0 */
@FunctionalInterface
public interface SkillCatalogLoader {
    SkillCatalogSnapshot load(CancellationToken cancellationToken);
}
