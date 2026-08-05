package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.List;
import java.util.Objects;

/**
 * 一个来源完成严格解析后才可发布的原子 Settings 快照。
 *
 * @param sourceId 安全来源标识
 * @param revision 来源版本摘要
 * @param declaredValues 已完整校验的声明值
 * @param diagnostics 无正文诊断
 * @since 0.8.0
 */
public record SettingsSourceSnapshot(SettingsSourceId sourceId, SettingsRevision revision,
                                     DeclaredSettings declaredValues, List<ConfigurationDiagnostic> diagnostics) {
    /**
     * 创建只能整体发布或整体拒绝的来源快照。
     *
     * @param sourceId 不含物理路径的安全来源标识
     * @param revision 来源字节的非敏感摘要
     * @param declaredValues 完整校验后的声明值
     * @param diagnostics 不携带 Settings 正文的固定诊断
     */
    public SettingsSourceSnapshot {
        sourceId = Objects.requireNonNull(sourceId, "sourceId 不能为空");
        revision = Objects.requireNonNull(revision, "revision 不能为空");
        declaredValues = Objects.requireNonNull(declaredValues, "declaredValues 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
    }

    @Override
    public String toString() {
        return "SettingsSourceSnapshot[sourceId=" + sourceId + ", revision=" + revision
                + ", declaredValues=<redacted>, diagnostics=" + diagnostics + "]";
    }
}
