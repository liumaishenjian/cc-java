package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * S05 内置 Tool 的独立、确定性 Permission Selector 提取器。
 *
 * <p>文件写入只提取规范化 Workspace-relative 路径；命令只接受经过 Tool 校验的完整
 * 命令正文。其它 Tool 使用 Tool-wide 范围。路径真实 containment 与敏感策略仍由
 * Hard Denial 和 Tool Adapter 独立复验。</p>
 *
 * @since 0.5.0
 */
public final class DefaultPermissionSelectorResolver implements PermissionSelectorResolver {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    /** 创建内置 Tool 的确定性 selector 提取器。 */
    public DefaultPermissionSelectorResolver() {
    }

    @Override
    public PermissionSelector resolve(
            ToolInvocation invocation,
            ToolDefinition definition) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(definition, "definition 不能为空");
        return switch (definition.name()) {
            case "apply_patch", "write_file" -> fileSelector(invocation, definition);
            case "run_command" -> commandSelector(invocation, definition);
            case "web_search" -> PermissionSelector.toolWide(definition.name(), definition.source());
            default -> PermissionSelector.toolWide(definition.name(), definition.source());
        };
    }


    private static PermissionSelector fileSelector(
            ToolInvocation invocation,
            ToolDefinition definition) {
        String toolName = definition.name();
        String raw;
        try {
            raw = invocation.call().arguments().string("path").orElse("");
        } catch (IllegalArgumentException exception) {
            return PermissionSelector.toolWide(toolName, definition.source());
        }
        if (raw.isBlank()
                || raw.startsWith("/")
                || raw.startsWith("\\")
                || WINDOWS_DRIVE.matcher(raw).matches()
                || raw.codePoints().anyMatch(Character::isISOControl)) {
            return PermissionSelector.toolWide(toolName, definition.source());
        }
        try {
            Path parsed = Path.of(raw.replace('\\', java.io.File.separatorChar)
                    .replace('/', java.io.File.separatorChar));
            if (parsed.isAbsolute()) {
                return PermissionSelector.toolWide(toolName, definition.source());
            }
            for (Path segment : parsed) {
                if ("..".equals(segment.toString())) {
                    return PermissionSelector.toolWide(toolName, definition.source());
                }
            }
            String normalized = parsed.normalize().toString()
                    .replace(java.io.File.separatorChar, '/');
            if (normalized.isBlank() || ".".equals(normalized)) {
                return PermissionSelector.toolWide(toolName, definition.source());
            }
            return new PermissionSelector(toolName, definition.source(), normalized);
        } catch (InvalidPathException exception) {
            return PermissionSelector.toolWide(toolName, definition.source());
        }
    }

    private static PermissionSelector commandSelector(
            ToolInvocation invocation,
            ToolDefinition definition) {
        String toolName = definition.name();
        try {
            String command = invocation.call().arguments().string("command").orElse("");
            if (command.isBlank()
                    || command.codePointCount(0, command.length())
                    > PermissionSelector.MAX_VALUE_CHARACTERS
                    || command.indexOf('\0') >= 0) {
                return PermissionSelector.toolWide(toolName, definition.source());
            }
            return new PermissionSelector(toolName, definition.source(), command);
        } catch (IllegalArgumentException exception) {
            return PermissionSelector.toolWide(toolName, definition.source());
        }
    }
}
