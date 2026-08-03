package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 可安全投影给 stdio/TUI 的 Checkpoint 摘要。
 *
 * <p>摘要不包含绝对路径、pre-image、Store root 或原始 Tool 参数。{@link #phase()} 保留
 * durable prepared/uncertain/committed 区别，Surface 不得从布尔值猜测是否可以 Undo。</p>
 *
 * @param id Checkpoint ID
 * @param callId 对应 Tool Call ID
 * @param toolName 可信 Tool 名称
 * @param target Workspace-relative 目标
 * @param existedBefore 执行前是否存在
 * @param phase durable Checkpoint 阶段
 * @since 0.6.0
 */
public record CheckpointSummary(
        CheckpointId id,
        String callId,
        String toolName,
        String target,
        boolean existedBefore,
        CheckpointPhase phase) {

    /** 校验有界摘要字段。 */
    public CheckpointSummary {
        id = Objects.requireNonNull(id, "id 不能为空");
        callId = requireText(callId, "callId", 200);
        toolName = requireText(toolName, "toolName", 200);
        target = requireText(target, "target", 1_024);
        phase = Objects.requireNonNull(phase, "phase 不能为空");
    }

    /**
     * 判断该 phase 是否允许进入 compare-before-restore。
     *
     * @return completed journal 已提交且 post-state 可比较时为 {@code true}
     */
    public boolean undoable() {
        return phase == CheckpointPhase.COMPLETED_PRESENT
                || phase == CheckpointPhase.COMPLETED_ABSENT;
    }

    /**
     * 判断 Undo journal 是否已经提交。
     *
     * @return phase 为 {@link CheckpointPhase#UNDONE} 时为 {@code true}
     */
    public boolean undone() {
        return phase == CheckpointPhase.UNDONE;
    }

    private static String requireText(String value, String name, int max) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " 为空或超过长度限制");
        }
        return value;
    }
}
