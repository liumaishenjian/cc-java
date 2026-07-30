package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEvent;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.RunId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 Lifecycle Event 记录进 Session，并按相同顺序发布给外部 Sink。
 *
 * <p>Session 内记录是规范事实，外部 Sink 只是可替换观察者。Sink 抛出的
 * Runtime 异常会被隔离，避免 UI 或遥测故障改变 Agent Loop 控制流。</p>
 *
 * @since 0.1.0
 */
public final class LifecycleDispatcher {

    private final Clock clock;
    private final AgentEventSink sink;

    /**
     * 创建生命周期事件分发器。
     *
     * @param clock 事件时间来源
     * @param sink  外部事件观察者
     */
    public LifecycleDispatcher(Clock clock, AgentEventSink sink) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.sink = Objects.requireNonNull(sink, "sink 不能为空");
    }

    /**
     * 发布 Session 级事件。
     *
     * @param session 事件所属 Session
     * @param event   事件正文
     */
    public void dispatch(AgentSession session, AgentEvent event) {
        dispatch(session, Optional.empty(), event);
    }

    /**
     * 发布 Run 级事件。
     *
     * @param session 事件所属 Session
     * @param runId   事件所属 Run
     * @param event   事件正文
     */
    public void dispatch(AgentSession session, RunId runId, AgentEvent event) {
        dispatch(session, Optional.of(Objects.requireNonNull(runId, "runId 不能为空")), event);
    }

    private void dispatch(
            AgentSession session,
            Optional<RunId> runId,
            AgentEvent event) {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(event, "event 不能为空");
        synchronized (session) {
            Instant occurredAt = clock.instant();
            AgentEventEnvelope envelope = session.recordEvent(occurredAt, runId, event);
            try {
                sink.publish(envelope);
            } catch (RuntimeException ignored) {
                // 事件已进入规范 Session；S14 再为观察者故障增加诊断通道。
            }
        }
    }
}
