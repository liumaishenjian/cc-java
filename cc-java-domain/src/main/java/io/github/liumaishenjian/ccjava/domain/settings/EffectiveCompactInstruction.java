package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.List;
import java.util.Objects;

/**
 * 最终 compact anchor 的首次来源与重复抑制证据。
 *
 * <p>锚点文本是 Context 输入，不得通过 {@link #toString()} 进入诊断或日志。</p>
 *
 * @param instruction 首次出现且保留顺序的 anchor
 * @param provenance 首次提供该 anchor 的来源
 * @param suppressedDuplicates 后续重复声明的来源证据
 * @since 0.8.0
 */
public record EffectiveCompactInstruction(String instruction, SettingProvenance provenance,
                                          List<DuplicateSuppressionProvenance> suppressedDuplicates) {
    /** 创建不可变的最终 anchor 及其完整来源证据。 */
    public EffectiveCompactInstruction {
        instruction = Objects.requireNonNull(instruction, "instruction 不能为空");
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
        suppressedDuplicates = List.copyOf(Objects.requireNonNull(suppressedDuplicates, "suppressedDuplicates 不能为空"));
    }

    @Override
    public String toString() {
        return "EffectiveCompactInstruction[instruction=<redacted>, provenance=" + provenance
                + ", suppressedDuplicates=" + suppressedDuplicates + "]";
    }
}
