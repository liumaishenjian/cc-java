package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolJournalPersistenceException;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 对同一 Assistant batch 中明确安全的只读 Tool 执行有限并行。
 *
 * <p>每个实例拥有按 {@code maximumParallelism} 创建的有界执行器；只有宿主 allowlist 中、
 * effect=READ_WORKSPACE 且整个批次均安全的调用才并发。每个调用仍进入传入的唯一
 * {@link ToolExecutionPipeline}，结果按原始顺序和 Call ID 归并。取消或 fence 异常会取消全部
 * 在飞任务并有界等待，避免批次返回后留下孤儿。</p>
 *
 * @since 0.12.0
 */
public final class ParallelToolBatchExecutor implements AutoCloseable {
    private static final long QUIESCE_SECONDS = 5;
    private final ToolRegistry registry;
    private final ToolExecutionPipeline pipeline;
    private final Set<String> allowlist;
    private final ExecutorService executor;

    /**
     * 创建只并发执行宿主 allowlist 中只读 Tool 的有界执行器。
     *
     * @param registry 用于确认 Tool effect 的唯一 Registry
     * @param pipeline 每个调用必须经过的统一执行管线
     * @param allowlist 宿主明确允许并发的 Tool 名称
     * @param maximumParallelism 最大并发度，范围 1 到 4
     */
    public ParallelToolBatchExecutor(ToolRegistry registry, ToolExecutionPipeline pipeline,
            Set<String> allowlist, int maximumParallelism) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline 不能为空");
        this.allowlist = Set.copyOf(Objects.requireNonNull(allowlist, "allowlist 不能为空"));
        if (maximumParallelism < 1 || maximumParallelism > 4) {
            throw new IllegalArgumentException("并行度无效");
        }
        executor = Executors.newFixedThreadPool(maximumParallelism,
                Thread.ofPlatform().name("cc-java-read-batch-", 0).daemon(true).factory());
    }

    /**
     * 执行批次；{@code ordinals} 必须与 {@code calls} 一一对应。
     *
     * @param session Tool 执行所属 Session
     * @param runId Tool 执行所属 Run
     * @param ordinals 每个调用在 Run 中的原始序号
     * @param calls 同一 Assistant Message 提出的调用
     * @param cancellation 批次共享取消令牌
     * @return 与模型原始调用顺序一致的 Tool Result
     */
    public List<ToolResult> executeSafeBatch(AgentSession session, RunId runId,
            List<Integer> ordinals, List<ToolCall> calls, CancellationToken cancellation) {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(calls, "calls 不能为空");
        Objects.requireNonNull(ordinals, "ordinals 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (calls.size() != ordinals.size()) {
            throw new IllegalArgumentException("ordinal 数量不匹配");
        }
        if (calls.size() < 2 || calls.stream().anyMatch(call -> !safe(call))) {
            return sequential(session, runId, ordinals, calls, cancellation);
        }

        List<Future<ToolResult>> futures = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            int ordinal = ordinals.get(index);
            ToolCall call = calls.get(index);
            futures.add(executor.submit(
                    () -> pipeline.execute(session, runId, ordinal, call, cancellation)));
        }

        List<ToolResult> results = new ArrayList<>(calls.size());
        try {
            for (int index = 0; index < futures.size(); index++) {
                if (cancellation.isCancellationRequested()) {
                    cancelAndQuiesce(futures);
                    return cancelledResults(calls);
                }
                try {
                    results.add(futures.get(index).get());
                } catch (CancellationException cancelled) {
                    cancelAndQuiesce(futures);
                    return cancelledResults(calls);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancelAndQuiesce(futures);
                    return cancelledResults(calls);
                } catch (ExecutionException failure) {
                    cancelAndQuiesce(futures);
                    Throwable cause = failure.getCause();
                    if (cause instanceof ToolJournalPersistenceException journalFailure) {
                        throw journalFailure;
                    }
                    if (cause instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw new IllegalStateException("并行 Tool 执行失败", cause);
                }
            }
            return List.copyOf(results);
        } finally {
            if (cancellation.isCancellationRequested()) {
                cancelAndQuiesce(futures);
            }
        }
    }

    private boolean safe(ToolCall call) {
        return allowlist.contains(call.name()) && registry.find(call.name())
                .map(tool -> tool.definition().effect() == ToolEffect.READ_WORKSPACE)
                .orElse(false);
    }

    private List<ToolResult> sequential(AgentSession session, RunId runId,
            List<Integer> ordinals, List<ToolCall> calls, CancellationToken cancellation) {
        List<ToolResult> results = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            results.add(pipeline.execute(session, runId, ordinals.get(index), calls.get(index), cancellation));
        }
        return List.copyOf(results);
    }

    private static List<ToolResult> cancelledResults(List<ToolCall> calls) {
        return calls.stream().map(call -> ToolResult.failure(call.id(), call.name(),
                ToolError.of(ToolErrorCode.EXECUTION_FAILED, "并行 Tool 批次被取消"))).toList();
    }

    private static void cancelAndQuiesce(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUIESCE_SECONDS);
        for (Future<?> future : futures) {
            while (!future.isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    /** 停止该批次执行器并有界等待全部在飞调用。 */
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(QUIESCE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
