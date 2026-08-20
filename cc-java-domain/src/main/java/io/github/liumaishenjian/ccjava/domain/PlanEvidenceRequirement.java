package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 规划期间由受控 Tool 声明的一项交付或验证要求。
 *
 * <p>{@code locator} 对交付物是 Workspace-relative 普通文件，对验证是可信 Tool 名；它不是
 * 命令、Markdown 片段或可执行步骤。Runtime 只按种类执行固定验证，绝不解析 Plan 正文。</p>
 *
 * @param requirementId Plan 内稳定、无隐私语义的短 ID
 * @param kind 证据种类
 * @param locator 相对路径或 Tool 名
 * @param label 用户可读的有界说明
 * @param required 是否阻止 Plan 完成
 * @since 0.1.0
 */
public record PlanEvidenceRequirement(String requirementId, PlanEvidenceKind kind, String locator,
                                      String label, boolean required) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** 验证标识、定位符和说明上限。 */
    public PlanEvidenceRequirement {
        requirementId = Objects.requireNonNull(requirementId, "requirementId 不能为空");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        locator = bounded(locator, "locator", 512);
        label = bounded(label, "label", 512);
        if (!ID.matcher(requirementId).matches()) throw new IllegalArgumentException("requirementId 格式无效");
        if (locator.startsWith("/") || locator.startsWith("\\") || locator.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("locator 无效");
        }
        if (kind == PlanEvidenceKind.VERIFICATION && !locator.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("验证 locator 必须是可信 Tool 名");
        }
    }

    private static String bounded(String value, String field, int maxCodePoints) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints
                || value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '	')) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return value;
    }
}
