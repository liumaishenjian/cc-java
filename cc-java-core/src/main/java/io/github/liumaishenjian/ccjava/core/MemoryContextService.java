package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.MemoryContextMessage;
import io.github.liumaishenjian.ccjava.domain.MemoryProjection;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 在 Agent Runtime 中管理单回合 ready-only 文件记忆 Projection。
 *
 * <p>启用实例只负责启动 Port、唯一调用 {@link MemoryPrefetch#consumeReady()}，以及把非空结果插入
 * 当前 UserMessage 之前；失败、取消和未完成都降级为无记忆请求。服务不保存跨回合状态、不访问文件
 * 系统、不拥有 Executor，也不修改 Session、Journal、Tool Definitions 或 Permission 管线。</p>
 *
 * @since 0.7.0
 */
public final class MemoryContextService {

    private final MemoryPrefetchFactory factory;
    private final boolean enabled;

    /** 创建启用 ready-only Memory Projection 的服务。 */
    public MemoryContextService(MemoryPrefetchFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory 不能为空");
        this.enabled = true;
    }

    private MemoryContextService() {
        this.factory = null;
        this.enabled = false;
    }

    /** 返回不创建资源且保持请求原样的兼容路径。 */
    public static MemoryContextService noop() {
        return new MemoryContextService();
    }

    /**
     * 在任何非记忆 ContextAssembler 工作前启动一个回合级预取。
     *
     * @param currentUserMessage 当前 Run 的有界用户消息
     * @param cancellationToken Run 取消令牌
     * @return 本回合句柄；no-op 或启动失败时为空句柄
     */
    public TurnPrefetch start(
            UserMessage currentUserMessage,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(currentUserMessage, "currentUserMessage 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (!enabled || cancellationToken.isCancellationRequested()) {
            return TurnPrefetch.empty();
        }
        try {
            return TurnPrefetch.of(factory.start(currentUserMessage, cancellationToken));
        } catch (RuntimeException failure) {
            return TurnPrefetch.empty();
        }
    }

    /**
     * 在 Context Projection/Gateway 前的唯一消费点立即合并 ready 结果。
     *
     * <p>本方法无论结果如何都会把句柄标记为已消费；迟到完成不能再修改返回请求。只在当前
     * UserMessage 的同一对象身份仍存在时插入，防止把召回结果附着到错误历史消息。</p>
     *
     * @param canonical ContextAssembler 产生的规范请求快照
     * @param currentUserMessage 启动召回时使用的当前用户消息
     * @param prefetch 本回合句柄
     * @return 原请求或含一个短生命周期 Memory Context 消息的新请求
     */
    public ModelRequest consumeReady(
            ModelRequest canonical,
            UserMessage currentUserMessage,
            TurnPrefetch prefetch) {
        Objects.requireNonNull(canonical, "canonical 不能为空");
        Objects.requireNonNull(currentUserMessage, "currentUserMessage 不能为空");
        Objects.requireNonNull(prefetch, "prefetch 不能为空");
        MemoryProjection projection;
        try {
            projection = prefetch.consumeReady();
        } catch (RuntimeException failure) {
            return canonical;
        }
        if (projection == null
                || projection.items().isEmpty()
                || projection.items().size() > MemoryContextMessage.MAX_ITEMS) {
            return canonical;
        }
        int userIndex = identityIndex(canonical.messages(), currentUserMessage);
        if (userIndex < 0) {
            return canonical;
        }
        ArrayList<AgentMessage> messages = new ArrayList<>(canonical.messages().size() + 1);
        messages.addAll(canonical.messages().subList(0, userIndex));
        try {
            messages.add(new MemoryContextMessage(
                    projection.catalogRevision(), projection.items()));
        } catch (RuntimeException invalidProjection) {
            return canonical;
        }
        messages.addAll(canonical.messages().subList(userIndex, canonical.messages().size()));
        return new ModelRequest(
                canonical.sessionId(),
                canonical.runId(),
                canonical.turnNumber(),
                messages,
                canonical.toolDefinitions());
    }

    private int identityIndex(List<AgentMessage> messages, UserMessage currentUserMessage) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) == currentUserMessage) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Runtime 持有的单回合预取句柄。
     *
     * <p>{@link #consumeReady()} 最多委托一次 ready-only 消费；{@link #close()} 只调用
     * {@link MemoryPrefetch#cancel()}，不等待后台工作结束。</p>
     */
    public static final class TurnPrefetch implements AutoCloseable {

        private MemoryPrefetch prefetch;
        private boolean consumed;

        private TurnPrefetch(MemoryPrefetch prefetch) {
            this.prefetch = prefetch;
        }

        private static TurnPrefetch of(MemoryPrefetch prefetch) {
            return new TurnPrefetch(Objects.requireNonNull(prefetch, "prefetch 不能为空"));
        }

        private static TurnPrefetch empty() {
            return new TurnPrefetch(null);
        }

        private MemoryProjection consumeReady() {
            MemoryPrefetch current = prefetch;
            if (current == null || consumed) {
                return null;
            }
            consumed = true;
            return current.consumeReady();
        }

        /** 取消仍在运行的召回工作；不等待完成。 */
        @Override
        public void close() {
            MemoryPrefetch current = prefetch;
            prefetch = null;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
