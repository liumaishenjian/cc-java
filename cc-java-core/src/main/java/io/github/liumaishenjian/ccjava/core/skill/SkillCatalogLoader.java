package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;

/** 从固定 Adapter roots 建立 metadata-only snapshot 的 Port。 @since 0.11.0 */
@FunctionalInterface
public interface SkillCatalogLoader {
    /**
     * 扫描固定来源并冻结 metadata-only Skill 目录。
     *
     * @param cancellationToken 扫描期间使用的取消令牌
     * @return 完成冲突隔离与稳定排序的目录快照
     */
    SkillCatalogSnapshot load(CancellationToken cancellationToken);
}
