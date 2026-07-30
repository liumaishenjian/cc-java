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
 * @param cancellationToken 当前 Run 的取消信号；Tool 只能停止自身资源，不能发布 Run 终态
 * @param outputSink Tool 执行期间的有界输出观察端口
 * @since 0.1.0
 */
public record ToolInvocation(
        SessionId sessionId,
        RunId runId,
        int ordinal,
        ToolCall call,
        CancellationToken cancellationToken,
        ToolOutputSink outputSink) {

    /**
     * 创建不传播取消的调用上下文，供无需活动 Run 的确定性单元测试使用。
     *
     * @param sessionId 所属 Session
     * @param runId 所属 Run
     * @param ordinal 本次 Run 内从 1 开始的序号
     * @param call 原始 Tool Call
     */
    public ToolInvocation(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            ToolCall call) {
        this(
                sessionId,
                runId,
                ordinal,
                call,
                CancellationToken.none(),
                ToolOutputSink.none());
    }

    /**
     * 使用显式取消信号和无输出观察者创建调用。
     *
     * @param sessionId 所属 Session
     * @param runId 所属 Run
     * @param ordinal Tool Call 序号
     * @param call 原始 Tool Call
     * @param cancellationToken 当前 Run 取消信号
     */
    public ToolInvocation(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            ToolCall call,
            CancellationToken cancellationToken) {
        this(
                sessionId,
                runId,
                ordinal,
                call,
                cancellationToken,
                ToolOutputSink.none());
    }

    /**
     * 校验并创建一次 Tool 调用上下文。
     *
     * @param sessionId 所属 Session
     * @param runId     所属 Run
     * @param ordinal   本次 Run 内从 1 开始的序号
     * @param call      原始 Tool Call
     * @param cancellationToken 当前 Run 的取消信号
     * @param outputSink Tool 输出观察端口
     * @throws NullPointerException     任一引用参数为空时抛出
     * @throws IllegalArgumentException {@code ordinal} 小于 1 时抛出
     */
    public ToolInvocation {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        call = Objects.requireNonNull(call, "call 不能为空");
        cancellationToken = Objects.requireNonNull(
                cancellationToken, "cancellationToken 不能为空");
        outputSink = Objects.requireNonNull(outputSink, "outputSink 不能为空");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal 必须从 1 开始");
        }
    }
}
