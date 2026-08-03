package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * Checkpoint pre-image 与当前 Workspace 文件的有界显式比较。
 *
 * @param checkpointId Checkpoint ID
 * @param target 相对路径
 * @param status 固定比较状态
 * @param text 有界文本差异，不含绝对路径
 * @param truncated 是否因输出预算裁剪
 * @since 0.6.0
 */
public record CheckpointDiff(
        CheckpointId checkpointId,
        String target,
        Status status,
        String text,
        boolean truncated) {

    /** 显式 Diff 状态。 */
    public enum Status {
        /** 当前内容等于 pre-image。 */
        UNCHANGED,
        /** 当前内容与 pre-image 不同。 */
        CHANGED,
        /** Checkpoint 前不存在且当前仍不存在。 */
        ABSENT,
        /** 当前路径或备份不满足安全契约。 */
        CONFLICT
    }

    /** 校验有界结果。 */
    public CheckpointDiff {
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        target = Objects.requireNonNull(target, "target 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        text = Objects.requireNonNull(text, "text 不能为空");
        if (target.isBlank() || target.length() > 1_024 || text.length() > 64_000) {
            throw new IllegalArgumentException("Checkpoint Diff 超过协议上限");
        }
    }
}
