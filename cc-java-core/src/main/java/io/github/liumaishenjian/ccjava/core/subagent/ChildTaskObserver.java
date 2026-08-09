package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;

/** 接收子任务隐私安全状态；慢或失败 observer 不得阻塞终态。 @since 0.12.0 */
@FunctionalInterface
public interface ChildTaskObserver {
    void onTerminal(ChildTaskReport report);
    static ChildTaskObserver noop() { return ignored -> { }; }
}
