package io.github.liumaishenjian.ccjava.domain.subagent;

/** 子任务可观察状态；终态只允许首次 CAS 写入。 @since 0.12.0 */
public enum ChildTaskStatus {
    QUEUED, STARTING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED_UNKNOWN;

    /** @return 当前状态是否禁止再次执行模型或 Tool */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED_UNKNOWN;
    }
}
