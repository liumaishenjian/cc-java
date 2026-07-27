package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;

/**
 * 消费 Runtime 已记录的有序 Agent Event。
 *
 * <p>Sink 只负责观察，不得改变 Agent 决策。实现不应抛出异常；
 * {@link LifecycleDispatcher} 会隔离观察者故障，保证终端渲染或遥测失败
 * 不改变规范消息历史。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface AgentEventSink {

    /**
     * 消费一个已经获得 Session 顺序号的事件。
     *
     * @param envelope 事件信封
     */
    void publish(AgentEventEnvelope envelope);

    /**
     * 返回忽略所有事件的 Sink。
     *
     * @return 无副作用 Sink
     */
    static AgentEventSink noop() {
        return ignored -> {
        };
    }
}
