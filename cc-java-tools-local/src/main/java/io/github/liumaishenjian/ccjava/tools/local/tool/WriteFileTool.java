package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 在安全、已存在的 Workspace 父目录中创建一个新的 UTF-8 文本文件。
 *
 * <p>首版明确不覆盖已有文件，也不隐式创建父目录；覆盖已有内容必须改用携带旧内容
 * 前置条件的 {@link ApplyPatchTool}。审批通过后仍会再次验证新文件路径，再以同目录
 * 临时文件 Move 完成创建。</p>
 *
 * @since 0.4.0
 */
public final class WriteFileTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("path", "content");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "write_file",
            "Create one new UTF-8 file under an existing workspace directory.",
            """
            {"type":"object","additionalProperties":false,"required":["path","content"],"properties":{"path":{"type":"string","minLength":1},"content":{"type":"string","maxLength":2097152}}}
            """,
            ToolEffect.WRITE_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(5),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;

    /**
     * 创建绑定 Workspace 安全边界的新文件 Tool。
     *
     * @param guard 共享 WorkspaceGuard
     */
    public WriteFileTool(WorkspaceGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
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
            String content = ToolArguments.string(arguments, "content", null);
            ToolArguments.requireNonBlank("path", path);
            if (content == null) {
                throw new IllegalArgumentException("content 不能为空");
            }
            ToolArguments.rejectBinaryNull("content", content);
            if (Utf8TextEncoder.encode(content, false).length
                    > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("content 超过 UTF-8 字节上限");
            }
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String path = ToolArguments.string(arguments, "path", null);
        String content = ToolArguments.string(arguments, "content", null);
        try {
            if (invocation.cancellationToken().isCancellationRequested()) {
                return ToolExecutionOutcome.failure(
                        ToolError.of(ToolErrorCode.OPERATION_CANCELLED, "文件操作已取消"));
            }
            ValidatedWorkspacePath validated = guard.requireNewFile(path);
            byte[] bytes = Utf8TextEncoder.encode(content, false);
            AtomicUtf8FileWriter.create(
                    validated.realPath(),
                    bytes,
                    invocation.cancellationToken(),
                    () -> {
                        ValidatedWorkspacePath current = guard.requireNewFile(path);
                        if (!current.realPath().equals(validated.realPath())) {
                            throw new WorkspaceAccessException(ToolError.of(
                                    ToolErrorCode.FILE_CONFLICT,
                                    "新文件真实父目录在写入前已改变"));
                        }
                    });
            PatchResultRenderer.Rendered rendered = PatchResultRenderer.render(
                    validated.protocolPath(),
                    "created",
                    "",
                    content,
                    1);
            return ToolExecutionOutcome.success(rendered.content(), rendered.metadata());
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }
}
