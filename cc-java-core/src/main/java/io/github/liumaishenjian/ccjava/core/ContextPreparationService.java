package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStatus;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final boolean enabled;
    private final ConcurrentMap<RunId, RunState> runs = new ConcurrentHashMap<>();

    /** 创建启用 C1-C4 的准备服务。 */
    public ContextPreparationService(
            ContextPreparationConfig config,
            ContextSummarizer summarizer) {
        this.config = Objects.requireNonNull(config, "config 不能为空");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不能为空");
        this.enabled = true;
    }

    private ContextPreparationService() {
        this.config = null;
        this.summarizer = null;
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
                canonical.messages(), config.capacity(), revision, protectedCount, false);
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
        return new ModelRequest(
                canonical.sessionId(),
                canonical.runId(),
                canonical.turnNumber(),
                projection.messages(),
                canonical.toolDefinitions());
    }

    /** 关闭并移除一个 Run 的所有摘要冷却状态。 */
    public void closeRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        RunState state = runs.remove(runId);
        if (state != null) {
            state.summary().close();
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
        return new RunState(reducer, summary);
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

    private record RunState(
            DeterministicContextReducer reducer,
            SummaryReductionCoordinator summary) {
    }
}
