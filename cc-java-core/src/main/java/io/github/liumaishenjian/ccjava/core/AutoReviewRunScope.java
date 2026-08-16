package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/**
 * 单个 Run 显式拥有的自动审查状态。
 *
 * <p>该类型由 {@link AgentRuntime} 创建、关闭并显式传入批处理与 Pipeline；它不使用
 * ThreadLocal、静态注册表或跨 Run 可变状态。自动审查未启用时使用 disabled scope，旧调用链
 * 保持用户审批语义。</p>
 *
 * @since 0.15.0
 */
public final class AutoReviewRunScope implements AutoCloseable {
    private final RunId runId;
    private final AutoReviewCircuit circuit;
    private final boolean enabled;
    private boolean stopAfterBatch;

    private AutoReviewRunScope(RunId runId, boolean enabled) {
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        this.enabled = enabled;
        this.circuit = enabled ? new AutoReviewCircuit(runId) : null;
    }

    /**
     * 创建启用自动审查的 Run 独占 scope。
     *
     * @param runId 当前 Run
     * @return 携带独立 circuit 的 scope
     */
    public static AutoReviewRunScope enabled(RunId runId) {
        return new AutoReviewRunScope(runId, true);
    }

    /**
     * 创建保持既有 USER 审批行为的 scope。
     *
     * @param runId 当前 Run
     * @return 不允许访问 circuit 的 disabled scope
     */
    public static AutoReviewRunScope disabled(RunId runId) {
        return new AutoReviewRunScope(runId, false);
    }

    /**
     * 查询该 scope 的 Run 所有者。
     *
     * @return 该 scope 唯一绑定的 Run
     */
    public RunId runId() {
        return runId;
    }

    /**
     * 查询该 scope 是否启用自动审查。
     *
     * @return 当前 reviewer 是否启用自动审查
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 返回当前 Run 独占 circuit。
     *
     * @return 启用状态下的 circuit
     * @throws IllegalStateException disabled scope 不拥有 circuit
     */
    public AutoReviewCircuit circuit() {
        if (!enabled) {
            throw new IllegalStateException("自动审查未启用");
        }
        return circuit;
    }

    /** 标记当前批次完成 Tool Result 配对后必须停止 Run。 */
    public synchronized void requestStopAfterBatch() {
        stopAfterBatch = true;
    }

    /**
     * 查询 batch stop 信号。
     *
     * @return 当前批次完成后是否必须停止 Run
     */
    public synchronized boolean stopAfterBatch() {
        return stopAfterBatch;
    }

    /** 关闭当前 Run 的 circuit；之后不允许再借此 scope 放行调用。 */
    @Override
    public void close() {
        if (circuit != null) circuit.close();
    }
}
