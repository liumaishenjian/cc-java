package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;
import java.util.Locale;

/**
 * 把用户可读的 S05 Permission Mode 名称转换为 Domain 枚举。
 *
 * @since 0.5.0
 */
final class PermissionModeConverter implements ITypeConverter<PermissionMode> {

    @Override
    public PermissionMode convert(String value) {
        if (value == null) {
            throw new TypeConversionException("permission mode 不能为空");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "default" -> PermissionMode.DEFAULT;
            case "plan" -> PermissionMode.PLAN;
            case "accept-edits", "accept_edits" -> PermissionMode.ACCEPT_EDITS;
            default -> throw new TypeConversionException(
                    "permission mode 必须是 default、plan 或 accept-edits");
        };
    }
}
