package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryProjection;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnostic;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionItem;
import io.github.liumaishenjian.ccjava.domain.MemoryRecallPlan;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import io.github.liumaishenjian.ccjava.domain.MemoryTopicHeader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在 Catalog revision、topic digest 和总正文预算 Gate 内构造 M5 投影。
 *
 * <p>单个 topic 失败只产生结构化诊断；其他候选继续。投影不修改 Catalog 或 Transcript。</p>
 *
 * @since 0.7.0
 */
public final class MemoryProjector {

    private final MemoryBodyLoader loader;

    /** 固定安全正文加载 Port。 */
    public MemoryProjector(MemoryBodyLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader 不能为空");
    }

    /**
     * 校验并投影已选择候选。
     *
     * @param plan M4 计划
     * @param currentCatalog 消费时 Catalog
     * @return 有界投影和隔离诊断
     */
    public MemoryProjection project(MemoryRecallPlan plan, MemoryCatalog currentCatalog) {
        Objects.requireNonNull(plan, "plan 不能为空");
        Objects.requireNonNull(currentCatalog, "currentCatalog 不能为空");
        if (!plan.catalogRevision().equals(currentCatalog.revision())) {
            return MemoryProjection.empty(
                    plan.byteBudget(),
                    currentCatalog.revision(),
                    MemoryProjectionDiagnostic.catalog(
                            MemoryProjectionDiagnosticKind.STALE_CATALOG));
        }
        List<MemoryProjectionItem> items = new ArrayList<>();
        List<MemoryProjectionDiagnostic> diagnostics = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int total = 0;
        for (MemoryTopicHeader header : plan.selectedHeaders()) {
            if (!names.add(header.name())) {
                diagnostics.add(MemoryProjectionDiagnostic.topic(
                        MemoryProjectionDiagnosticKind.DUPLICATE_TOPIC, header.name()));
                continue;
            }
            MemoryTopic topic = loader.load(header.name()).orElse(null);
            if (topic == null) {
                diagnostics.add(MemoryProjectionDiagnostic.topic(
                        MemoryProjectionDiagnosticKind.TOPIC_UNAVAILABLE, header.name()));
                continue;
            }
            if (!topic.contentDigest().equals(header.contentDigest())) {
                diagnostics.add(MemoryProjectionDiagnostic.topic(
                        MemoryProjectionDiagnosticKind.DIGEST_MISMATCH, header.name()));
                continue;
            }
            int bytes = topic.body().getBytes(StandardCharsets.UTF_8).length;
            if (bytes == 0 || bytes > plan.byteBudget() - total) {
                diagnostics.add(MemoryProjectionDiagnostic.topic(
                        MemoryProjectionDiagnosticKind.BUDGET_EXHAUSTED, header.name()));
                continue;
            }
            items.add(new MemoryProjectionItem(
                    topic.name(), topic.kind(), topic.description(), topic.body(),
                    topic.contentDigest(), bytes));
            total += bytes;
        }
        return new MemoryProjection(
                items, total, plan.byteBudget(), currentCatalog.revision(), diagnostics);
    }
}
