package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 在既有 Session 中启动一次 Agent Run 的输入。
 *
 * @param userMessage 本次 Run 新增的一条用户消息
 * @param limits      本次 Run 独立的确定性预算
 * @since 0.1.0
 */
public record AgentRunRequest(UserMessage userMessage, AgentLimits limits) {

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
