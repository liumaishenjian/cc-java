package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import java.util.Optional;

/** 当前 Session 的不可变 Skill Catalog 查询面。 @since 0.11.0 */
public interface SkillCatalog {
    SkillCatalogSnapshot snapshot();
    default Optional<SkillDescriptor> find(SkillId id) {
        return snapshot().entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
}
