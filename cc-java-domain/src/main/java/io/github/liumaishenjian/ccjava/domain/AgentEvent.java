package io.github.liumaishenjian.ccjava.domain;

/**
 * Runtime 向终端、测试或未来 SDK 发布的框架无关事件。
 *
 * <p>Lifecycle Event 表达离散状态边界；{@link ModelTextDelta} 表达 S02
 * 模型回合尚未聚合完成时的有序文本增量。两类事件都只是可观察事实，
 * 不能绕过 Runtime 改变 Agent 决策。</p>
 *
 * @since 0.1.0
 */
public sealed interface AgentEvent permits LifecycleEvent, ModelTextDelta {
}
