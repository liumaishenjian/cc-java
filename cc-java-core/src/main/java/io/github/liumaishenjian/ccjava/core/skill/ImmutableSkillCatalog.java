package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot;
import java.util.Objects;

/**
 * 持有 Session 启动时固定的 Skill catalog snapshot。
 *
 * <p>该服务不刷新磁盘；Adapter 的后续扫描只能用于新 Session，避免运行中的模型视图与
 * 恢复 digest 因外部文件变化而漂移。</p>
 *
 * @since 0.11.0
 */
public final class ImmutableSkillCatalog implements SkillCatalog {
    private final SkillCatalogSnapshot snapshot;

    /**
     * 固定一个已发布快照，后续磁盘扫描不会改变该目录视图。
     *
     * @param snapshot 已完成冲突隔离和稳定排序的快照
     */
    public ImmutableSkillCatalog(SkillCatalogSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
    }

    @Override
    public SkillCatalogSnapshot snapshot() {
        return snapshot;
    }
}
