package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型请求可见的短生命周期 Context Projection。
 *
 * <p>Projection 只包含 Canonical Transcript 的派生快照；Reduction 不能删除、重排或重写
 * Canonical Transcript。列表中的同批 Tool Call 与对应 Result 必须保持协议完整。</p>
 *
 * @param messages 投影后的不可变消息
 * @param usage 投影后的 Context Usage
 * @param appliedReductions 按提交顺序记录的 Reduction
 * @param sourceRevision 构建时读取的规范历史版本
 * @since 0.7.0
 */
public record ContextProjection(
        List<AgentMessage> messages,
        ContextUsage usage,
        List<ContextReduction> appliedReductions,
        long sourceRevision) {

    /**
     * 防御性复制投影并校验 revision。
     *
     * @throws NullPointerException 消息、Usage 或 Reduction 列表为空时
     * @throws IllegalArgumentException revision 为负数时
     */
    public ContextProjection {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        usage = Objects.requireNonNull(usage, "usage 不能为空");
        appliedReductions = List.copyOf(
                Objects.requireNonNull(appliedReductions, "appliedReductions 不能为空"));
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision 不能为负数");
        }
    }
}
