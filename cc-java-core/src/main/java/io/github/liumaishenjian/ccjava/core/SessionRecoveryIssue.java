package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.Objects;
import java.util.Optional;

/**
 * 不含 Tool 参数、正文或绝对路径的 Session 恢复问题。
 *
 * @param kind 稳定问题分类
 * @param callId 相关 Tool Call ID；非 Tool 问题时为空
 * @param toolName 可信 Tool 名称；非 Tool 问题时为空
 * @param effect 可信 Tool Effect；未知或非 Tool 问题时为空
 * @since 0.6.0
 */
public record SessionRecoveryIssue(
        SessionRecoveryIssueKind kind,
        Optional<String> callId,
        Optional<String> toolName,
        Optional<ToolEffect> effect) {

    /** 防御性校验安全恢复摘要。 */
    public SessionRecoveryIssue {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        callId = Objects.requireNonNull(callId, "callId 不能为空");
        toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
        effect = Objects.requireNonNull(effect, "effect 不能为空");
    }

    /**
     * 创建不关联具体 Tool 的恢复问题。
     *
     * @param kind 问题分类
     * @return 安全问题摘要
     */
    public static SessionRecoveryIssue session(SessionRecoveryIssueKind kind) {
        return new SessionRecoveryIssue(kind, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
