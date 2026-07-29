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
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 在受控 Workspace 文本文件中执行有界字面搜索。
 *
 * <p>S03 不接受正则表达式。遍历逐层排序且不跟随目录链接，候选文件逐一经过 Guard、大小和
 * 严格 UTF-8 校验；达到文件数、扫描字节或匹配数预算时立即停止并显式标记原因。</p>
 *
 * @since 0.3.0
 */
public final class SearchTextTool implements AgentTool {

    private static final int MAX_SNIPPET_CODE_POINTS = 240;
    private static final Set<String> ARGUMENTS = Set.of(
            "query", "path", "glob", "caseSensitive", "maxResults");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "search_text",
            "Search a bounded set of workspace UTF-8 files for literal text.",
            """
            {"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":1024},"path":{"type":"string","default":"."},"glob":{"type":"string"},"caseSensitive":{"type":"boolean","default":true},"maxResults":{"type":"integer","minimum":1,"maximum":500,"default":100}}}
            """,
            ToolEffect.READ_WORKSPACE,
            ToolSource.BUILT_IN,
            false,
            Duration.ofSeconds(10),
            "text/plain",
            LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);

    private final WorkspaceGuard guard;

    /**
     * 创建绑定 Workspace 的字面搜索工具。
     *
     * @param guard 共享路径安全边界
     */
    public SearchTextTool(WorkspaceGuard guard) {
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
            String query = ToolArguments.string(arguments, "query", null);
            ToolArguments.requireNonBlank("query", query);
            if (query.codePointCount(0, query.length()) > 1024) {
                throw new IllegalArgumentException("query 超过 1024 字符");
            }
            ToolArguments.requireNonBlank("path", ToolArguments.string(arguments, "path", "."));
            String glob = ToolArguments.string(arguments, "glob", null);
            if (glob != null) {
                ProtocolGlob.compile(glob);
            }
            ToolArguments.bool(arguments, "caseSensitive", true);
            ToolArguments.requireRange("maxResults",
                    ToolArguments.integer(arguments, "maxResults", 100),
                    1, LocalToolLimits.MAX_SEARCH_RESULTS);
            return ToolValidationResult.validResult();
        } catch (IllegalArgumentException exception) {
            return ToolValidationResult.invalid(exception.getMessage());
        }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String query = ToolArguments.string(arguments, "query", null);
        String rootInput = ToolArguments.string(arguments, "path", ".");
        String glob = ToolArguments.string(arguments, "glob", null);
        boolean caseSensitive = ToolArguments.bool(arguments, "caseSensitive", true);
        int maxResults = ToolArguments.integer(arguments, "maxResults", 100);
        try {
            ValidatedWorkspacePath root = guard.requireDirectory(rootInput);
            Search traversal = new Search(
                    root,
                    query,
                    glob == null ? null : ProtocolGlob.compile(glob),
                    caseSensitive,
                    maxResults);
            traversal.visit(root.realPath());
            return traversal.outcome();
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        } catch (IOException exception) {
            return ToolExecutionOutcome.failure(ToolError.of(
                    ToolErrorCode.EXECUTION_FAILED, "文本搜索失败"));
        }
    }

    private final class Search {
        private final ValidatedWorkspacePath root;
        private final String expected;
        private final ProtocolGlob matcher;
        private final boolean caseSensitive;
        private final int maxResults;
        private final ArrayList<Match> matches = new ArrayList<>();
        private long scannedBytes;
        private long filtered;
        private int scannedFiles;
        private ToolResultTruncationReason limitReason = ToolResultTruncationReason.NONE;

        private Search(
                ValidatedWorkspacePath root,
                String query,
                ProtocolGlob matcher,
                boolean caseSensitive,
                int maxResults) {
            this.root = root;
            this.expected = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
            this.matcher = matcher;
            this.caseSensitive = caseSensitive;
            this.maxResults = maxResults;
        }

        private void visit(Path directory) throws IOException {
            if (limited()) {
                return;
            }
            List<Path> children;
            try (var stream = Files.list(directory)) {
                children = stream.sorted(Comparator.comparing(path ->
                                path.getFileName().toString()))
                        .toList();
            }
            for (Path child : children) {
                if (limited()) {
                    return;
                }
                String protocol = protocol(root, child);
                ValidatedWorkspacePath validated;
                try {
                    validated = guard.requireExisting(protocol);
                } catch (WorkspaceAccessException exception) {
                    filtered++;
                    continue;
                }
                if (Files.isDirectory(validated.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    visit(validated.realPath());
                } else if (Files.isRegularFile(validated.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    searchFile(validated);
                }
            }
        }

        private void searchFile(ValidatedWorkspacePath validated) {
            String relative = root.realPath().relativize(validated.realPath())
                    .toString().replace('\\', '/');
            if (matcher != null && !matcher.matches(relative)) {
                return;
            }
            if (scannedFiles >= LocalToolLimits.MAX_SEARCH_FILES) {
                limitReason = ToolResultTruncationReason.FILE_LIMIT;
                return;
            }
            long size;
            try {
                size = Files.size(validated.realPath());
            } catch (IOException exception) {
                filtered++;
                return;
            }
            if (size > LocalToolLimits.MAX_TEXT_FILE_BYTES) {
                filtered++;
                return;
            }
            if (scannedBytes + size > LocalToolLimits.MAX_SEARCH_BYTES) {
                limitReason = ToolResultTruncationReason.SCAN_BYTE_LIMIT;
                return;
            }
            String text;
            try {
                text = Utf8TextReader.read(
                        validated.realPath(), LocalToolLimits.MAX_TEXT_FILE_BYTES);
            } catch (WorkspaceAccessException exception) {
                filtered++;
                return;
            }
            scannedFiles++;
            scannedBytes += size;
            List<String> lines = text.isEmpty() ? List.of() : text.lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                String compared = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                if (compared.contains(expected)) {
                    matches.add(new Match(validated.protocolPath(), index + 1, snippet(line)));
                    if (matches.size() >= maxResults) {
                        limitReason = ToolResultTruncationReason.ITEM_LIMIT;
                        return;
                    }
                }
            }
        }

        private boolean limited() {
            return limitReason != ToolResultTruncationReason.NONE;
        }

        private ToolExecutionOutcome outcome() {
            StringBuilder output = new StringBuilder();
            for (Match match : matches) {
                output.append(match.path()).append(':').append(match.line()).append(": ")
                        .append(match.snippet()).append('\n');
            }
            output.append("summary: matches=").append(matches.size())
                    .append(" files=").append(scannedFiles)
                    .append(" bytes=").append(scannedBytes).append('\n');
            String content = output.toString();
            boolean truncated = limited();
            return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                    truncated,
                    limitReason,
                    content.codePointCount(0, content.length()),
                    OptionalLong.empty(),
                    matches.size(),
                    filtered,
                    truncated
                            ? new JsonObject(Map.of("path", root.protocolPath()))
                            : JsonObject.empty()));
        }
    }

    private static String snippet(String line) {
        String normalized = line.strip();
        int points = normalized.codePointCount(0, normalized.length());
        if (points <= MAX_SNIPPET_CODE_POINTS) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(
                0, MAX_SNIPPET_CODE_POINTS - 1)) + "…";
    }

    private static String protocol(ValidatedWorkspacePath root, Path candidate) {
        String child = root.realPath().relativize(candidate).toString().replace('\\', '/');
        return root.protocolPath().equals(".") ? child : root.protocolPath() + "/" + child;
    }

    private record Match(String path, int line, String snippet) {
    }
}
