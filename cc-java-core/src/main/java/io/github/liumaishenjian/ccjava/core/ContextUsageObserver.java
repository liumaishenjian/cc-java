package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import java.util.Objects;

/**
 * 接收 Context Preparation 旁路使用量快照的观察端口。
 *
 * <p>观察者不属于 AgentRuntime 的权威执行路径。调用方必须隔离实现抛出的异常，且不得将 View
 * 持久化进 Canonical Session 或 Journal。实现只能消费 {@link ContextUsageView} 的安全数值化字段。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ContextUsageObserver {

    /** 接收一个已完成准备或 overflow recovery 的安全快照。 */
    void publish(ContextUsageView view);

    /** 返回不保留也不发布 Usage View 的兼容观察者。 */
    static ContextUsageObserver noop() {
        return view -> Objects.requireNonNull(view, "view 不能为空");
    }
}
