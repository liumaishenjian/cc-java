package io.github.liumaishenjian.ccjava.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 可从 M1 topic 文件重建、且不暴露本地路径的 M3 Catalog。
 *
 * <p>{@code entries} 必须按 topic name 严格升序排列且名称唯一；Catalog 不会在构造时静默排序或去重，
 * 因为这会让调用者提供的 revision 与实际内容失去对应关系。</p>
 *
 * @param entries 已验证、按 topic name 严格升序且名称唯一的 topic headers
 * @param diagnostics 扫描期间隔离出的结构化诊断
 * @param revision 对当前有序结果的稳定摘要
 * @since 0.7.0
 */
public record MemoryCatalog(
        List<MemoryTopicHeader> entries,
        List<MemoryDiagnostic> diagnostics,
        MemoryCatalogRevision revision) {

    /** 防御性复制 Catalog。 */
    public MemoryCatalog {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries 不能为空"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        Set<String> names = new HashSet<>();
        String previous = null;
        for (MemoryTopicHeader entry : entries) {
            String name = entry.name();
            if (!names.add(name)) {
                throw new IllegalArgumentException("entries 不能包含重复 topic name");
            }
            if (previous != null && previous.compareTo(name) >= 0) {
                throw new IllegalArgumentException("entries 必须按 topic name 严格升序排列");
            }
            previous = name;
        }
        revision = Objects.requireNonNull(revision, "revision 不能为空");
    }
}
