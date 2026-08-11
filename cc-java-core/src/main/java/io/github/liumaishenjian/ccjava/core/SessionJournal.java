package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;

/**
 * 在 Core 状态迁移与架构边缘的 durable Session journal 之间建立强一致语义边界。
 *
 * <p>该端口只接收已经聚合的规范消息与 Run 终态，不接收模型流 token、UI 事件或文件系统类型。
 * 实现抛出异常表示记录没有可靠落盘；调用方必须在可能执行副作用前 Fail Closed，而不能像普通
 * 遥测观察者一样忽略失败。</p>
 *
 * @since 0.6.0
 */
public interface SessionJournal {

    /**
     * 在内存状态改变前持久记录 Run 和对应 User Message。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param message 本次用户消息
     */
    void runStarted(SessionId sessionId, RunId runId, UserMessage message);

    /**
     * 在内存状态改变前持久记录聚合 Assistant 消息。
     *
     * <p>类型系统禁止 {@code ToolResultMessage} 进入该入口。Tool Result 必须与 resolved 或
     * completed 状态原子落盘，恢复时也只从对应 Tool record 重建。</p>
     *
     * @param sessionId Session ID
     * @param runId 当前 Run ID
     * @param message 聚合 Assistant 消息
     */
    void assistantAppended(SessionId sessionId, RunId runId, AssistantMessage message);

    /**
     * 原子持久记录 execute=0 的完整 Tool Result 与固定未执行原因。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param ordinal 当前 Run 内序号
     * @param result 完整有界 Tool Result
     * @param reason 固定未执行原因
     */
    void toolResolved(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            ToolResult result,
            ToolResolutionReason reason);

    /**
     * 在 Tool 真正执行前持久记录 started 边界。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param ordinal 当前 Run 内序号
     * @param callId Tool Call ID
     * @param toolName 可信 Tool 名称
     * @param effect 可信最高副作用等级
     */
    void toolStarted(
            SessionId sessionId,
            RunId runId,
            int ordinal,
            String callId,
            String toolName,
            ToolEffect effect);

    /**
     * 原子持久记录 Tool 完成状态及完整、已规范化且有界的 Tool Result。
     *
     * <p>该方法成功返回后，调用方才可把同一个 Result 追加到内存规范历史；失败表示执行结果
     * 不可恢复，必须留下 started 无 completed 并停止当前 Run。</p>
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param ordinal 当前 Run 内序号
     * @param result 完整有界 Tool Result
     */
    void toolCompleted(SessionId sessionId, RunId runId, int ordinal, ToolResult result);

    /**
     * 在 Skill Projection 提交到 Run scope 后持久记录隐私安全身份。
     *
     * <p>记录不含正文、资源文本、参数、物理路径或 Hook 输出；恢复只验证身份，不自动重放激活。</p>
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param kind 激活入口
     * @param record catalog/content 身份
     */
    default void skillInvoked(SessionId sessionId, RunId runId, SkillInvocationKind kind,
            SkillRecoveryRecord record) {
    }

    /**
     * 持久记录一次 Skill 激活尝试的唯一安全终态。
     *
     * @param sessionId 当前 Session ID
     * @param runId 当前 Run ID
     * @param skillId 规范 Skill ID
     * @param kind 激活入口
     * @param errorCode 失败时的固定分类；成功为空
     */
    default void skillCompleted(SessionId sessionId, RunId runId, SkillId skillId,
            SkillInvocationKind kind, SkillErrorCode errorCode) {
    }

    /**
     * 在 Run 释放活动状态前持久记录唯一终态。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param stopReason 唯一停止原因
     */
    void runCompleted(SessionId sessionId, RunId runId, StopReason stopReason);

    /**
     * 返回不执行任何 I/O 的实现。
     *
     * @return 共享 no-op journal
     */
    static SessionJournal noop() {
        return NoopHolder.INSTANCE;
    }

    /** 延迟持有共享实例，避免公开额外实现类型。 */
    final class NoopHolder {
        private static final SessionJournal INSTANCE = new SessionJournal() {
            @Override
            public void runStarted(
                    SessionId sessionId,
                    RunId runId,
                    UserMessage message) {
            }

            @Override
            public void assistantAppended(
                    SessionId sessionId,
                    RunId runId,
                    AssistantMessage message) {
            }

            @Override
            public void toolResolved(
                    SessionId sessionId,
                    RunId runId,
                    int ordinal,
                    ToolResult result,
                    ToolResolutionReason reason) {
            }

            @Override
            public void toolStarted(
                    SessionId sessionId,
                    RunId runId,
                    int ordinal,
                    String callId,
                    String toolName,
                    ToolEffect effect) {
            }

            @Override
            public void toolCompleted(
                    SessionId sessionId,
                    RunId runId,
                    int ordinal,
                    ToolResult result) {
            }

            @Override
            public void runCompleted(SessionId sessionId, RunId runId, StopReason stopReason) {
            }
        };

        private NoopHolder() {
        }
    }
}
