package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import java.util.List;
import java.util.Objects;

/**
 * 定义 AgentRuntime Projection seam 使用的显式模型容量与 C1-C4 有界参数。
 *
 * @param capacity 当前模型输入容量
 * @param largePayloadTokenThreshold C1 单结果触发阈值
 * @param protectedMessageCount 从 Canonical 尾部保护的消息数
 * @param maxSummaryUtf8Bytes 摘要候选 UTF-8 上限
 * @param maxSummaryTokens 摘要候选 Token 上限
 * @since 0.7.0
 */
public record ContextPreparationConfig(
        ContextCapacity capacity,
        long largePayloadTokenThreshold,
        int protectedMessageCount,
        int maxSummaryUtf8Bytes,
        long maxSummaryTokens) {

    /** 校验配置边界。 */
    public ContextPreparationConfig {
        capacity = Objects.requireNonNull(capacity, "capacity 不能为空");
        if (largePayloadTokenThreshold <= 0 || protectedMessageCount < 0) {
            throw new IllegalArgumentException("Projection 边界非法");
        }
        new SummaryReductionPolicy(
                0, true, List.of(), List.of(), maxSummaryUtf8Bytes, maxSummaryTokens);
    }
}
