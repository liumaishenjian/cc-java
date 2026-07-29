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
import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 使用固定 Git 参数读取 staged 或 unstaged 文本 Diff。
 *
 * <p>可选 path 在加入 `--` 后的 pathspec 前先经 Guard；模型不能提供 Git 选项。包含 Git
 * binary patch 的结果只返回固定摘要，不把原始二进制编码反馈给模型。</p>
 *
 * @since 0.3.0
 */
public final class GitDiffTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("mode", "path");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "git_diff",
            "Show bounded staged or unstaged diff using fixed read-only Git arguments.",
            """
            {"type":"object","additionalProperties":false,"properties":{"mode":{"type":"string","enum":["unstaged","staged"],"default":"unstaged"},"path":{"type":"string"}}}
            """,
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(10),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;
    private final GitReadClient git;

    /**
     * 创建固定 Workspace 的 Git Diff 工具。
     *
     * @param guard 共享路径安全边界
     * @param git 固定只读 Git Adapter
     */
    public GitDiffTool(WorkspaceGuard guard, GitReadClient git) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard 不能为空");
        this.git = java.util.Objects.requireNonNull(git, "git 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            ToolArguments.rejectUnknown(arguments, ARGUMENTS);
            String mode = ToolArguments.string(arguments, "mode", "unstaged");
            if (!mode.equals("unstaged") && !mode.equals("staged")) {
                throw new IllegalArgumentException("mode 必须是 unstaged 或 staged");
            }
            String path = ToolArguments.string(arguments, "path", null);
            if (path != null) {
                ToolArguments.requireNonBlank("path", path);
            }
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        boolean staged = ToolArguments.string(arguments, "mode", "unstaged").equals("staged");
        String path = ToolArguments.string(arguments, "path", null);
        try {
            String safePath = path == null ? null : guard.requireSafeGitPath(path);
            String diff = git.diff(staged, safePath).stdout();
            long filtered = 0;
            if (diff.contains("GIT binary patch") || diff.contains("Binary files ")) {
                diff = "[binary diff omitted]\n";
                filtered = 1;
            }
            String content = "mode: " + (staged ? "staged" : "unstaged") + "\n" + diff;
            return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                    false,
                    ToolResultTruncationReason.NONE,
                    content.codePointCount(0, content.length()),
                    OptionalLong.empty(),
                    diff.isEmpty() ? 0 : 1,
                    filtered,
                    JsonObject.empty()));
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        } catch (GitReadClient.GitReadException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }
}
