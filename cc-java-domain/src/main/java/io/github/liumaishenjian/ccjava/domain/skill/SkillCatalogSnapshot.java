package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;
import java.util.Objects;

/**
 * Session 启动时固定的不可变 Skill Catalog。
 *
 * @param snapshotId 对有序有效条目计算的 SHA-256
 * @param entries 已按来源优先级和 Skill ID 稳定排序的无冲突条目
 * @param diagnostics 不含路径/正文的结构化诊断
 * @since 0.11.0
 */
public record SkillCatalogSnapshot(String snapshotId, List<SkillDescriptor> entries,
        List<SkillDiagnostic> diagnostics) {
    public SkillCatalogSnapshot {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        if (!snapshotId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("snapshotId 非法");
        entries = List.copyOf(entries == null ? List.of() : entries);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        if (entries.size() > 256) throw new IllegalArgumentException("Catalog 超过 256 项");
    }
}
