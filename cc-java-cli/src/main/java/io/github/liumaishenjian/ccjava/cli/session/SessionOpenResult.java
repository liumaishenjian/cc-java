package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 持久 Session 打开后的安全结果与 Writer lease 所有权。
 *
 * @param session 已创建或恢复的 Core Session
 * @param mode 实际打开模式
 * @param parentSessionId Fork 来源
 * @param readOnly 是否仅允许 Inspect
 * @param issues 恢复问题
 * @param skillRecords 历史 Skill 身份，只用于当前 catalog 验证
 * @since 0.6.0
 */
public record SessionOpenResult(
        AgentSession session,
        SessionOpenMode mode,
        Optional<SessionId> parentSessionId,
        boolean readOnly,
        List<SessionRecoveryIssue> issues,
        List<SkillRecoveryRecord> skillRecords) {

    /** 防御性复制打开结果。 */
    public SessionOpenResult {
        session = Objects.requireNonNull(session, "session 不能为空");
        mode = Objects.requireNonNull(mode, "mode 不能为空");
        parentSessionId = Objects.requireNonNull(parentSessionId, "parentSessionId 不能为空");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues 不能为空"));
        skillRecords = List.copyOf(Objects.requireNonNull(skillRecords, "skillRecords 不能为空"));
    }

    /**
     * 兼容没有 S11 Skill 恢复身份的既有创建路径。
     *
     * @param session 已创建或恢复的 Core Session
     * @param mode 实际打开模式
     * @param parentSessionId Fork 来源
     * @param readOnly 是否仅允许 Inspect
     * @param issues 恢复问题
     */
    public SessionOpenResult(AgentSession session, SessionOpenMode mode, Optional<SessionId> parentSessionId,
            boolean readOnly, List<SessionRecoveryIssue> issues) {
        this(session, mode, parentSessionId, readOnly, issues, List.of());
    }
}
