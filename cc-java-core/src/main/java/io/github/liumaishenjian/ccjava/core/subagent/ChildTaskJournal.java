package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;

/**
 * 子任务 requested/started/terminal 聚合状态持久端口。
 *
 * <p>恢复时任何没有 terminal 的记录都只能标记 INTERRUPTED_UNKNOWN，绝不重放。</p>
 * @since 0.12.0
 */
public interface ChildTaskJournal {
    void requested(ChildTaskId id);
    void started(ChildTaskId id);
    void terminal(ChildTaskReport report);

    /**
     * 主 terminal append 失败后的独立 fail-closed 标记通道。
     * 实现应使用与主 append 不同的原子写路径，使恢复不会把失败任务误判为可重放。
     */
    default void terminalFailure(ChildTaskReport report) { }

    static ChildTaskJournal noop() { return new ChildTaskJournal() {
        public void requested(ChildTaskId id) { }
        public void started(ChildTaskId id) { }
        public void terminal(ChildTaskReport report) { }
    }; }
}
