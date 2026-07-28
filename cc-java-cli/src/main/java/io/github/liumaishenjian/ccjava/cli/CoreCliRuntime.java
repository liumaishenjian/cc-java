package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.InMemorySessionStore;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 CLI 的连续会话语义适配到一个显式 {@link AgentRuntime}。
 *
 * <p>实例创建时只建立一个内存 Session；Interactive 中后续每条输入都复用该
 * Session，因此规范消息历史不会在 Run 之间丢失。每次 Run 仍拥有独立的取消
 * Token 与预算。关闭只负责进程内 Session 和可关闭 Provider 资源。</p>
 *
 * @since 0.1.0
 */
public final class CoreCliRuntime implements CliRuntime {

    private final AgentRuntime runtime;
    private final InMemorySessionStore sessionStore;
    private final SessionId sessionId;
    private final AgentLimits limits;
    private final Object providerResource;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建 Core CLI 适配器。
     *
     * @param runtime Agent Loop
     * @param sessionStore 拥有 Session 的进程内 Store
     * @param sessionId 连续会话 ID
     * @param limits 每次 Run 使用的独立预算
     * @param providerResource 可能实现 {@link AutoCloseable} 的 Provider 资源
     */
    public CoreCliRuntime(
            AgentRuntime runtime,
            InMemorySessionStore sessionStore,
            SessionId sessionId,
            AgentLimits limits,
            Object providerResource) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
        this.providerResource = Objects.requireNonNull(
                providerResource,
                "providerResource 不能为空");
    }

    @Override
    public SessionId sessionId() {
        return sessionId;
    }

    @Override
    public AgentRunResult run(
            String userMessage,
            CancellationToken cancellationToken) {
        if (closed.get()) {
            throw new IllegalStateException("CLI Runtime 已关闭");
        }
        Objects.requireNonNull(userMessage, "userMessage 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        return runtime.run(
                sessionId,
                new AgentRunRequest(new UserMessage(userMessage), limits),
                cancellationToken);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sessionStore.close(sessionId);
        if (providerResource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // CLI 已经到达关闭边界；不得用 Provider 清理异常覆盖确定的 Run 结果。
            }
        }
    }
}
