package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.CheckpointDiff;
import io.github.liumaishenjian.ccjava.domain.CheckpointId;
import io.github.liumaishenjian.ccjava.domain.CheckpointSummary;
import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.CheckpointUndoResult;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.util.List;

/**
 * 协调写 Tool 与架构边缘普通文件 Checkpoint 的强一致端口。
 *
 * <p>{@link #create(ToolInvocation, CheckpointTarget)} 必须在 Tool started 和真实执行前
 * 可靠完成。实现失败时 Pipeline Fail Closed，不得执行副作用。该端口不负责 Shell、进程、
 * 网络或远端副作用，也不提供自动恢复。</p>
 *
 * @since 0.6.0
 */
public interface CheckpointCoordinator {

    /**
     * 在写 Tool 执行前创建 durable pre-image。
     *
     * @param invocation 已通过参数与权限校验的调用
     * @param target Tool 自身验证的单文件计划
     * @return 新 Checkpoint ID
     */
    CheckpointId create(ToolInvocation invocation, CheckpointTarget target);

    /**
     * Tool completed durable 前记录执行后的文件 digest。
     *
     * @param invocation 当前调用
     * @param checkpointId 对应 Checkpoint
     * @param result Tool 的规范结果；失败时实现仍检查当前状态
     */
    void complete(ToolInvocation invocation, CheckpointId checkpointId, ToolResult result);

    /**
     * 返回 Session 的安全 Checkpoint 摘要。
     *
     * @param sessionId 目标 Session
     * @return 不含绝对路径或文件内容的有界摘要
     */
    List<CheckpointSummary> list(SessionId sessionId);

    /**
     * 显式比较 pre-image 和当前 Workspace。
     *
     * @param sessionId 目标 Session
     * @param checkpointId 目标 Checkpoint
     * @return 有界 Diff 或冲突状态
     */
    CheckpointDiff diff(SessionId sessionId, CheckpointId checkpointId);

    /**
     * 显式执行 compare-before-restore Undo。
     *
     * @param sessionId 持有可写 lease 且没有活动 Run 的 Session
     * @param checkpointId 目标 Checkpoint
     * @param explicitlyConfirmed 只能由独立用户确认动作传入 {@code true}
     * @return 明确的恢复、幂等或冲突终态
     */
    CheckpointUndoResult undo(
            SessionId sessionId,
            CheckpointId checkpointId,
            boolean explicitlyConfirmed);

    /**
     * 返回不创建 Checkpoint 的兼容实现。
     *
     * @return 只生成关联 ID、不保存文件的实现
     */
    static CheckpointCoordinator noop() {
        return NoopHolder.INSTANCE;
    }

    /** 共享 no-op 实现。 */
    final class NoopHolder {
        private static final CheckpointCoordinator INSTANCE = new CheckpointCoordinator() {
            @Override
            public CheckpointId create(ToolInvocation invocation, CheckpointTarget target) {
                String call = invocation.call().id().replaceAll("[^A-Za-z0-9-]", "-");
                return new CheckpointId("checkpoint-noop-" + call + "-" + invocation.ordinal());
            }

            @Override
            public void complete(
                    ToolInvocation invocation,
                    CheckpointId checkpointId,
                    ToolResult result) {
            }

            @Override
            public List<CheckpointSummary> list(SessionId sessionId) {
                return List.of();
            }

            @Override
            public CheckpointDiff diff(SessionId sessionId, CheckpointId checkpointId) {
                throw new IllegalArgumentException("Checkpoint 不存在");
            }

            @Override
            public CheckpointUndoResult undo(
                    SessionId sessionId,
                    CheckpointId checkpointId,
                    boolean explicitlyConfirmed) {
                throw new IllegalArgumentException("Checkpoint 不存在");
            }
        };

        private NoopHolder() {
        }
    }
}
