package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;
import java.util.Optional;

/**
 * Settings 读取或校验失败的隐私安全诊断。
 *
 * <p>本类型只携带固定分类和安全来源标识，不携带 JSON 原文、文件路径、凭证、端点或选择器正文。</p>
 *
 * @param sourceId 候选来源
 * @param code 固定失败分类
 * @param severity 严重程度
 * @param fieldPath 可选的受限字段路径
 * @since 0.8.0
 */
public record ConfigurationDiagnostic(SettingsSourceId sourceId, ConfigurationDiagnosticCode code,
                                      ConfigurationDiagnosticSeverity severity, Optional<SettingPath> fieldPath) {
    /** 冻结全部安全诊断组件，禁止空引用进入投影。 */
    public ConfigurationDiagnostic {
        sourceId = Objects.requireNonNull(sourceId, "sourceId 不能为空");
        code = Objects.requireNonNull(code, "code 不能为空");
        severity = Objects.requireNonNull(severity, "severity 不能为空");
        fieldPath = Objects.requireNonNull(fieldPath, "fieldPath 不能为空");
    }
}
