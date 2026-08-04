package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * M2 {@code MEMORY.md} 的确定性渲染结果。
 *
 * <p>内容只使用 topic 相对链接与 bounded hook，不包含 memory root 或 Workspace 绝对路径。</p>
 *
 * @param content UTF-8 可编码的索引正文
 * @param includedTopics 实际写入索引的 topic 数量
 * @param diagnostics 因行数或字节上限产生的结构化诊断
 * @since 0.7.0
 */
public record MemoryIndex(
        String content,
        int includedTopics,
        List<MemoryDiagnostic> diagnostics) {

    /** 校验并防御性复制渲染结果。 */
    public MemoryIndex {
        content = Objects.requireNonNull(content, "content 不能为空");
        if (includedTopics < 0) {
            throw new IllegalArgumentException("includedTopics 不能为负数");
        }
        long contentLines = content.lines().count();
        if (includedTopics != contentLines) {
            throw new IllegalArgumentException("includedTopics 必须等于索引正文行数");
        }
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
    }
}
