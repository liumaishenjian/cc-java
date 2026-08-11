package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;

/**
 * 文件 Adapter 完整校验 journal 后交给 Core 的框架无关恢复快照。
 *
 * <p>快照只包含可安全进入下一模型回合的规范消息。活动 Run、未完成 Tool 和损坏警告由打开
 * 结果单独表达，不能通过伪造规范 Assistant/Tool Result 消息隐藏。</p>
 *
 * @param sessionId 被恢复的 Session ID
 * @param spec Session 创建配置
 * @param messages 已验证的完整规范消息链
 * @param runIds 历史 Run ID，用于阻止恢复后重复
 * @param parentSessionId Fork 来源
 * @param issues 阻止无条件继续写入的安全恢复问题
 * @param skillRecords 历史成功激活的 privacy-safe Skill 身份；只用于验证，不自动重放
 * @since 0.6.0
 */
public record SessionRecoverySnapshot(
        SessionId sessionId,
        SessionSpec spec,
        List<AgentMessage> messages,
        List<RunId> runIds,
        Optional<SessionId> parentSessionId,
        List<SessionRecoveryIssue> issues,
        List<SkillRecoveryRecord> skillRecords) {

    /**
     * 防御性复制恢复数据。
     */
    public SessionRecoverySnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        spec = Objects.requireNonNull(spec, "spec 不能为空");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        runIds = List.copyOf(Objects.requireNonNull(runIds, "runIds 不能为空"));
        parentSessionId = Objects.requireNonNull(parentSessionId, "parentSessionId 不能为空");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues 不能为空"));
        skillRecords = List.copyOf(Objects.requireNonNull(skillRecords, "skillRecords 不能为空"));
    }

    /**
     * 兼容不含 S11 Skill 记录的既有构造路径。
     *
     * @param sessionId 被恢复的 Session ID
     * @param spec Session 创建配置
     * @param messages 已验证的完整规范消息链
     * @param runIds 历史 Run ID
     * @param parentSessionId Fork 来源
     * @param issues 安全恢复问题
     */
    public SessionRecoverySnapshot(SessionId sessionId, SessionSpec spec, List<AgentMessage> messages,
            List<RunId> runIds, Optional<SessionId> parentSessionId, List<SessionRecoveryIssue> issues) {
        this(sessionId, spec, messages, runIds, parentSessionId, issues, List.of());
    }
}
