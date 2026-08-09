package io.github.liumaishenjian.ccjava.domain.subagent;

/** 隐私安全的子任务失败分类，不携带异常或 Provider 原文。 @since 0.12.0 */
public enum ChildTaskFailureCode {
    NONE, DEFINITION_REJECTED, BUDGET_REJECTED, QUEUE_FULL, DEPTH_EXCEEDED,
    START_HOOK_BLOCKED, RUNTIME_FAILED, JOURNAL_FAILED, CANCELLED, TIMEOUT, INTERRUPTED_UNKNOWN
}
