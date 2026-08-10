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
    public ExecutionFailure {
        kind = Objects.requireNonNull(kind);
        reasonCode = Objects.requireNonNull(reasonCode);
    }

    /** 执行失败的稳定分类。 */
    public enum Kind {
        UNAVAILABLE,
        POLICY_UNSUPPORTED,
        START_FAILED,
        INTERNAL,
        CANCELLED,
        TIMED_OUT
    }
}
