package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 按 1-based 行范围读取 Workspace 内的受控 UTF-8 普通文件。
 *
 * <p>路径先经 {@link WorkspaceGuard} 验证；读取在字节 ceiling 内完成，支持 UTF-8 BOM，
 * 拒绝二进制和非法编码。输出只使用相对协议路径，并通过 metadata 给出下一页行号。</p>
 *
 * @since 0.3.0
 */
public final class ReadFileTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("path", "startLine", "maxLines");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "read_file",
            "Read a bounded range of UTF-8 text lines from a workspace-relative file.",
            """
            {"type":"object","additionalProperties":false,"required":["path"],"properties":{"path":{"type":"string","minLength":1},"startLine":{"type":"integer","minimum":1,"default":1},"maxLines":{"type":"integer","minimum":1,"maximum":500,"default":200}}}
            """,
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(5),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;

    /**
     * 创建绑定单个真实 Workspace 的读取工具。
     *
     * @param guard 共享路径安全边界
     */
    public ReadFileTool(WorkspaceGuard guard) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            ToolArguments.rejectUnknown(arguments, ARGUMENTS);
            String path = ToolArguments.string(arguments, "path", null);
            ToolArguments.requireNonBlank("path", path);
            int startLine = ToolArguments.integer(arguments, "startLine", 1);
            int maxLines = ToolArguments.integer(arguments, "maxLines", 200);
            ToolArguments.requireRange("startLine", startLine, 1, Integer.MAX_VALUE);
            ToolArguments.requireRange(
                    "maxLines", maxLines, 1, LocalToolLimits.MAX_READ_LINES);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String path = ToolArguments.string(arguments, "path", null);
        int startLine = ToolArguments.integer(arguments, "startLine", 1);
        int maxLines = ToolArguments.integer(arguments, "maxLines", 200);
        try {
            ValidatedWorkspacePath validated = guard.requireRegularFile(path);
            String text = Utf8TextReader.read(
                    validated.realPath(), LocalToolLimits.MAX_TEXT_FILE_BYTES);
            return render(validated.protocolPath(), text, startLine, maxLines);
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    private static ToolExecutionOutcome render(
            String protocolPath,
            String text,
            int startLine,
            int maxLines) {
        List<String> lines = splitLines(text);
        if (startLine > lines.size() && !(lines.isEmpty() && startLine == 1)) {
            return ToolExecutionOutcome.failure(new io.github.liumaishenjian.ccjava.domain.ToolError(
                    io.github.liumaishenjian.ccjava.domain.ToolErrorCode.INVALID_ARGUMENTS,
                    "startLine 超过文件行数",
                    new JsonObject(Map.of("lineCount", lines.size()))));
        }
        int startIndex = Math.min(startLine - 1, lines.size());
        int endIndex = Math.min(lines.size(), startIndex + maxLines);
        StringBuilder output = new StringBuilder();
        output.append("path: ").append(protocolPath).append('\n');
        for (int index = startIndex; index < endIndex; index++) {
            output.append(index + 1).append(" | ").append(lines.get(index)).append('\n');
        }
        boolean truncated = endIndex < lines.size();
        JsonObject continuation = truncated
                ? new JsonObject(Map.of("path", protocolPath, "startLine", endIndex + 1))
                : JsonObject.empty();
        String content = output.toString();
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                truncated,
                truncated ? ToolResultTruncationReason.LINE_LIMIT
                        : ToolResultTruncationReason.NONE,
                content.codePointCount(0, content.length()),
                OptionalLong.empty(),
                endIndex - startIndex,
                0,
                continuation));
    }

    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String[] raw = text.split("\\R", -1);
        int size = raw.length;
        if (size > 0 && raw[size - 1].isEmpty()
                && (text.endsWith("\n") || text.endsWith("\r"))) {
            size--;
        }
        ArrayList<String> lines = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            lines.add(raw[index]);
        }
        return List.copyOf(lines);
    }
}
