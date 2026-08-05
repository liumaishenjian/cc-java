package io.github.liumaishenjian.ccjava.tools.local.memory;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.MemoryContextService;
import io.github.liumaishenjian.ccjava.core.MemoryPrefetch;
import io.github.liumaishenjian.ccjava.core.MemoryPrefetchFactory;
import io.github.liumaishenjian.ccjava.core.MemoryProjector;
import io.github.liumaishenjian.ccjava.core.RelevantMemoryRecall;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalog;
import io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision;
import io.github.liumaishenjian.ccjava.domain.MemoryProjection;
import io.github.liumaishenjian.ccjava.domain.MemoryRecallPlan;
import io.github.liumaishenjian.ccjava.domain.RecallQuery;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用本地文件 Adapter 异步完成 D2 M3-M5 召回并拥有其执行器生命周期。
 *
 * <p>{@link #start(UserMessage, CancellationToken)} 只验证内存状态并提交异步任务，不执行文件 I/O。
 * 有界关键词提取、Catalog Adapter 构造与重建、M4 选择、消费时 fresh Catalog/revision 检查、正文加载
 * 和 M5 投影全部在执行器任务中完成。生产构造器使用独立虚拟线程执行器；注入执行器同样必须真正排队，
 * 不得在 {@link ExecutorService#execute(Runnable)} 内联到调用线程。缺失、非法或变化的 root，以及任务拒绝、
 * 取消和失败，均以无记忆降级且不回显 Workspace、home、root 或底层异常文本。</p>
 *
 * <p>该应用层文件边界不是 OS Sandbox；Memory 内容仍是不可信 Context，不能授予权限。</p>
 *
 * @since 0.7.0
 */
public final class FileMemoryPrefetchAdapter implements MemoryPrefetchFactory, AutoCloseable {

    /** M4 最多选择 20 个候选，与 Domain 上限一致。 */
    public static final int MAX_TOPICS = 20;

    /** M5 正文总预算为 256 KiB，与 Domain 上限一致。 */
    public static final int BYTE_BUDGET = 256 * 1024;

    private static final MemoryCatalogRevision EMPTY_REVISION =
            new MemoryCatalogRevision("0".repeat(64));

    private final Path memoryRoot;
    private final ExecutorService executor;
    private final DeterministicMemoryKeywordPolicy keywordPolicy;
    private final RelevantMemoryRecall recall;
    private final RecallObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 使用独立虚拟线程执行器装配一个固定 root 的生产 Adapter。
     *
     * @param memoryRoot 默认布局或测试注入的 memory root；构造时不访问文件系统
     */
    public FileMemoryPrefetchAdapter(Path memoryRoot) {
        this(
                memoryRoot,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("cc-java-memory-prefetch-", 0).factory()),
                new DeterministicMemoryKeywordPolicy(),
                new RelevantMemoryRecall(),
                RecallObserver.noop());
    }

    /**
     * 使用调用方移交所有权的执行器创建 Adapter，供 Composition 测试确定性控制调度。
     *
     * <p>Adapter 在 {@link #close()} 时仍会调用 {@link ExecutorService#shutdownNow()}；调用方不得共享
     * 该执行器。执行器的 {@link ExecutorService#execute(Runnable)} 必须先排队再返回，不得在调用线程内联
     * 执行任务，否则会破坏 {@link #start(UserMessage, CancellationToken)} 的零 I/O、立即返回契约。</p>
     *
     * @param memoryRoot 注入的 memory root
     * @param executor 专属于该 Adapter、且保证非内联排队的执行器
     */
    public FileMemoryPrefetchAdapter(Path memoryRoot, ExecutorService executor) {
        this(
                memoryRoot,
                executor,
                new DeterministicMemoryKeywordPolicy(),
                new RelevantMemoryRecall(),
                RecallObserver.noop());
    }

    FileMemoryPrefetchAdapter(
            Path memoryRoot,
            ExecutorService executor,
            DeterministicMemoryKeywordPolicy keywordPolicy,
            RelevantMemoryRecall recall,
            RecallObserver observer) {
        this.memoryRoot = Objects.requireNonNull(memoryRoot, "memoryRoot 不能为空")
                .toAbsolutePath()
                .normalize();
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.keywordPolicy = Objects.requireNonNull(keywordPolicy, "keywordPolicy 不能为空");
        this.recall = Objects.requireNonNull(recall, "recall 不能为空");
        this.observer = Objects.requireNonNull(observer, "observer 不能为空");
    }

    /**
     * 立即提交一次只使用当前用户消息的异步召回。
     *
     * @param currentUserMessage 当前 Run 的有界用户消息
     * @param cancellationToken Run 取消令牌
     * @return ready-only 句柄；关闭或提交拒绝时返回已完成空句柄
     */
    @Override
    public MemoryPrefetch start(
            UserMessage currentUserMessage,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(currentUserMessage, "currentUserMessage 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (closed.get() || cancellationToken.isCancellationRequested()) {
            return emptyPrefetch();
        }
        String userText = currentUserMessage.content();
        try {
            return MemoryPrefetch.start(
                    executor,
                    () -> recall(userText, cancellationToken),
                    BYTE_BUDGET,
                    EMPTY_REVISION);
        } catch (RejectedExecutionException rejected) {
            return emptyPrefetch();
        }
    }

    private MemoryProjection recall(
            String userText,
            CancellationToken cancellationToken) {
        if (closed.get() || cancellationToken.isCancellationRequested()) {
            return emptyProjection();
        }
        try {
            observer.beforeFileWork();
            List<String> keywords = keywordPolicy.extract(userText);
            if (keywords.isEmpty()
                    || !Files.exists(memoryRoot, LinkOption.NOFOLLOW_LINKS)) {
                return emptyProjection();
            }
            FileMemoryCatalogAdapter catalogs = new FileMemoryCatalogAdapter(memoryRoot);
            MemoryCatalog selectedCatalog = catalogs.rebuild();
            if (closed.get() || cancellationToken.isCancellationRequested()) {
                return emptyProjection(selectedCatalog.revision());
            }
            RecallQuery query = new RecallQuery(
                    userText,
                    keywords,
                    MAX_TOPICS,
                    BYTE_BUDGET,
                    selectedCatalog.revision());
            MemoryRecallPlan plan = recall.select(selectedCatalog, query);
            if (plan.selectedHeaders().isEmpty()) {
                return emptyProjection(selectedCatalog.revision());
            }
            observer.afterSelection();
            if (closed.get() || cancellationToken.isCancellationRequested()) {
                return emptyProjection(selectedCatalog.revision());
            }
            MemoryCatalog consumptionCatalog = catalogs.rebuild();
            if (closed.get() || cancellationToken.isCancellationRequested()) {
                return emptyProjection(consumptionCatalog.revision());
            }
            return new MemoryProjector(new FileMemoryBodyLoader(memoryRoot))
                    .project(plan, consumptionCatalog);
        } catch (RuntimeException failure) {
            return emptyProjection();
        }
    }

    private MemoryPrefetch emptyPrefetch() {
        return MemoryPrefetch.start(
                Runnable::run,
                this::emptyProjection,
                BYTE_BUDGET,
                EMPTY_REVISION);
    }

    private MemoryProjection emptyProjection() {
        return emptyProjection(EMPTY_REVISION);
    }

    private MemoryProjection emptyProjection(MemoryCatalogRevision revision) {
        return new MemoryProjection(List.of(), 0, BYTE_BUDGET, revision, List.of());
    }

    /**
     * 取消尚未结束的任务并立即关闭执行器，不等待后台文件操作退出。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    /** 包级测试观察 worker 阶段；回调始终位于执行器线程，不进入公开契约。 */
    interface RecallObserver {
        void beforeFileWork();

        void afterSelection();

        static RecallObserver noop() {
            return new RecallObserver() {
                @Override
                public void beforeFileWork() {
                }

                @Override
                public void afterSelection() {
                }
            };
        }
    }

    /**
     * 创建供 AgentRuntime 使用的短生命周期 Memory Context 服务。
     *
     * @return 以本 Adapter 为回合级预取工厂、但不转移 Executor 所有权的 Core 服务
     */
    public MemoryContextService contextService() {
        return new MemoryContextService(this);
    }
}
