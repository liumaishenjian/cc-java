package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import java.util.Optional;

/**
 * 当前 Session 的不可变 Skill Catalog 查询面。
 *
 * <p>实现必须始终返回同一 snapshot；刷新或重新扫描属于 Adapter 为新 Session 创建 Catalog
 * 的职责，不能热切换当前 Session。</p>
 *
 * @since 0.11.0
 */
public interface SkillCatalog {
    /** @return 当前 Session 固定快照 */
    SkillCatalogSnapshot snapshot();

    /**
     * @param id 规范 Skill 身份
     * @return 无冲突且已发布的 descriptor
     */
    default Optional<SkillDescriptor> find(SkillId id) {
        return snapshot().entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
}
