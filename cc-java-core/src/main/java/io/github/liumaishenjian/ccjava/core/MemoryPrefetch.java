package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryCatalogRevision;
import io.github.liumaishenjian.ccjava.domain.MemoryProjection;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnostic;
import io.github.liumaishenjian.ccjava.domain.MemoryProjectionDiagnosticKind;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * M5 ready-only 预取句柄：早启动，但消费点绝不等待。
 *
 * <p>{@link #consumeReady()} 只调用 {@link Future#isDone()}、{@link Future#isCancelled()} 和
 * 已完成 {@link CompletableFuture#getNow(Object)}；不调用 join/get、sleep 或锁等待。每个句柄只能
 * 消费一次，当前请求忽略的迟到结果不会在同一请求再次注入。</p>
 *
 * @since 0.7.0
 */
public final class MemoryPrefetch {

    private final Future<MemoryProjection> future;
    private final int byteBudget;
    private final MemoryCatalogRevision revision;
    private final AtomicBoolean consumed = new AtomicBoolean();

    MemoryPrefetch(
            Future<MemoryProjection> future,
            int byteBudget,
            MemoryCatalogRevision revision) {
        this.future = Objects.requireNonNull(future, "future 不能为空");
        this.byteBudget = validateByteBudget(byteBudget);
        this.revision = requireRevision(revision);
    }

    /**
     * 在 Executor 上尽早启动预取工作。
     *
     * @param executor 调用者拥有的执行器
     * @param work 产生投影的有界工作
     * @param byteBudget 空投影仍需保留的预算
     * @param revision 启动 revision
     * @return ready-only 句柄
     */
    public static MemoryPrefetch start(
            Executor executor,
            Supplier<MemoryProjection> work,
            int byteBudget,
            MemoryCatalogRevision revision) {
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor 不能为空");
        Supplier<MemoryProjection> checkedWork = Objects.requireNonNull(work, "work 不能为空");
        int checkedBudget = validateByteBudget(byteBudget);
        MemoryCatalogRevision checkedRevision = requireRevision(revision);
        CompletableFuture<MemoryProjection> future = CompletableFuture.supplyAsync(
                checkedWork, checkedExecutor);
        return new MemoryPrefetch(future, checkedBudget, checkedRevision);
    }

    /**
     * 立即检查一次结果；未完成、失败、取消或重复消费均返回空投影。
     *
     * @return 当前时刻 ready 的投影，否则结构化空投影
     */
    public MemoryProjection consumeReady() {
        if (!consumed.compareAndSet(false, true)) {
            return empty(MemoryProjectionDiagnosticKind.ALREADY_CONSUMED);
        }
        if (!future.isDone()) {
            return empty(MemoryProjectionDiagnosticKind.NOT_READY);
        }
        if (future.isCancelled()) {
            return empty(MemoryProjectionDiagnosticKind.CANCELLED);
        }
        if (!(future instanceof CompletableFuture<?> completable)) {
            return empty(MemoryProjectionDiagnosticKind.RECALL_FAILED);
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<MemoryProjection> typed =
                    (CompletableFuture<MemoryProjection>) completable;
            MemoryProjection projection = typed.getNow(null);
            return projection == null
                    ? empty(MemoryProjectionDiagnosticKind.RECALL_FAILED)
                    : projection;
        } catch (RuntimeException failure) {
            return empty(MemoryProjectionDiagnosticKind.RECALL_FAILED);
        }
    }

    /**
     * 尽力取消尚未完成的召回工作，绝不等待任务结束。
     *
     * <p>执行器与底层资源仍由创建该句柄的 Adapter 拥有；本方法只传播
     * {@link Future#cancel(boolean)} 信号。</p>
     *
     * @return Future 接受本次取消请求时为 {@code true}
     */
    public boolean cancel() {
        return future.cancel(true);
    }

    private MemoryProjection empty(MemoryProjectionDiagnosticKind kind) {
        return MemoryProjection.empty(
                byteBudget, revision, MemoryProjectionDiagnostic.catalog(kind));
    }

    private static int validateByteBudget(int byteBudget) {
        if (byteBudget < 1 || byteBudget > 256 * 1024) {
            throw new IllegalArgumentException("byteBudget 必须在 1..262144");
        }
        return byteBudget;
    }

    private static MemoryCatalogRevision requireRevision(MemoryCatalogRevision revision) {
        return Objects.requireNonNull(revision, "revision 不能为空");
    }
}
