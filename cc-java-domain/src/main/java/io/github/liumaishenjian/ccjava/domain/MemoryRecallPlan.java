package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * M4 对当前 Catalog 进行确定性排序后产生的有界选择计划。
 *
 * @param selectedHeaders 按相关性与稳定 tie-break 排列的候选
 * @param catalogRevision 选择时的 Catalog revision
 * @param byteBudget 交给 M5 的正文总预算
 * @since 0.7.0
 */
public record MemoryRecallPlan(
        List<MemoryTopicHeader> selectedHeaders,
        MemoryCatalogRevision catalogRevision,
        int byteBudget) {

    /** M4 计划最多携带的候选数，与 {@link RecallQuery#maxTopics()} 上限一致。 */
    public static final int MAX_SELECTED_TOPICS = 20;

    /** 防御性复制并校验数量与预算。 */
    public MemoryRecallPlan {
        selectedHeaders = List.copyOf(Objects.requireNonNull(
                selectedHeaders, "selectedHeaders 不能为空"));
        if (selectedHeaders.size() > MAX_SELECTED_TOPICS) {
            throw new IllegalArgumentException("selectedHeaders 不能超过 20 个");
        }
        catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision 不能为空");
        if (byteBudget < 1 || byteBudget > 256 * 1024) {
            throw new IllegalArgumentException("byteBudget 必须在 1..262144");
        }
    }
}
