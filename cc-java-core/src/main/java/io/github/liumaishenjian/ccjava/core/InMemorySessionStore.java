package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以插入顺序保存 Session 的单进程 Store。
 *
 * <p>实现故意不加锁；S01 Runtime 是单线程顺序控制流。并发打开检测和
 * 跨进程恢复属于 S06/S14。</p>
 *
 * @since 0.1.0
 */
public final class InMemorySessionStore implements SessionStore {

    private final AgentIdGenerator idGenerator;
    private final LifecycleDispatcher lifecycle;
    private final HookCoordinator hooks;
    private final Map<SessionId, AgentSession> sessions = new LinkedHashMap<>();

    /**
     * 创建内存 Store。
     *
     * @param idGenerator Session ID 来源
     * @param lifecycle   Lifecycle 记录与发布器
     */
    public InMemorySessionStore(
            AgentIdGenerator idGenerator,
            LifecycleDispatcher lifecycle) {
        this(idGenerator, lifecycle, HookCoordinator.disabled());
    }

    /**
     * 创建可选接入 S09 Session Hook 的内存 Store。
     *
     * @param idGenerator Session ID 来源
     * @param lifecycle Lifecycle 记录与发布器
     * @param hooks Session Start/End Hook 协调器
     */
    public InMemorySessionStore(
            AgentIdGenerator idGenerator,
            LifecycleDispatcher lifecycle,
            HookCoordinator hooks) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
    }

    @Override
    public AgentSession create(SessionSpec spec) {
        Objects.requireNonNull(spec, "spec 不能为空");
        SessionId id = idGenerator.newSessionId();
        if (sessions.containsKey(id)) {
            throw new IllegalStateException("AgentIdGenerator 生成了重复 Session ID: " + id.value());
        }
        AgentSession session = new AgentSession(id, spec);
        sessions.put(id, session);
        lifecycle.dispatch(session, new LifecycleEvent.SessionStarted(spec));
        hooks.evaluate(
                new HookInvocation(
                        HookEventKind.SESSION_START,
                        id,
                        Optional.empty(),
                        id.value(),
                        new JsonObject(Map.of("sessionId", id.value()))),
                CancellationToken.none());
        return session;
    }

    @Override
    public Optional<AgentSession> find(SessionId id) {
        return Optional.ofNullable(sessions.get(Objects.requireNonNull(id, "id 不能为空")));
    }

    @Override
    public void close(SessionId id) {
        AgentSession session = find(id)
                .orElseThrow(() -> new IllegalArgumentException("Session 不存在: " + id.value()));
        session.close();
        lifecycle.dispatch(session, new LifecycleEvent.SessionEnded());
        hooks.evaluate(
                new HookInvocation(
                        HookEventKind.SESSION_END,
                        session.id(),
                        Optional.empty(),
                        session.id().value(),
                        new JsonObject(Map.of("sessionId", session.id().value()))),
                CancellationToken.none());
    }
}
