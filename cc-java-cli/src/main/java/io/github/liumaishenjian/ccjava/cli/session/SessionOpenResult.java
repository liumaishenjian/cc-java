package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.SessionRecoveryIssue;
import io.github.liumaishenjian.ccjava.domain.SessionId;
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
 * @since 0.6.0
 */
public record SessionOpenResult(
        AgentSession session,
        SessionOpenMode mode,
        Optional<SessionId> parentSessionId,
        boolean readOnly,
        List<SessionRecoveryIssue> issues) {

    /** 防御性复制打开结果。 */
    public SessionOpenResult {
        session = Objects.requireNonNull(session, "session 不能为空");
        mode = Objects.requireNonNull(mode, "mode 不能为空");
        parentSessionId = Objects.requireNonNull(parentSessionId, "parentSessionId 不能为空");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues 不能为空"));
    }
}
