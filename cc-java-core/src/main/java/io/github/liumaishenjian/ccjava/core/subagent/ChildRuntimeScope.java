package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Objects;

/**
 * 一次子任务独占的 Runtime/Session/资源组合。
 *
 * <p>实现必须创建新的 Session、Context、Permission state、Tool Registry、Budget 和取消所有权；
 * {@link AgentRuntime} 仍是唯一模型/Tool Loop。</p>
 * @param runtime 重用的核心 Runtime 类型
 * @param sessionId 独立子 Session
 * @param cleanup 逆序幂等资源清理
 * @param worktreeDisposition 清理后可选的 Worktree 保留/移除诊断
 * @since 0.12.0
 */
public record ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup,
        java.util.function.Supplier<java.util.Optional<String>> worktreeDisposition) implements AutoCloseable {
    /**
     * 创建不含 Worktree disposition 的兼容 scope。
     *
     * @param runtime 独立装配的 Runtime
     * @param sessionId 独立子 Session
     * @param cleanup 幂等资源清理
     */
    public ChildRuntimeScope(AgentRuntime runtime, SessionId sessionId, AutoCloseable cleanup) {
        this(runtime, sessionId, cleanup, java.util.Optional::empty);
    }
    /** 校验 scope 所有权资源均已装配。 */
    public ChildRuntimeScope {
        Objects.requireNonNull(runtime); Objects.requireNonNull(sessionId); Objects.requireNonNull(cleanup);
        Objects.requireNonNull(worktreeDisposition);
    }
    @Override public void close() { try { cleanup.close(); } catch (Exception ignored) { } }
}
