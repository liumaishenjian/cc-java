package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在一个已绑定 Agent Run 内为 Context overflow 提供至多一次摘要恢复与请求重试。
 *
 * <p>本类型不接入 AgentRuntime 或 ModelGateway；调用者提供只消费 Projection 的尝试函数。
 * retry 状态只保留当前 Run 的 source revision，并由 {@link #close()} 在 Run 终止时并发安全地
 * 清除。execute 与 close 使用同一生命周期锁建立 fail-closed 边界，但耗时的摘要和模型调用
 * 不在锁内执行；每个外部调用前后都重新检查关闭和取消，关闭期间产生的候选不会被采用或重试。</p>
 *
 * @since 0.7.0
 */
public final class ContextOverflowRetryCoordinator implements AutoCloseable {

    private final Object lifecycleLock = new Object();
    private final RunId runId;
    private final SummaryReductionCoordinator summaryCoordinator;
    private final Set<Long> consumedRevisions = new HashSet<>();
    private boolean closed;

    /**
     * 创建绑定单个 Run 的单 overflow retry Coordinator。
     *
     * @param runId 唯一所有者 Run
     * @param summaryCoordinator 同一 Run 使用的摘要协调器
     */
    public ContextOverflowRetryCoordinator(
            RunId runId,
            SummaryReductionCoordinator summaryCoordinator) {
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        this.summaryCoordinator = Objects.requireNonNull(
                summaryCoordinator, "summaryCoordinator 不能为空");
    }

    /**
     * 执行初始请求；仅在 overflow、允许恢复且摘要已提交时重试一次。
     *
     * @param requestedRunId 必须与构造时绑定的 Run 一致
     * @param request Canonical Transcript 快照与 overflow recovery 标记
     * @param projection C1/C2 Projection
     * @param policy C3/C4 Gate 配置
     * @param cancellationToken 取消令牌
     * @param attempt 不具有循环权的单次模型请求尝试
     * @param <T> 成功响应数据类型
     * @return 最终尝试状态、尝试次数、最终 Projection 与可选摘要结果
     * @throws IllegalStateException Coordinator 已关闭时
     */
    public <T> Outcome<T> execute(
            RunId requestedRunId,
            ProjectionRequest request,
            ContextProjection projection,
            SummaryReductionPolicy policy,
            CancellationToken cancellationToken,
            ProjectionAttempt<T> attempt) {
        verifyRun(requestedRunId);
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(projection, "projection 不能为空");
        Objects.requireNonNull(policy, "policy 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(attempt, "attempt 不能为空");
        ensureActive();
        if (cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 0, projection, Optional.empty());
        }

        AttemptResult<T> first = Objects.requireNonNull(
                attempt.execute(projection), "attempt result 不能为空");
        if (!isActive() || cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 1, projection, Optional.empty());
        }
        if (first.status() != AttemptStatus.OVERFLOW
                || !request.overflowRecoveryAvailable()) {
            return new Outcome<>(first, 1, projection, Optional.empty());
        }
        if (!consumeRetry(request.sourceRevision())) {
            return new Outcome<>(first, 1, projection, Optional.empty());
        }
        if (!isActive() || cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 1, projection, Optional.empty());
        }

        SummaryOutcome summary = summaryCoordinator.reduce(
                runId, request, projection, policy, cancellationToken);
        if (!isActive() || cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 1, projection, Optional.of(summary));
        }
        if (summary.status() != SummaryOutcome.Status.ADOPTED) {
            return new Outcome<>(first, 1, projection, Optional.of(summary));
        }
        if (!isActive() || cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 1, projection, Optional.of(summary));
        }

        AttemptResult<T> retry = Objects.requireNonNull(
                attempt.execute(summary.projection()), "retry result 不能为空");
        if (!isActive() || cancellationToken.isCancellationRequested()) {
            return new Outcome<>(AttemptResult.cancelled(), 2, summary.projection(), Optional.of(summary));
        }
        return new Outcome<>(retry, 2, summary.projection(), Optional.of(summary));
    }

    /**
     * 关闭当前 Run 的 retry 与摘要冷却状态。
     *
     * <p>关闭幂等；返回后不接受新 execute，并清除本 Run 的全部 revision Key。</p>
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            consumedRevisions.clear();
        }
        summaryCoordinator.close();
    }

    /** 返回测试和同包生命周期诊断使用的当前保留 revision 数。 */
    int retainedRetryCount() {
        synchronized (lifecycleLock) {
            return consumedRevisions.size();
        }
    }

    private void verifyRun(RunId requestedRunId) {
        Objects.requireNonNull(requestedRunId, "requestedRunId 不能为空");
        if (!runId.equals(requestedRunId)) {
            throw new IllegalArgumentException("overflow retry 不属于当前 Coordinator 绑定的 Run");
        }
    }

    private void ensureActive() {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("ContextOverflowRetryCoordinator 已关闭");
            }
        }
    }

    private boolean isActive() {
        synchronized (lifecycleLock) {
            return !closed;
        }
    }

    private boolean consumeRetry(long sourceRevision) {
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            return consumedRevisions.add(sourceRevision);
        }
    }

    /**
     * 单次模型请求尝试；实现不能自行重试。
     *
     * @param <T> 成功响应的数据类型
     */
    @FunctionalInterface
    public interface ProjectionAttempt<T> {
        /**
         * 对给定不可变 Projection 执行一次请求。
         *
         * @param projection 本次请求唯一可消费的 Projection
         * @return 成功、overflow、失败或取消的分类结果
         */
        AttemptResult<T> execute(ContextProjection projection);
    }

    /** 单次请求的结果分类。 */
    public enum AttemptStatus {
        /** 请求成功并携带数据。 */
        SUCCESS,
        /** Provider 明确报告输入 Context overflow。 */
        OVERFLOW,
        /** 非 overflow 失败。 */
        FAILED,
        /** 运行已取消。 */
        CANCELLED
    }

    /**
     * 单次请求结果；只有 SUCCESS 必须且只能携带 value。
     *
     * @param <T> 成功响应的数据类型
     * @param status 结果分类
     * @param value 成功数据
     */
    public record AttemptResult<T>(AttemptStatus status, Optional<T> value) {
        /** 校验状态与数据的一致性。 */
        public AttemptResult {
            status = Objects.requireNonNull(status, "status 不能为空");
            value = Objects.requireNonNull(value, "value 不能为空");
            if ((status == AttemptStatus.SUCCESS) != value.isPresent()) {
                throw new IllegalArgumentException("只有 SUCCESS 必须且只能携带 value");
            }
        }

        /**
         * 创建成功结果。
         *
         * @param <T> 成功响应的数据类型
         * @param value 本次请求产生的非空响应
         * @return 携带响应的成功分类结果
         */
        public static <T> AttemptResult<T> success(T value) {
            return new AttemptResult<>(AttemptStatus.SUCCESS, Optional.of(
                    Objects.requireNonNull(value, "value 不能为空")));
        }

        /**
         * 创建 overflow 结果。
         *
         * @param <T> 原本期望的成功响应数据类型
         * @return 不携带响应的 overflow 分类结果
         */
        public static <T> AttemptResult<T> overflow() {
            return new AttemptResult<>(AttemptStatus.OVERFLOW, Optional.empty());
        }

        /**
         * 创建普通失败结果。
         *
         * @param <T> 原本期望的成功响应数据类型
         * @return 不携带响应的非 overflow 失败分类结果
         */
        public static <T> AttemptResult<T> failed() {
            return new AttemptResult<>(AttemptStatus.FAILED, Optional.empty());
        }

        /**
         * 创建取消结果。
         *
         * @param <T> 原本期望的成功响应数据类型
         * @return 不携带响应的取消分类结果
         */
        public static <T> AttemptResult<T> cancelled() {
            return new AttemptResult<>(AttemptStatus.CANCELLED, Optional.empty());
        }
    }

    /**
     * 单 overflow recovery 的终态。
     *
     * @param <T> 最后一次成功响应的数据类型
     * @param result 最后一次实际模型请求结果；零次请求时为取消
     * @param modelRequestAttempts 实际请求次数，只能为零、一或二
     * @param projection 最后一次请求使用或可安全保留的 Projection
     * @param summaryOutcome overflow 后的可选摘要决策
     */
    public record Outcome<T>(
            AttemptResult<T> result,
            int modelRequestAttempts,
            ContextProjection projection,
            Optional<SummaryOutcome> summaryOutcome) {
        /** 校验尝试计数和摘要/Projection 对应关系。 */
        public Outcome {
            result = Objects.requireNonNull(result, "result 不能为空");
            projection = Objects.requireNonNull(projection, "projection 不能为空");
            summaryOutcome = Objects.requireNonNull(
                    summaryOutcome, "summaryOutcome 不能为空");
            if (modelRequestAttempts < 0 || modelRequestAttempts > 2) {
                throw new IllegalArgumentException("modelRequestAttempts 只能为零、一或二");
            }
            if (modelRequestAttempts == 0
                    && (result.status() != AttemptStatus.CANCELLED || summaryOutcome.isPresent())) {
                throw new IllegalArgumentException("零次请求只能表示请求前取消");
            }
            if (modelRequestAttempts == 2
                    && (summaryOutcome.isEmpty()
                    || summaryOutcome.orElseThrow().status()
                            != SummaryOutcome.Status.ADOPTED
                    || !projection.equals(summaryOutcome.orElseThrow().projection()))) {
                throw new IllegalArgumentException("第二次请求必须使用已提交摘要 Projection");
            }
        }
    }
}
