package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import java.util.List;

/**
 * 估算 Context Projection 的 Token 占用。
 *
 * <p>实现必须保持确定性，并明确标记精确或估算来源。该 Port 不访问 Provider、不修改消息，
 * 也不能把本地估算冒充真实模型 Usage。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ContextTokenEstimator {

    /**
     * 估算给定消息快照相对容量的分类 Usage。
     *
     * @param messages 不可变消息快照
     * @param capacity 请求容量
     * @return 分类 Token 占用
     */
    ContextUsage estimate(List<AgentMessage> messages, ContextCapacity capacity);
}
