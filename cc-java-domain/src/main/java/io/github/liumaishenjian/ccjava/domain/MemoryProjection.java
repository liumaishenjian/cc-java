package io.github.liumaishenjian.ccjava.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * M5 产生的短生命周期、有来源且有总预算的记忆投影。
 *
 * @param items 按选择顺序排列且名称唯一的投影项
 * @param totalUtf8Bytes 正文总 UTF-8 字节数
 * @param byteBudget 本次投影预算
 * @param catalogRevision 投影对应的 Catalog revision
 * @param diagnostics 被隔离候选与降级原因
 * @since 0.7.0
 */
public record MemoryProjection(
        List<MemoryProjectionItem> items,
        int totalUtf8Bytes,
        int byteBudget,
        MemoryCatalogRevision catalogRevision,
        List<MemoryProjectionDiagnostic> diagnostics) {

    /** 校验预算、总量、顺序输入唯一性并防御性复制。 */
    public MemoryProjection {
        items = List.copyOf(Objects.requireNonNull(items, "items 不能为空"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision 不能为空");
        if (byteBudget < 1 || totalUtf8Bytes < 0 || totalUtf8Bytes > byteBudget) {
            throw new IllegalArgumentException("投影总量必须位于预算内");
        }
        int actual = 0;
        Set<String> names = new HashSet<>();
        for (MemoryProjectionItem item : items) {
            if (!names.add(item.name())) {
                throw new IllegalArgumentException("投影不能包含重复 topic");
            }
            actual = Math.addExact(actual, item.utf8Bytes());
        }
        if (actual != totalUtf8Bytes) {
            throw new IllegalArgumentException("totalUtf8Bytes 与 items 不一致");
        }
    }

    /**
     * 创建不携带正文的空投影。
     *
     * @param byteBudget 本次仍适用、但未被消耗的正文 UTF-8 字节预算
     * @param revision 消费时已经验证的 Catalog revision
     * @param diagnostic 说明为何安全降级为空且不回显内容的诊断
     * @return 保留预算、revision 与单条诊断的空 M5 投影
     */
    public static MemoryProjection empty(
            int byteBudget,
            MemoryCatalogRevision revision,
            MemoryProjectionDiagnostic diagnostic) {
        return new MemoryProjection(
                List.of(), 0, byteBudget, revision, List.of(diagnostic));
    }
}
