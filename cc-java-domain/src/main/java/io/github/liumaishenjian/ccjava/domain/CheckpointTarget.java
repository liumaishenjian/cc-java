package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 描述写 Tool 经自身安全规则验证后声明的单一普通文件目标。
 *
 * <p>路径始终是 Workspace-relative 协议路径。{@code existedBefore} 区分已有文件
 * pre-image 与新文件“不存在”标记；Core 不接触文件系统路径类型。</p>
 *
 * @param protocolPath 使用 {@code /} 的相对路径
 * @param existedBefore Tool 执行前目标是否为普通文件
 * @since 0.6.0
 */
public record CheckpointTarget(String protocolPath, boolean existedBefore) {

    /** 校验安全协议值。 */
    public CheckpointTarget {
        protocolPath = Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        if (protocolPath.isBlank() || protocolPath.length() > 1_024) {
            throw new IllegalArgumentException("Checkpoint 目标路径为空或超过长度限制");
        }
    }
}
