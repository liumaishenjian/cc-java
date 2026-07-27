package io.github.liumaishenjian.ccjava.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 为 Agent Event 补充稳定顺序、时间和关联标识。
 *
 * <p>{@code sequence} 在单个 Session 中严格递增。Session 级事件没有
 * Run ID；Run 级事件必须携带 Run ID。</p>
 *
 * @param sequence   Session 内从 1 开始的事件序号
 * @param occurredAt 事件产生时间
 * @param sessionId  所属 Session
 * @param runId      Run 级事件的关联 ID
 * @param event      事件正文
 * @since 0.1.0
 */
public record AgentEventEnvelope(
        long sequence,
        Instant occurredAt,
        SessionId sessionId,
        Optional<RunId> runId,
        AgentEvent event) {

    /**
     * 校验事件顺序和 Session/Run 关联后创建事件信封。
     *
     * @param sequence Session 内从 1 开始的事件序号
     * @param occurredAt 事件产生时间
     * @param sessionId 所属 Session
     * @param runId Run 级事件的关联 ID
     * @param event 事件正文
     * @throws NullPointerException 时间、Session、Run ID 容器或事件为空时
     * @throws IllegalArgumentException 序号无效或事件级别与 Run ID 不匹配时
     */
    public AgentEventEnvelope {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence 必须从 1 开始");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        event = Objects.requireNonNull(event, "event 不能为空");
        boolean sessionLevel = event instanceof LifecycleEvent.SessionStarted
                || event instanceof LifecycleEvent.SessionEnded;
        if (sessionLevel && runId.isPresent()) {
            throw new IllegalArgumentException("Session 级事件不能携带 runId");
        }
        if (!sessionLevel && runId.isEmpty()) {
            throw new IllegalArgumentException("Run 级事件必须携带 runId");
        }
    }
}
