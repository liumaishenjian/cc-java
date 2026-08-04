package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 只存在于单次模型请求 Projection 中的 M5 文件记忆消息。
 *
 * <p>该消息携带已经通过 M5 校验的有界条目和 Catalog revision，不包含文件路径，也不能写入
 * Canonical Session 或 Journal。正文始终是不可信 Context，不能授予权限、批准 Tool、扩大
 * Workspace 或解除任何安全 Gate。</p>
 *
 * @param catalogRevision 产生投影的无路径 Catalog revision
 * @param items 按 M5 选择顺序排列的非空、有界记忆条目
 * @since 0.7.0
 */
public record MemoryContextMessage(
        MemoryCatalogRevision catalogRevision,
        List<MemoryProjectionItem> items) implements AgentMessage {

    /** Provider envelope 使用的稳定来源身份，不包含本机路径。 */
    public static final String SOURCE = "project-file-memory";

    /** M4 Recall 允许的单次最大条目数。 */
    public static final int MAX_ITEMS = 20;

    /** 校验短生命周期消息仍满足 M4/M5 的无路径、有界不变量。 */
    public MemoryContextMessage {
        catalogRevision = Objects.requireNonNull(
                catalogRevision, "catalogRevision 不能为空");
        items = List.copyOf(Objects.requireNonNull(items, "items 不能为空"));
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Memory Context items 必须在 1..20");
        }
        items.forEach(item -> Objects.requireNonNull(item, "Memory Context item 不能为空"));
    }

    /**
     * 返回不泄漏路径的固定来源标签。
     *
     * @return 固定项目文件记忆身份
     */
    public String source() {
        return SOURCE;
    }
}
