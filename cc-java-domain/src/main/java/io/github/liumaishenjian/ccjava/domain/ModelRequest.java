package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * Core 向模型适配器发起一个完整 Model Turn 的不可变请求。
 *
 * <p>消息列表是当前 Run 可见的规范 Context 快照，Tool Definitions 是本次
 * 请求允许模型选择的完整集合。Adapter 不得修改这些集合或在 Core 背后
 * 自动执行 Tool Loop。</p>
 *
 * @param sessionId      当前 Session
 * @param runId          当前 Run
 * @param turnNumber     从 1 开始的模型回合序号
 * @param messages       本回合可见的不可变消息快照
 * @param toolDefinitions 本回合可见的不可变 Tool 定义
 * @since 0.1.0
 */
public record ModelRequest(
        SessionId sessionId,
        RunId runId,
        int turnNumber,
        List<AgentMessage> messages,
        List<ToolDefinition> toolDefinitions) {

    /**
     * 校验并防御性复制上下文后创建模型请求。
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param turnNumber 从 1 开始的模型回合序号
     * @param messages 本回合可见的消息快照
     * @param toolDefinitions 本回合可见的 Tool 定义
     * @throws NullPointerException 必填引用、列表或列表元素为空时
     * @throws IllegalArgumentException 回合序号小于 1 时
     */
    public ModelRequest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber 必须从 1 开始");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        toolDefinitions = List.copyOf(
                Objects.requireNonNull(toolDefinitions, "toolDefinitions 不能为空"));
    }
}
