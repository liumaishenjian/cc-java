package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 标识一次写 Tool 执行前创建的普通文件 Checkpoint。
 *
 * <p>ID 只承担 Session 内关联语义，不包含路径、内容摘要或存储位置。</p>
 *
 * @param value 不透明且有界的 Checkpoint ID
 * @since 0.6.0
 */
public record CheckpointId(String value) {

    /** 校验不透明标识。 */
    public CheckpointId {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (value.isBlank() || value.length() > 128
                || !value.matches("checkpoint-[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Checkpoint ID 格式无效");
        }
    }
}
