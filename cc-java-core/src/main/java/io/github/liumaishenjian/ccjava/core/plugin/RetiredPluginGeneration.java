package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.util.Objects;

/**
 * 已退出 active namespace 且 lease 已归零、可由 Adapter 恰好回收一次的 generation。
 *
 * @param snapshot 待回收 immutable snapshot
 * @param generationId Registry 内稳定且不含路径的 generation ID
 * @since 0.11.0
 */
public record RetiredPluginGeneration(PluginSnapshot snapshot, long generationId) {
    /** 校验 snapshot 存在且 generation ID 为正数。 */
    public RetiredPluginGeneration {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if (generationId < 1) throw new IllegalArgumentException("generationId 必须为正数");
    }
}
