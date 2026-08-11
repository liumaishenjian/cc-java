package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;

/**
 * 不回显平台异常或命令内容的结构化执行失败。
 *
 * @param kind 失败种类
 * @param reasonCode 固定原因码
 * @param started 后端是否已经启动进程；true 时绝不允许 Local 重放
 * @since 0.13.0
 */
public record ExecutionFailure(Kind kind, String reasonCode, boolean started) {
    /** 校验固定失败分类与原因码。 */
    public ExecutionFailure {
        kind = Objects.requireNonNull(kind);
        reasonCode = Objects.requireNonNull(reasonCode);
    }

    /** 执行失败的稳定分类。 */
    public enum Kind {
        /** 后端或依赖不可用。 */ UNAVAILABLE,
        /** 后端无法满足策略维度。 */ POLICY_UNSUPPORTED,
        /** 进程未成功启动。 */ START_FAILED,
        /** 后端内部不变量失败。 */ INTERNAL,
        /** 取消已传播并清理。 */ CANCELLED,
        /** Deadline 到期并清理。 */ TIMED_OUT
    }
}
