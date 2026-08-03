package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * Permission Rule 与 Session Grant 使用的规范化调用范围。
 *
 * <p>{@code source} 与 {@code value} 只能由可信 Tool Definition 和 Tool-specific
 * 提取器产生，而不能直接采信模型声明。空字符串表示 Tool-wide 范围；S05 对
 * {@code run_command} 禁止 Tool-wide Session Grant。值对象不保存文件正文、Prompt
 * 或 Secret。</p>
 *
 * @param toolName 稳定 Tool 名称
 * @param source Tool Definition 声明的可信注册来源
 * @param value 规范化 selector；空字符串表示整个 Tool
 * @since 0.5.0
 */
public record PermissionSelector(String toolName, ToolSource source, String value) {

    /** selector 文本硬上限。 */
    public static final int MAX_VALUE_CHARACTERS = 8_192;

    /** 校验并冻结 selector。 */
    public PermissionSelector {
        toolName = requireText(toolName, "toolName", 128);
        source = Objects.requireNonNull(source, "source 不能为空");
        value = Objects.requireNonNull(value, "value 不能为 null");
        if (value.codePointCount(0, value.length()) > MAX_VALUE_CHARACTERS
                || value.codePoints().anyMatch(character -> character == 0)) {
            throw new IllegalArgumentException("selector 过长或包含 NUL");
        }
    }

    /**
     * 创建 Tool-wide selector。
     *
     * @param toolName Tool 名称
     * @param source Tool Definition 声明的可信来源
     * @return 空范围 selector
     */
    public static PermissionSelector toolWide(String toolName, ToolSource source) {
        return new PermissionSelector(toolName, source, "");
    }

    /**
     * 判断本 selector 是否覆盖目标调用。
     *
     * @param invocation 已规范化目标
     * @return Tool 名称和可信来源相同，且本值为空或完全相等时为 {@code true}
     */
    public boolean matches(PermissionSelector invocation) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        return toolName.equals(invocation.toolName())
                && source == invocation.source()
                && (value.isEmpty() || value.equals(invocation.value()));
    }

    /**
     * 判断 selector 是否覆盖同一 Tool 与来源下的全部范围。
     *
     * @return value 为空字符串时为 {@code true}
     */
    public boolean toolWide() {
        return value.isEmpty();
    }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 为空、过长或包含控制字符");
        }
        return value;
    }
}
