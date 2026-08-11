package io.github.liumaishenjian.ccjava.domain.subagent;

/**
 * 子任务可观察状态；终态只允许首次 CAS 写入。
 *
 * @since 0.12.0
 */
public enum ChildTaskStatus {
    /** 已入公平有界队列。 */
    QUEUED,
    /** 已取得 worker，正在执行 start Gate。 */
    STARTING,
    /** 独立 child Runtime 正在运行。 */
    RUNNING,
    /** Runtime 明确成功。 */
    SUCCEEDED,
    /** Runtime 或控制面明确失败。 */
    FAILED,
    /** 父级或显式取消已收敛。 */
    CANCELLED,
    /** 恢复发现无 durable terminal，且绝不重放。 */
    INTERRUPTED_UNKNOWN;

    /**
     * 判断状态是否禁止再次执行模型或 Tool。
     *
     * @return 终态为 true
     */
    public boolean terminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == CANCELLED
                || this == INTERRUPTED_UNKNOWN;
    }
}
