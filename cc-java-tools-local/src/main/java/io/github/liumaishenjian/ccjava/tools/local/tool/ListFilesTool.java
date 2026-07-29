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
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 在安全 Workspace 子树内有界枚举文件和目录。
 *
 * <p>遍历逐层读取并先对目录项排序，不跟随目录链接；每个候选项再次经过
 * {@link WorkspaceGuard}，因此 `.git`、敏感路径和外部链接不会进入结果。收集到
 * {@code maxResults + 1} 个匹配项就停止，既能显式报告截断，也不会先扫描完整仓库。</p>
 *
 * @since 0.3.0
 */
public final class ListFilesTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of(
            "path", "glob", "maxDepth", "maxResults");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_files",
            "List a bounded, sorted workspace file tree without following directory links.",
            """
            {"type":"object","additionalProperties":false,"properties":{"path":{"type":"string","default":"."},"glob":{"type":"string"},"maxDepth":{"type":"integer","minimum":0,"maximum":20,"default":8},"maxResults":{"type":"integer","minimum":1,"maximum":1000,"default":200}}}
            """,
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(5),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;

    /**
     * 创建绑定单个 Workspace 的枚举工具。
     *
     * @param guard 共享路径安全边界
     */
    public ListFilesTool(WorkspaceGuard guard) {
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
            ToolArguments.requireNonBlank("path", ToolArguments.string(arguments, "path", "."));
            String glob = ToolArguments.string(arguments, "glob", null);
            if (glob != null) {
                ProtocolGlob.compile(glob);
            }
            ToolArguments.requireRange("maxDepth",
                    ToolArguments.integer(arguments, "maxDepth", 8),
                    0, LocalToolLimits.MAX_LIST_DEPTH);
            ToolArguments.requireRange("maxResults",
                    ToolArguments.integer(arguments, "maxResults", 200),
                    1, LocalToolLimits.MAX_LIST_RESULTS);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String rootInput = ToolArguments.string(arguments, "path", ".");
        String glob = ToolArguments.string(arguments, "glob", null);
        int maxDepth = ToolArguments.integer(arguments, "maxDepth", 8);
        int maxResults = ToolArguments.integer(arguments, "maxResults", 200);
        try {
            ValidatedWorkspacePath root = guard.requireDirectory(rootInput);
            ProtocolGlob matcher = glob == null ? null : ProtocolGlob.compile(glob);
            Traversal traversal = new Traversal(root, matcher, maxDepth, maxResults);
            traversal.visit(root.realPath(), 0);
            return traversal.outcome();
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        } catch (IOException exception) {
            return ToolExecutionOutcome.failure(ToolError.of(
                    ToolErrorCode.EXECUTION_FAILED, "目录枚举失败"));
        }
    }

    private final class Traversal {
        private final ValidatedWorkspacePath root;
        private final ProtocolGlob matcher;
        private final int maxDepth;
        private final int maxResults;
        private final ArrayList<Entry> entries = new ArrayList<>();
        private long filtered;
        private boolean itemLimited;
        private boolean depthLimited;

        private Traversal(
                ValidatedWorkspacePath root,
                ProtocolGlob matcher,
                int maxDepth,
                int maxResults) {
            this.root = root;
            this.matcher = matcher;
            this.maxDepth = maxDepth;
            this.maxResults = maxResults;
        }

        private void visit(Path directory, int depth) throws IOException {
            if (itemLimited) {
                return;
            }
            List<Path> children;
            try (var stream = Files.list(directory)) {
                children = stream.sorted(Comparator.comparing(path ->
                                path.getFileName().toString()))
                        .toList();
            }
            for (Path child : children) {
                if (itemLimited) {
                    return;
                }
                Path relative = root.realPath().relativize(child);
                String protocol = join(root.protocolPath(), relative);
                ValidatedWorkspacePath validated;
                try {
                    validated = guard.requireExisting(protocol);
                } catch (WorkspaceAccessException exception) {
                    filtered++;
                    continue;
                }
                boolean directoryEntry = Files.isDirectory(
                        validated.realPath(), LinkOption.NOFOLLOW_LINKS);
                String matchValue = relative.toString().replace('\\', '/');
                if (matcher == null || matcher.matches(matchValue)) {
                    entries.add(new Entry(protocol, directoryEntry));
                    if (entries.size() > maxResults) {
                        itemLimited = true;
                        return;
                    }
                }
                if (directoryEntry) {
                    if (depth < maxDepth) {
                        visit(validated.realPath(), depth + 1);
                    } else if (hasChildren(validated.realPath())) {
                        depthLimited = true;
                    }
                }
            }
        }

        private ToolExecutionOutcome outcome() {
            List<Entry> visible = entries.size() > maxResults
                    ? entries.subList(0, maxResults)
                    : List.copyOf(entries);
            StringBuilder content = new StringBuilder("root: ")
                    .append(root.protocolPath()).append('\n');
            visible.forEach(entry -> content.append(entry.directory() ? "dir  " : "file ")
                    .append(entry.path()).append('\n'));
            boolean truncated = itemLimited || depthLimited;
            ToolResultTruncationReason reason = itemLimited
                    ? ToolResultTruncationReason.ITEM_LIMIT
                    : depthLimited
                            ? ToolResultTruncationReason.DEPTH_LIMIT
                            : ToolResultTruncationReason.NONE;
            String output = content.toString();
            return ToolExecutionOutcome.success(output, new ToolResultMetadata(
                    truncated,
                    reason,
                    output.codePointCount(0, output.length()),
                    OptionalLong.empty(),
                    visible.size(),
                    filtered,
                    truncated
                            ? new JsonObject(Map.of("path", root.protocolPath()))
                            : JsonObject.empty()));
        }

        private boolean hasChildren(Path directory) {
            try (var stream = Files.list(directory)) {
                return stream.findAny().isPresent();
            } catch (IOException exception) {
                filtered++;
                return false;
            }
        }
    }

    private static String join(String root, Path relative) {
        String child = relative.toString().replace('\\', '/');
        return root.equals(".") ? child : root + "/" + child;
    }

    private record Entry(String path, boolean directory) {
    }
}
