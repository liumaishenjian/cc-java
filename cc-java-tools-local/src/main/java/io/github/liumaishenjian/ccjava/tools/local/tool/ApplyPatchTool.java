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
 * 以精确旧内容前置条件修改一个 Workspace UTF-8 普通文件。
 *
 * <p>默认要求旧内容只出现一次；多处替换必须显式设置 {@code replaceAll}。审批通过后
 * 仍会重新读取文件，并在原子移动前比较原始字节和真实路径，因此不会用过期 Patch
 * 覆盖用户或其他进程的并发修改。该 Tool 不删除文件、不创建目录、不格式化仓库。</p>
 *
 * @since 0.4.0
 */
public final class ApplyPatchTool implements AgentTool {

    private static final Set<String> ARGUMENTS =
            Set.of("path", "oldText", "newText", "replaceAll");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "apply_patch",
            "Replace exact UTF-8 text in one existing workspace-relative file.",
            """
            {"type":"object","additionalProperties":false,"required":["path","oldText","newText"],"properties":{"path":{"type":"string","minLength":1},"oldText":{"type":"string","minLength":1,"maxLength":524288},"newText":{"type":"string","maxLength":524288},"replaceAll":{"type":"boolean","default":false}}}
            """,
            ToolEffect.WRITE_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(5),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;

    /**
     * 创建绑定 Workspace 安全边界的 Patch Tool。
     *
     * @param guard 共享 WorkspaceGuard
     */
    public ApplyPatchTool(WorkspaceGuard guard) {
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
            String oldText = ToolArguments.string(arguments, "oldText", null);
            String newText = ToolArguments.string(arguments, "newText", null);
            ToolArguments.requireNonBlank("path", path);
            if (oldText == null || oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText 不能为空");
            }
            if (newText == null) {
                throw new IllegalArgumentException("newText 不能为空");
            }
            if (oldText.equals(newText)) {
                throw new IllegalArgumentException("oldText 与 newText 必须不同");
            }
            ToolArguments.requireMaximumCharacters(
                    "oldText", oldText, LocalToolLimits.MAX_PATCH_FRAGMENT_CHARACTERS);
            ToolArguments.requireMaximumCharacters(
                    "newText", newText, LocalToolLimits.MAX_PATCH_FRAGMENT_CHARACTERS);
            ToolArguments.rejectBinaryNull("oldText", oldText);
            ToolArguments.rejectBinaryNull("newText", newText);
            ToolArguments.bool(arguments, "replaceAll", false);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String path = ToolArguments.string(arguments, "path", null);
        String oldText = ToolArguments.string(arguments, "oldText", null);
        String newText = ToolArguments.string(arguments, "newText", null);
        boolean replaceAll = ToolArguments.bool(arguments, "replaceAll", false);
        try {
            if (invocation.cancellationToken().isCancellationRequested()) {
                return cancelled();
            }
            ValidatedWorkspacePath validated = guard.requireRegularFile(path);
            Utf8TextDocument original = Utf8TextReader.readDocument(
                    validated.realPath(), LocalToolLimits.MAX_TEXT_FILE_BYTES);
            int matches = countMatches(original.text(), oldText);
            if (matches == 0) {
                return conflict("oldText 在当前文件中不存在");
            }
            if (matches > 1 && !replaceAll) {
                return conflict("oldText 匹配多处；需要更多上下文或显式 replaceAll");
            }
            String updated = replace(
                    original.text(), oldText, newText, replaceAll);
            byte[] updatedBytes = Utf8TextEncoder.encode(updated, original.bom());
            if (updatedBytes.length > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                return ToolExecutionOutcome.failure(
                        ToolError.of(ToolErrorCode.FILE_TOO_LARGE, "修改后文件超过大小上限"));
            }
            AtomicUtf8FileWriter.replace(
                    validated.realPath(),
                    original.bytes(),
                    updatedBytes,
                    invocation.cancellationToken(),
                    () -> {
                        ValidatedWorkspacePath current = guard.requireRegularFile(path);
                        if (!current.realPath().equals(validated.realPath())) {
                            throw new WorkspaceAccessException(ToolError.of(
                                    ToolErrorCode.FILE_CONFLICT,
                                    "文件真实路径在写入前已改变"));
                        }
                    });
            PatchResultRenderer.Rendered rendered = PatchResultRenderer.render(
                    validated.protocolPath(),
                    "modified",
                    oldText,
                    newText,
                    replaceAll ? matches : 1);
            return ToolExecutionOutcome.success(rendered.content(), rendered.metadata());
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    private static int countMatches(String text, String target) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(target, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + target.length();
        }
    }

    private static String replace(
            String text,
            String oldText,
            String newText,
            boolean replaceAll) {
        if (replaceAll) {
            return text.replace(oldText, newText);
        }
        int index = text.indexOf(oldText);
        return text.substring(0, index)
                + newText
                + text.substring(index + oldText.length());
    }

    private static ToolExecutionOutcome conflict(String message) {
        return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.FILE_CONFLICT, message));
    }

    private static ToolExecutionOutcome cancelled() {
        return ToolExecutionOutcome.failure(
                ToolError.of(ToolErrorCode.OPERATION_CANCELLED, "文件操作已取消"));
    }
}
