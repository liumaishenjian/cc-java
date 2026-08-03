package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 显式 compare-before-restore Undo 的结果。
 *
 * @param checkpointId Checkpoint ID
 * @param status 固定终态
 * @param target Workspace-relative 目标
 * @param message 不含绝对路径或文件内容的稳定说明
 * @since 0.6.0
 */
public record CheckpointUndoResult(
        CheckpointId checkpointId,
        Status status,
        String target,
        String message) {

    /** Undo 终态。 */
    public enum Status {
        /** 已恢复 pre-image 或删除 Agent 创建的新文件。 */
        RESTORED,
        /** 此前已经成功恢复，本次没有再次写入。 */
        ALREADY_RESTORED,
        /** 当前 Workspace 状态与已记录 post-image 不一致。 */
        CONFLICT
    }

    /** 校验隐私安全结果。 */
    public CheckpointUndoResult {
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        target = Objects.requireNonNull(target, "target 不能为空");
        message = Objects.requireNonNull(message, "message 不能为空");
        if (target.isBlank() || target.length() > 1_024
                || message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("Checkpoint Undo 结果超过协议上限");
        }
    }
}
