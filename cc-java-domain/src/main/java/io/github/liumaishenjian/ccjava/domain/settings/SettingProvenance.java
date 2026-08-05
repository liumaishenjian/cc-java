package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 单个 Settings 值或删除操作的来源说明。
 *
 * @param sourceId 安全来源标识
 * @param precedence 低到高合并中的来源序号
 * @param operation 已应用操作
 * @param validationStatus 来源完整校验的类型化结论
 * @since 0.8.0
 */
public record SettingProvenance(SettingsSourceId sourceId, int precedence, SettingOperation operation,
                                SettingValidationStatus validationStatus) {
    /**
     * 创建单个最终值或删除操作的来源说明。
     *
     * @param sourceId 不含物理路径和正文的安全来源标识
     * @param precedence 固定来源优先级序号
     * @param operation 已应用的声明操作
     * @param validationStatus 来源的完整校验结论
     */
    public SettingProvenance {
        sourceId = Objects.requireNonNull(sourceId, "sourceId 不能为空");
        operation = Objects.requireNonNull(operation, "operation 不能为空");
        validationStatus = Objects.requireNonNull(validationStatus, "validationStatus 不能为空");
        if (precedence < 0 || precedence > 5) throw new IllegalArgumentException("precedence 超出范围");
    }
}
