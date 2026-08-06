package io.github.liumaishenjian.ccjava.domain.command;

/**
 * 命令状态机的终态分类。
 *
 * @since 0.8.0
 */
public enum SessionCommandStatus {
    /** 命令按契约成功完成。 */
    SUCCEEDED,
    /** 命令因状态或安全 Gate 被拒绝。 */
    REJECTED,
    /** 命令在执行前已被取消。 */
    CANCELLED,
    /** 命令遇到未分类的内部故障。 */
    FAILED
}
