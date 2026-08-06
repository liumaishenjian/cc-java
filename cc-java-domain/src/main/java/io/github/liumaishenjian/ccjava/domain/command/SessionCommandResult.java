package io.github.liumaishenjian.ccjava.domain.command;

import java.util.Objects;

/**
 * 一次命令分派的唯一终态。
 *
 * <p>结果只包装一条终态 event，调用方不应再根据异常或外部状态推测第二个终态。</p>
 *
 * @since 0.8.0
 */
public sealed interface SessionCommandResult permits SessionCommandResult.Succeeded, SessionCommandResult.Rejected,
        SessionCommandResult.Cancelled, SessionCommandResult.Failed {
    /**
     * 返回本结果唯一对应的终态事件。
     *
     * @return 与结果子类型状态一致的事件
     */
    SessionCommandEvent event();

    /**
     * 成功完成的命令。
     *
     * @param event 唯一成功终态事件
     */
    record Succeeded(SessionCommandEvent event) implements SessionCommandResult {
        /**
         * 验证成功终态事件。
         *
         * @param event 唯一成功事件
         */
        public Succeeded { require(event, SessionCommandStatus.SUCCEEDED); }
    }

    /**
     * 因契约或状态 Gate 被拒绝的命令。
     *
     * @param event 唯一拒绝终态事件
     */
    record Rejected(SessionCommandEvent event) implements SessionCommandResult {
        /**
         * 验证拒绝终态事件。
         *
         * @param event 唯一拒绝事件
         */
        public Rejected { require(event, SessionCommandStatus.REJECTED); }
    }

    /**
     * 在可变状态提交前观察到取消的命令。
     *
     * @param event 唯一取消终态事件
     */
    record Cancelled(SessionCommandEvent event) implements SessionCommandResult {
        /**
         * 验证取消终态事件。
         *
         * @param event 唯一取消事件
         */
        public Cancelled { require(event, SessionCommandStatus.CANCELLED); }
    }

    /**
     * 未分类内部失败的安全终态。
     *
     * @param event 唯一失败终态事件
     */
    record Failed(SessionCommandEvent event) implements SessionCommandResult {
        /**
         * 验证失败终态事件。
         *
         * @param event 唯一失败事件
         */
        public Failed { require(event, SessionCommandStatus.FAILED); }
    }

    private static void require(SessionCommandEvent event, SessionCommandStatus status) {
        event = Objects.requireNonNull(event, "event 不能为空");
        if (event.status() != status) throw new IllegalArgumentException("result 与 event 状态不匹配");
    }
}
