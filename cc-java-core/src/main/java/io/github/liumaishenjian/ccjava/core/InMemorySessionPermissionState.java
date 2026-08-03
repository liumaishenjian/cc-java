package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 线程安全的 S05 内存 Permission 状态。
 *
 * <p>Grant 按完整 selector 去重；拒绝计数按 Session 与 selector 分离。进程退出或显式
 * {@link #clear(SessionId)} 后不可恢复，符合 S06 持久化延期边界。</p>
 *
 * @since 0.5.0
 */
public final class InMemorySessionPermissionState implements SessionPermissionState {

    private final Map<SessionId, State> states = new HashMap<>();

    /** 创建空的进程内 Session Permission 状态。 */
    public InMemorySessionPermissionState() {
    }

    @Override
    public synchronized List<PermissionRule> rules(SessionId sessionId) {
        State state = states.get(requireSession(sessionId));
        return state == null ? List.of() : List.copyOf(state.rules);
    }

    @Override
    public synchronized void grant(SessionId sessionId, PermissionSelector selector) {
        requireSession(sessionId);
        PermissionRule rule = SessionPermissionState.sessionAllow(
                Objects.requireNonNull(selector, "selector 不能为空"));
        State state = states.computeIfAbsent(sessionId, ignored -> new State());
        if (!state.rules.contains(rule)) {
            state.rules.add(rule);
        }
        state.denials.remove(selector);
    }

    @Override
    public synchronized int recordDenial(
            SessionId sessionId,
            PermissionSelector selector) {
        State state = states.computeIfAbsent(requireSession(sessionId), ignored -> new State());
        return state.denials.merge(
                Objects.requireNonNull(selector, "selector 不能为空"),
                1,
                Integer::sum);
    }

    @Override
    public synchronized int recordDenialUpTo(
            SessionId sessionId,
            PermissionSelector selector,
            int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum 必须大于 0");
        }
        State state = states.computeIfAbsent(requireSession(sessionId), ignored -> new State());
        PermissionSelector checked = Objects.requireNonNull(selector, "selector 不能为空");
        int current = state.denials.getOrDefault(checked, 0);
        if (current >= maximum) {
            return current;
        }
        int next = current + 1;
        state.denials.put(checked, next);
        return next;
    }

    @Override
    public synchronized int denialCount(
            SessionId sessionId,
            PermissionSelector selector) {
        State state = states.get(requireSession(sessionId));
        return state == null
                ? 0
                : state.denials.getOrDefault(
                        Objects.requireNonNull(selector, "selector 不能为空"), 0);
    }

    @Override
    public synchronized void clearDenials(
            SessionId sessionId,
            PermissionSelector selector) {
        State state = states.get(requireSession(sessionId));
        if (state != null) {
            state.denials.remove(Objects.requireNonNull(selector, "selector 不能为空"));
        }
    }

    @Override
    public synchronized void clear(SessionId sessionId) {
        states.remove(requireSession(sessionId));
    }

    private static SessionId requireSession(SessionId sessionId) {
        return Objects.requireNonNull(sessionId, "sessionId 不能为空");
    }

    private static final class State {
        private final List<PermissionRule> rules = new ArrayList<>();
        private final Map<PermissionSelector, Integer> denials = new HashMap<>();
    }
}
