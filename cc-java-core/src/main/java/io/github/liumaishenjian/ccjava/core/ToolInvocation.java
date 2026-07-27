package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import java.util.Objects;

/**
 * Pipeline 传给 Permission 和 Tool 实现的单次调用上下文。
 *
 * @param sessionId 所属 Session
 * @param runId     所属 Run
 * @param ordinal   本次 Run 内从 1 开始的 Tool Call 序号
 * @param call      模型产生的原始 Tool Call
 * @since 0.1.0
 */
public record ToolInvocation(
        SessionId sessionId,
        RunId runId,
        int ordinal,
        ToolCall call) {

    /**
     * 校验并创建一次 Tool 调用上下文。
     *
     * @param sessionId 所属 Session
     * @param runId     所属 Run
     * @param ordinal   本次 Run 内从 1 开始的序号
     * @param call      原始 Tool Call
     * @throws NullPointerException     任一引用参数为空时抛出
     * @throws IllegalArgumentException {@code ordinal} 小于 1 时抛出
     */
    public ToolInvocation {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        call = Objects.requireNonNull(call, "call 不能为空");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal 必须从 1 开始");
        }
    }
}
