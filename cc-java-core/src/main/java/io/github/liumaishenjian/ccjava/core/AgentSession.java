package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEvent;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 保存当前进程内 Session 的规范消息历史和有序事件。
 *
 * <p>该类型不提供持久化、锁或崩溃恢复。所有修改方法保持包内可见，
 * 外部只能读取不可变快照；规范写入由 {@link AgentRuntime}、
 * {@link ToolExecutionPipeline} 和 {@link LifecycleDispatcher} 协作完成。</p>
 *
 * @since 0.1.0
 */
public final class AgentSession {

    private final SessionId id;
    private final SessionSpec spec;
    private final List<AgentMessage> messages = new ArrayList<>();
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final Map<String, String> toolNamesByCallId = new HashMap<>();
    private final Set<String> toolResultIds = new HashSet<>();
    private final Set<RunId> runIds = new HashSet<>();
    private long nextEventSequence = 1;
    private RunId activeRunId;
    private boolean closed;

    AgentSession(SessionId id, SessionSpec spec) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.spec = Objects.requireNonNull(spec, "spec 不能为空");
    }

    /**
     * 返回 Session ID。
     *
     * @return 稳定标识
     */
    public SessionId id() {
        return id;
    }

    /**
     * 返回创建 Session 时的不可变配置。
     *
     * @return Session 配置
     */
    public SessionSpec spec() {
        return spec;
    }

    /**
     * 返回当前规范消息历史快照。
     *
     * @return 与真实追加顺序一致的不可变列表
     */
    public List<AgentMessage> messages() {
        return List.copyOf(messages);
    }

    /**
     * 返回 Session 中已经记录的事件快照。
     *
     * @return 按 sequence 排序的不可变列表
     */
    public List<AgentEventEnvelope> events() {
        return List.copyOf(events);
    }

    /**
     * 判断 Session 是否已显式关闭。
     *
     * @return 关闭后返回 {@code true}
     */
    public boolean isClosed() {
        return closed;
    }

    void beginRun(RunId runId, UserMessage userMessage) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(userMessage, "userMessage 不能为空");
        if (activeRunId != null) {
            throw new IllegalStateException("Session 已有正在执行的 Run: " + activeRunId.value());
        }
        if (!runIds.add(runId)) {
            throw new IllegalStateException("Session 中出现重复 Run ID: " + runId.value());
        }
        activeRunId = runId;
        messages.add(userMessage);
    }

    void appendAssistant(AssistantMessage message) {
        ensureActiveRun();
        Objects.requireNonNull(message, "message 不能为空");
        HashSet<String> batchIds = new HashSet<>();
        for (ToolCall call : message.toolCalls()) {
            if (!batchIds.add(call.id())) {
                throw new IllegalArgumentException("同一模型回合包含重复 Tool Call ID: " + call.id());
            }
            if (toolNamesByCallId.containsKey(call.id())) {
                throw new IllegalArgumentException("Session 历史包含重复 Tool Call ID: " + call.id());
            }
        }
        message.toolCalls().forEach(call -> toolNamesByCallId.put(call.id(), call.name()));
        messages.add(message);
    }

    void appendToolResult(ToolResultMessage message) {
        ensureActiveRun();
        Objects.requireNonNull(message, "message 不能为空");
        String expectedToolName = toolNamesByCallId.get(message.result().callId());
        if (expectedToolName == null) {
            throw new IllegalArgumentException(
                    "Tool Result 没有对应的 Tool Call ID: " + message.result().callId());
        }
        if (!expectedToolName.equals(message.result().toolName())) {
            throw new IllegalArgumentException(
                    "Tool Result 名称与原始 Tool Call 不一致，期望 "
                            + expectedToolName
                            + "，实际 "
                            + message.result().toolName());
        }
        if (!toolResultIds.add(message.result().callId())) {
            throw new IllegalArgumentException(
                    "同一 Tool Call 不能追加多个 Result: " + message.result().callId());
        }
        messages.add(message);
    }

    void endRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (!runId.equals(activeRunId)) {
            throw new IllegalStateException("尝试结束非当前 Run: " + runId.value());
        }
        activeRunId = null;
    }

    void close() {
        ensureOpen();
        if (activeRunId != null) {
            throw new IllegalStateException("存在活动 Run 时不能关闭 Session");
        }
        closed = true;
    }

    AgentEventEnvelope recordEvent(
            Instant occurredAt,
            Optional<RunId> runId,
            AgentEvent event) {
        AgentEventEnvelope envelope = new AgentEventEnvelope(
                nextEventSequence++,
                occurredAt,
                id,
                runId,
                event);
        events.add(envelope);
        return envelope;
    }

    boolean hasToolCallId(String callId) {
        return toolNamesByCallId.containsKey(callId);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Session 已关闭: " + id.value());
        }
    }

    private void ensureActiveRun() {
        ensureOpen();
        if (activeRunId == null) {
            throw new IllegalStateException("当前没有活动 Run");
        }
    }
}
