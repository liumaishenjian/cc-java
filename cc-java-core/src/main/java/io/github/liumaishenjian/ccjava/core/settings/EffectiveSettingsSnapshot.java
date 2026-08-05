package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import java.util.Objects;

/**
 * 已发布的完整有效 Settings 与单调发布版本。
 *
 * <p>该快照只在全部来源完成读取、校验和合并后创建。版本只描述本进程内的发布次序，
 * 不暴露文件路径、来源字节或持久化状态。</p>
 *
 * @param revision 本进程内单调递增的发布版本
 * @param settings 完整且不可变的最终 Settings
 * @since 0.8.0
 */
public record EffectiveSettingsSnapshot(long revision, EffectiveSettings settings) {
    /**
     * 验证发布版本和完整投影。
     *
     * @param revision 大于零的已发布版本
     * @param settings 全部来源合并成功后的 Settings
     */
    public EffectiveSettingsSnapshot {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision 必须为正数");
        }
        settings = Objects.requireNonNull(settings, "settings 不能为空");
    }

    @Override
    public String toString() {
        return "EffectiveSettingsSnapshot[revision=" + revision + ", settings=<redacted>]";
    }
}
