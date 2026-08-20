package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import java.util.Objects;

/**
 * 为架构边缘的 {@link SessionStore} 实现提供受控的 Session 生命周期操作。
 *
 * <p>{@link AgentSession} 的普通状态修改仍保持包内可见，文件 Adapter 只能通过这里关闭
 * 已经由 Store 持有的 Session，并同步发布规范生命周期事件。该类型不提供消息注入、
 * fence 清除或活动 Run 修改能力。</p>
 *
 * @since 0.6.0
 */
public final class SessionStoreAccess {

    private SessionStoreAccess() {
    }

    /**
     * 关闭没有活动 Run 的 Session，并发布 {@link LifecycleEvent.SessionEnded}。
     *
     * @param session Store 当前持有的 Session
     * @param lifecycle 生命周期分发器
     */
    public static void closeSession(
            AgentSession session,
            LifecycleDispatcher lifecycle) {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        session.close();
        lifecycle.dispatch(session, new LifecycleEvent.SessionEnded());
    }

    /**
     * 关闭只读或已 fenced 的恢复投影，不发布可持久语义事件。
     *
     * <p>该操作仅释放 Adapter 内存所有权；不能清除 fence，也不能把 Inspect 投影变成
     * 可写 Session。</p>
     *
     * @param session Store 当前持有的恢复投影
     */
    public static void discardRecoveredSession(AgentSession session) {
        AgentSession checked = Objects.requireNonNull(session, "session 不能为空");
        if (!checked.isFenced()) {
            throw new IllegalArgumentException("只能丢弃 fenced 的恢复投影");
        }
        checked.closeRecoveredProjection();
    }

    /**
     * 在架构边缘 durable 写入不确定后永久 fence 当前内存 Session。
     *
     * @param session Store 当前持有的 Session
     */
    public static void fenceSession(AgentSession session) {
        Objects.requireNonNull(session, "session 不能为空").fence();
    }

    /**
     * 判断 Store 当前持有的 Session 是否存在活动 Run。
     *
     * <p>该只读查询只供持久化 Adapter 实施 Undo 等互斥 Gate；它不允许 Adapter 修改
     * Run 状态，也不能清除 fence。</p>
     *
     * @param session Store 当前持有的 Session
     * @return 活动 Run 尚未结束时为 {@code true}
     */
    public static boolean hasActiveRun(AgentSession session) {
        return Objects.requireNonNull(session, "session 不能为空").hasActiveRun();
    }
}
