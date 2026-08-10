package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;

/**
 * 仅对当前 Call ID、执行前有效的一次性 Local fallback 决定。
 *
 * @param callId 精确调用身份
 * @param allowed 是否显式批准
 * @param reasonCode 固定原因码
 * @since 0.13.0
 */
public record ExecutionFallbackDecision(String callId, boolean allowed, String reasonCode) {
    public ExecutionFallbackDecision {
        callId = Objects.requireNonNull(callId);
        reasonCode = Objects.requireNonNull(reasonCode);
    }

    /**
     * 构造当前调用的拒绝决定。
     *
     * @param callId 精确调用身份
     * @param reasonCode 固定拒绝原因码
     * @return 拒绝决定
     */
    public static ExecutionFallbackDecision deny(String callId, String reasonCode) {
        return new ExecutionFallbackDecision(callId, false, reasonCode);
    }
}
