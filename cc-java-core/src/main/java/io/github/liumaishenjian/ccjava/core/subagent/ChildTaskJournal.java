package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;

/**
 * 子任务 requested/started/terminal 聚合状态持久端口。
 *
 * <p>恢复时任何没有 terminal 的记录都只能标记 INTERRUPTED_UNKNOWN，绝不重放。</p>
 *
 * @since 0.12.0
 */
public interface ChildTaskJournal {
    /**
     * 记录已接受且完成预算预留的任务。
     *
     * @param id 任务 identity
     */
    void requested(ChildTaskId id);

    /**
     * 记录已取得 worker 且即将物化 scope 的任务。
     *
     * @param id 任务 identity
     */
    void started(ChildTaskId id);

    /**
     * 记录任务唯一终态。
     *
     * @param report 隐私安全终态
     */
    void terminal(ChildTaskReport report);

    /**
     * 主 terminal append 失败后的独立 fail-closed 标记通道。
     *
     * @param report 无法写入主 journal 的终态
     */
    default void terminalFailure(ChildTaskReport report) {
    }

    /**
     * 创建不持久化任务状态的安全默认实现。
     *
     * @return no-op journal
     */
    static ChildTaskJournal noop() {
        return new ChildTaskJournal() {
            public void requested(ChildTaskId id) { }
            public void started(ChildTaskId id) { }
            public void terminal(ChildTaskReport report) { }
        };
    }
}
