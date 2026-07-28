package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;

/**
 * 接收 CLI 需要展示的 Runtime 语义事件。
 *
 * <p>S02 文本增量已经是 {@code ModelTextDelta}，与 Lifecycle Event 一样通过
 * {@link AgentEventEnvelope} 发布。CLI 不再维护第二套取消或流式协议，也不会把
 * Reactor、JLine 等适配器类型带回 Core。</p>
 *
 * @since 0.1.0
 */
public interface CliEventListener {

    /**
     * 接收已经关联 Session/Run 的语义事件。
     *
     * @param envelope Runtime 事件信封
     */
    void onAgentEvent(AgentEventEnvelope envelope);
}
