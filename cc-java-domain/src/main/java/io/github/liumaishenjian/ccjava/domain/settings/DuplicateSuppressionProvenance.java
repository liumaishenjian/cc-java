package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 已存在 compact anchor 被高优先级重复声明时保留的来源证据。
 *
 * <p>抑制不会改变锚点的首次位置和首次 provenance；该记录仅说明后续输入被确定性忽略。</p>
 *
 * @param provenance 产生重复项的有效来源操作
 * @since 0.8.0
 */
public record DuplicateSuppressionProvenance(SettingProvenance provenance) {
    /** 创建不可为空的重复抑制证据。 */
    public DuplicateSuppressionProvenance {
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
    }
}
