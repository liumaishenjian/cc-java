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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 返回当前分支及 staged、unstaged、untracked 的安全 Git 状态摘要。
 *
 * <p>原始 porcelain 路径必须再次通过 Guard；敏感或无法安全解析的条目只计入 filtered，
 * 不进入模型正文。</p>
 *
 * @since 0.3.0
 */
public final class GitStatusTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "git_status",
            "Show a bounded branch and workspace change summary using fixed read-only Git arguments.",
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(10),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;
    private final GitReadClient git;

    /**
     * 创建固定 Workspace 的 Git 状态工具。
     *
     * @param guard 共享路径安全边界
     * @param git 固定只读 Git Adapter
     */
    public GitStatusTool(WorkspaceGuard guard, GitReadClient git) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard 不能为空");
        this.git = java.util.Objects.requireNonNull(git, "git 不能为空");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        return arguments.values().isEmpty()
                ? ToolValidationResult.validResult()
                : ToolValidationResult.invalid("git_status 不接受参数");
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        try {
            return summarize(git.status().stdout());
        } catch (GitReadClient.GitReadException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    private ToolExecutionOutcome summarize(String porcelain) {
        String branch = "unknown";
        ArrayList<String> staged = new ArrayList<>();
        ArrayList<String> unstaged = new ArrayList<>();
        ArrayList<String> untracked = new ArrayList<>();
        long filtered = 0;
        for (String line : porcelain.lines().toList()) {
            if (line.startsWith("## ")) {
                branch = line.substring(3).strip();
                continue;
            }
            if (line.length() < 4) {
                filtered++;
                continue;
            }
            String rawPath = line.substring(3);
            int rename = rawPath.indexOf(" -> ");
            String target = rename >= 0 ? rawPath.substring(rename + 4) : rawPath;
            target = unquote(target);
            String safePath;
            try {
                safePath = guard.requireSafeGitPath(target);
            } catch (WorkspaceAccessException exception) {
                filtered++;
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            if (index == '?' && worktree == '?') {
                untracked.add(safePath);
            } else {
                if (index != ' ') {
                    staged.add(index + " " + safePath);
                }
                if (worktree != ' ') {
                    unstaged.add(worktree + " " + safePath);
                }
            }
        }
        staged.sort(String::compareTo);
        unstaged.sort(String::compareTo);
        untracked.sort(String::compareTo);
        StringBuilder output = new StringBuilder("branch: ").append(branch).append('\n');
        append(output, "staged", staged);
        append(output, "unstaged", unstaged);
        append(output, "untracked", untracked);
        String content = output.toString();
        long returned = staged.size() + unstaged.size() + untracked.size();
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                false,
                ToolResultTruncationReason.NONE,
                content.codePointCount(0, content.length()),
                OptionalLong.empty(),
                returned,
                filtered,
                JsonObject.empty()));
    }

    private static void append(StringBuilder output, String label, List<String> entries) {
        output.append(label).append(" (").append(entries.size()).append("):").append('\n');
        entries.forEach(entry -> output.append("  ").append(entry).append('\n'));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }
}
