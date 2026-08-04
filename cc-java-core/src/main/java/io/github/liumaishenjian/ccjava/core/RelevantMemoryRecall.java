package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryRecallPlan;
import io.github.liumaishenjian.ccjava.domain.MemoryTopicHeader;
import io.github.liumaishenjian.ccjava.domain.RecallQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 按显式词项命中与稳定 tie-break 从 M3 Catalog 选择 M4 候选。
 *
 * <p>本策略不调用模型、不读取正文，也不把零命中的 topic 猜测为相关。</p>
 *
 * @since 0.7.0
 */
public final class RelevantMemoryRecall {

    /**
     * 选择有界候选。
     *
     * @param catalog 当前 Catalog manifest
     * @param query 有界召回查询
     * @return 按分数降序、名称升序排列的计划
     */
    public MemoryRecallPlan select(MemoryCatalog catalog, RecallQuery query) {
        Objects.requireNonNull(catalog, "catalog 不能为空");
        Objects.requireNonNull(query, "query 不能为空");
        if (!catalog.revision().equals(query.catalogRevision())) {
            return new MemoryRecallPlan(List.of(), catalog.revision(), query.byteBudget());
        }
        String user = query.userText().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String keyword : query.keywords()) {
            String term = keyword.toLowerCase(Locale.ROOT);
            if (!terms.contains(term)) {
                terms.add(term);
            }
        }
        List<Scored> scored = new ArrayList<>();
        for (MemoryTopicHeader header : catalog.entries()) {
            String searchable = (header.name() + " " + header.description())
                    .toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                if (searchable.contains(term)) {
                    score += 4;
                }
                if (user.contains(term) && searchable.contains(term)) {
                    score += 2;
                }
            }
            for (String component : header.name().split("-")) {
                if (component.length() >= 3 && user.contains(component)) {
                    score++;
                }
            }
            if (score > 0) {
                scored.add(new Scored(header, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(scoredItem -> scoredItem.header().name()));
        List<MemoryTopicHeader> selected = scored.stream()
                .limit(query.maxTopics())
                .map(Scored::header)
                .toList();
        return new MemoryRecallPlan(selected, catalog.revision(), query.byteBudget());
    }

    private record Scored(MemoryTopicHeader header, int score) {
    }
}
