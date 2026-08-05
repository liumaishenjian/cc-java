package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SettingsResolver 的原子成功或失败结果。
 *
 * <p>失败结果永远不携带部分 {@link EffectiveSettings}，调用者必须保留此前已发布的完整快照。</p>
 *
 * @param effectiveSettings 成功时唯一的完整投影
 * @param diagnostics 无正文的固定失败或合并诊断
 * @since 0.8.0
 */
public record SettingsResolution(Optional<EffectiveSettings> effectiveSettings,
                                 List<ConfigurationDiagnostic> diagnostics) {
    /** 冻结结果，防止调用者观察到可变诊断集合。 */
    public SettingsResolution {
        effectiveSettings = Objects.requireNonNull(effectiveSettings, "effectiveSettings 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        if (effectiveSettings.isEmpty() == diagnostics.isEmpty()) {
            throw new IllegalArgumentException("成功结果必须无诊断，失败结果必须有诊断");
        }
    }

    /**
     * 创建不含合并失败诊断的完整成功结果。
     *
     * @param settings 已完成所有来源合并的不可变投影
     * @return 只包含完整投影的成功结果
     */
    public static SettingsResolution success(EffectiveSettings settings) {
        return new SettingsResolution(Optional.of(Objects.requireNonNull(settings, "settings 不能为空")), List.of());
    }

    /**
     * 创建不泄漏部分最终值的原子失败结果。
     *
     * @param diagnostics 至少一个隐私安全的固定失败诊断
     * @return 只包含失败诊断的结果
     */
    public static SettingsResolution failure(List<ConfigurationDiagnostic> diagnostics) {
        return new SettingsResolution(Optional.empty(), diagnostics);
    }

    @Override
    public String toString() {
        return "SettingsResolution[effectiveSettings=" + (effectiveSettings.isPresent() ? "<redacted>" : "<absent>")
                + ", diagnostics=" + diagnostics + "]";
    }
}
