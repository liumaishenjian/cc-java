package io.github.liumaishenjian.ccjava.domain;

/**
 * Runtime 向终端、测试或未来 SDK 发布的框架无关事件。
 *
 * <p>S01 只发布离散 Lifecycle Event。S02 的文本增量等外部事件将在保持
 * Core 同步控制流的前提下扩展该协议。</p>
 *
 * @since 0.1.0
 */
public sealed interface AgentEvent permits LifecycleEvent, ModelTextDelta, PlanProposalEvent, PlanReviewEvent {
}
