package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 在无 Tool Call 的最终 Assistant 回合形成 Run 终态前执行确定性验证或投影。
 *
 * <p>默认 Agent Run 使用 {@link #acceptAll()}。Plan Headless Runtime 可在这一线性化点严格验证
 * 结构化提案并发布 Session-owned 状态；处理器不能调用模型、执行 Tool 或建立第二份 transcript。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface FinalAssistantHandler {
    /**
     * 处理即将完成的最终 Assistant Message。
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param assistant 已聚合且不含 Tool Call 的消息
     * @return 接受时完成 Run；拒绝时以 INVALID_MODEL_RESPONSE 停止
     */
    boolean handle(SessionId sessionId, RunId runId, AssistantMessage assistant);

    /** 返回保持历史 Agent Runtime 语义的兼容处理器。 */
    static FinalAssistantHandler acceptAll() {
        return (ignoredSession, ignoredRun, ignoredAssistant) -> true;
    }
}
