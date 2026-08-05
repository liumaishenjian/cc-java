package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStatus;
import io.github.liumaishenjian.ccjava.domain.ContextUsageReasonCode;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 ContextAssembler 生成的 Canonical ModelRequest 构建单回合短生命周期 Projection。
 *
 * <p>默认实例是 no-op，保持 S01-S06 构造器行为。启用实例先执行 C1/C2；仍超容量时条件式
 * 执行 C3/C4。服务不修改 Session、Journal 或输入请求，并在 Run 结束时关闭冷却状态。</p>
 *
 * @since 0.7.0
 */
public final class ContextPreparationService {

    private final ContextPreparationConfig config;
    private final ContextSummarizer summarizer;
    private final ContextUsageObserver usageObserver;
    private final boolean enabled;
    private final ConcurrentMap<RunId, RunState> runs = new ConcurrentHashMap<>();

    /** 创建启用 C1-C4 的准备服务，默认不发布 Context Usage View。 */
    public ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer) {
        this(config, summarizer, ContextUsageObserver.noop());
    }

    /**
     * 创建启用 C1-C4 与旁路 Context Usage 观察的准备服务。
     *
     * <p>观察者仅接收数值化 View；其异常由本服务隔离，不能改变 Runtime 的请求、恢复或终态。</p>
     *
     * @param config 已校验的容量与 reduction 配置
     * @param summarizer C3/C4 摘要 Port
     * @param usageObserver 非权威的 Usage View 观察端口
     */
    public ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer,
            ContextUsageObserver usageObserver) {
        this.config = Objects.requireNonNull(config, "config 不能为空");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不能为空");
        this.usageObserver = Objects.requireNonNull(usageObserver, "usageObserver 不能为空");
        this.enabled = true;
    }

    private ContextPreparationService() {
        this.config = null;
        this.summarizer = null;
        this.usageObserver = ContextUsageObserver.noop();
        this.enabled = false;
    }

    /** 返回保持原 ModelRequest 不变且不保留 Run 状态的兼容路径。 */
    public static ContextPreparationService noop() {
        return new ContextPreparationService();
    }

    /**
     * 为一个模型回合准备 Projection，并保持 Session/Run/turn/Tool 定义原样。
     */
    public ModelRequest prepare(
            ModelRequest canonical,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(canonical, "canonical 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (!enabled) {
            return canonical;
        }
        RunState state = runs.computeIfAbsent(canonical.runId(), this::newRunState);
        int protectedCount = Math.min(
                config.protectedMessageCount(), canonical.messages().size());
        long revision = canonical.messages().size();
        ProjectionRequest request = new ProjectionRequest(
                canonical.messages(), config.capacity(), revision, protectedCount, true);
        ContextReductionOutcome reduced = state.reducer().reduce(request, cancellationToken);
        ContextProjection projection = reduced.projection();
        if (reduced.status() == ContextReductionStatus.CONTEXT_LIMIT_REACHED
                && !cancellationToken.isCancellationRequested()) {
            SummaryReductionPolicy policy = new SummaryReductionPolicy(
                    rollingEnd(projection, protectedCount),
                    true,
                    List.of(),
                    List.of(),
                    config.maxSummaryUtf8Bytes(),
                    config.maxSummaryTokens());
            SummaryOutcome summary = state.summary().reduce(
                    canonical.runId(), request, projection, policy, cancellationToken);
            projection = summary.projection();
        }
        ModelRequest prepared = new ModelRequest(
                canonical.sessionId(),
                canonical.runId(),
                canonical.turnNumber(),
                projection.messages(),
                canonical.toolDefinitions());
        ContextCapacity recoveryCapacity = recoveryCapacity(projection);
        ProjectionRequest recoveryRequest = new ProjectionRequest(
                canonical.messages(), recoveryCapacity, revision, protectedCount, true);
        ContextProjection recoveryProjection = new ContextProjection(
                projection.messages(),
                state.estimator().estimate(projection.messages(), recoveryCapacity),
                projection.appliedReductions(),
                projection.sourceRevision());
        state.prepared().set(new PreparedContext(
                recoveryRequest,
                recoveryProjection,
                policyFor(recoveryProjection, protectedCount)));
        publish(ContextUsageView.prepared(projection, config.capacity()));
        return prepared;
    }

    /**
     * 执行首次请求，并仅在 typed context overflow 后强制摘要与重试一次。
     *
     * <p>no-op 路径直接执行一次。启用路径把既有 Coordinator 的无循环尝试结果重新映射为
     * 原始 {@link ModelGatewayException}，从而保持非 overflow 失败和第二次 overflow 的精确分类。</p>
     */
    public <T> T executePrepared(
            ModelRequest prepared,
            CancellationToken cancellationToken,
            PreparedAttempt<T> attempt) throws ModelGatewayException {
        Objects.requireNonNull(prepared, "prepared 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(attempt, "attempt 不能为空");
        if (!enabled) {
            return attempt.execute(prepared);
        }
        RunState state = runs.get(prepared.runId());
        PreparedContext context = state == null ? null : state.prepared().getAndSet(null);
        if (state == null || context == null) {
            return attempt.execute(prepared);
        }
        AtomicReference<ModelGatewayException> failure = new AtomicReference<>();
        AtomicBoolean attempted = new AtomicBoolean();
        ContextOverflowRetryCoordinator.Outcome<AttemptValue<T>> outcome =
                state.overflow().execute(
                        prepared.runId(),
                        context.request(),
                        context.projection(),
                        context.policy(),
                        cancellationToken,
                        projection -> {
                    attempted.set(true);
                    try {
                        ModelRequest request = new ModelRequest(
                                prepared.sessionId(), prepared.runId(), prepared.turnNumber(),
                                projection.messages(), prepared.toolDefinitions());
                        return ContextOverflowRetryCoordinator.AttemptResult.success(
                                new AttemptValue<>(attempt.execute(request)));
                    } catch (ModelGatewayException exception) {
                        failure.set(exception);
                        return exception.kind() == ModelGatewayException.FailureKind.CONTEXT_OVERFLOW
                                ? ContextOverflowRetryCoordinator.AttemptResult.overflow()
                                : ContextOverflowRetryCoordinator.AttemptResult.failed();
                    }
                });
        publishRecovery(context, outcome);
        if (outcome.result().status()
                == ContextOverflowRetryCoordinator.AttemptStatus.SUCCESS) {
            return outcome.result().value().orElseThrow().value();
        }
        if (!attempted.get()) {
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.CANCELLED,
                    "Model request cancelled");
        }
        ModelGatewayException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
        throw new ModelGatewayException("Model request failed");
    }

    private <T> void publishRecovery(
            PreparedContext context,
            ContextOverflowRetryCoordinator.Outcome<T> outcome) {
        if (outcome.modelRequestAttempts() == 0) {
            return;
        }
        java.util.ArrayList<ContextUsageReasonCode> codes = new java.util.ArrayList<>();
        if (outcome.result().status() == ContextOverflowRetryCoordinator.AttemptStatus.OVERFLOW
                || outcome.summaryOutcome().isPresent()
                || outcome.modelRequestAttempts() == 2) {
            codes.add(ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW);
        }
        outcome.summaryOutcome().ifPresent(summary -> {
            if (summary.status() == SummaryOutcome.Status.ADOPTED) {
                codes.add(ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED);
            } else if (summary.status() == SummaryOutcome.Status.CANCELLED) {
                codes.add(ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED);
            } else {
                codes.add(ContextUsageReasonCode.OVERFLOW_SUMMARY_UNCHANGED);
            }
        });
        if (outcome.result().status() == ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED) {
            codes.add(ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED);
        }
        if (codes.isEmpty()) {
            return;
        }
        publish(ContextUsageView.recovered(
                outcome.projection(), context.request().capacity(), codes,
                outcome.modelRequestAttempts()));
    }

    private void publish(ContextUsageView view) {
        try {
            usageObserver.publish(view);
        } catch (RuntimeException ignored) {
            // Usage View 是旁路诊断，观察端故障不能影响模型请求或恢复。
        }
    }

    /** 关闭并移除一个 Run 的所有摘要冷却和 overflow retry 状态。 */
    public void closeRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        RunState state = runs.remove(runId);
        if (state != null) {
            state.overflow().close();
        }
    }

    /** 返回测试可观察的活动 Run 数。 */
    int activeRunCount() {
        return runs.size();
    }

    private RunState newRunState(RunId runId) {
        ContextTokenEstimator estimator = new CodePointContextTokenEstimator();
        DeterministicContextReducer reducer = new DeterministicContextReducer(
                estimator, config.largePayloadTokenThreshold());
        SummaryAttemptGuard guard = new SummaryAttemptGuard(runId);
        SummaryReductionCoordinator summary = new SummaryReductionCoordinator(
                summarizer, estimator, guard);
        ContextOverflowRetryCoordinator overflow = new ContextOverflowRetryCoordinator(
                runId, summary);
        return new RunState(
                estimator, reducer, summary, overflow, new AtomicReference<>());
    }

    private ContextCapacity recoveryCapacity(ContextProjection projection) {
        long reducedBudget = Math.max(1L, projection.usage().totalTokens() - 1L);
        long reserved = Math.addExact(
                config.capacity().reservedOutputTokens(),
                config.capacity().safetyMarginTokens());
        long maximum = Math.addExact(reducedBudget, reserved);
        return new ContextCapacity(
                config.capacity().modelId(),
                maximum,
                config.capacity().reservedOutputTokens(),
                config.capacity().safetyMarginTokens());
    }

    private SummaryReductionPolicy policyFor(
            ContextProjection projection,
            int protectedCount) {
        return new SummaryReductionPolicy(
                rollingEnd(projection, protectedCount),
                true,
                List.of(),
                List.of(),
                config.maxSummaryUtf8Bytes(),
                config.maxSummaryTokens());
    }

    private int rollingEnd(ContextProjection projection, int protectedCount) {
        int protectedStart = projection.messages().size() - protectedCount;
        int first = !projection.messages().isEmpty()
                && projection.messages().getFirst()
                        instanceof io.github.liumaishenjian.ccjava.domain.SystemMessage
                ? 1 : 0;
        int available = protectedStart - first;
        return available <= 1 ? 0 : first + Math.max(1, available / 2);
    }

    /** 单次 ModelRequest 尝试；实现不得自行循环。 */
    @FunctionalInterface
    public interface PreparedAttempt<T> {
        /** 执行一次 prepared 或 recovered 请求。 */
        T execute(ModelRequest request) throws ModelGatewayException;
    }

    private record AttemptValue<T>(T value) {
    }

    private record PreparedContext(
            ProjectionRequest request,
            ContextProjection projection,
            SummaryReductionPolicy policy) {
    }

    private record RunState(
            ContextTokenEstimator estimator,
            DeterministicContextReducer reducer,
            SummaryReductionCoordinator summary,
            ContextOverflowRetryCoordinator overflow,
            AtomicReference<PreparedContext> prepared) {
    }
}
