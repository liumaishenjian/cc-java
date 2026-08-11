package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;

/**
 * 接收子任务隐私安全终态；慢或失败 observer 不得阻塞终态。
 *
 * @since 0.12.0
 */
@FunctionalInterface
public interface ChildTaskObserver {
    /**
     * 接收 durable terminal 后的有界投影。
     *
     * @param report 子任务终态
     */
    void onTerminal(ChildTaskReport report);

    /**
     * 创建丢弃观察事件的默认实现。
     *
     * @return no-op observer
     */
    static ChildTaskObserver noop() {
        return ignored -> { };
    }
}
