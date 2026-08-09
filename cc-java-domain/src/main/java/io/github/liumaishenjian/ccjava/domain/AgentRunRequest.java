package io.github.liumaishenjian.ccjava.domain;

import io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation;
import java.util.Objects;
import java.util.Optional;

/**
 * 在既有 Session 中启动一次 Agent Run 的输入。
 *
 * @param userMessage 本次 Run 新增的一条用户消息
 * @param limits      本次 Run 独立的确定性预算
 * @param explicitSkill Surface 已解析的可选显式 Skill；不进入 Canonical User Message
 * @since 0.1.0
 */
public record AgentRunRequest(UserMessage userMessage, AgentLimits limits,
                              Optional<ExplicitSkillInvocation> explicitSkill) {

    /** 使用无显式 Skill 的兼容构造器。 */
    public AgentRunRequest(UserMessage userMessage, AgentLimits limits) {
        this(userMessage, limits, Optional.empty());
    }

    /**
     * 校验请求内容后创建一次 Run 的输入。
     *
     * @param userMessage 本次 Run 新增的用户消息
     * @param limits 本次 Run 的确定性预算
     * @throws NullPointerException 用户消息或预算为空时
     */
    public AgentRunRequest {
        userMessage = Objects.requireNonNull(userMessage, "userMessage 不能为空");
        limits = Objects.requireNonNull(limits, "limits 不能为空");
        explicitSkill = Objects.requireNonNull(explicitSkill, "explicitSkill 不能为空");
    }

    /**
     * 使用默认限制创建请求。
     *
     * @param content 用户消息正文
     * @return 使用 {@link AgentLimits#DEFAULT} 的请求
     */
    public static AgentRunRequest of(String content) {
        return new AgentRunRequest(new UserMessage(content), AgentLimits.DEFAULT);
    }
}
