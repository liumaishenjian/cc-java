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
import io.github.liumaishenjian.ccjava.tools.local.text.BoundedTextRange;
import io.github.liumaishenjian.ccjava.tools.local.text.BoundedTextRangeReader;
import io.github.liumaishenjian.ccjava.tools.local.text.ReadEvidence;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 按 1-based 行范围有界读取 Workspace 内的受控 UTF-8 普通文件。
 *
 * <p>路径先经 {@link WorkspaceGuard} 验证；随后由 {@link BoundedTextRangeReader} 以固定
 * 字节窗口流式解码，只保留被请求的那一页，因此单次调用的内存占用与文件大小无关。这也
 * 意味着在明确请求有界范围时，本 Tool 可以读取超过整文件读取上限的大文件，但仍受独立的
 * 扫描字节 ceiling、单行字符预算、Tool 期限和取消信号约束。</p>
 *
 * <p>结果头部给出模型可直接据此继续读取的结构化证据：协议路径、起始行、返回行数、
 * 是否仍有后续内容以及下一页起始行。总行数与总字节数<b>只有</b>在本次扫描确实到达文件
 * 末尾时才出现，绝不用当前页统计冒充整份文件。渲染前会按 Tool Definition 声明的字符上限
 * 预算本页正文，使 Pipeline 不必再次裁剪，从而保证继续读取契约不被破坏。</p>
 *
 * <p>同一 Session 重复请求同一路径同一范围且文件身份未变时，返回轻量“未变化”结果而不
 * 重复正文；此时仍完整保留是否有后续内容与下一页起始行，不会把部分结果说成完整结果。</p>
 *
 * @since 0.3.0
 */
public final class ReadFileTool implements AgentTool {

    private static final Set<String> ARGUMENTS = Set.of("path", "startLine", "maxLines");

    /** 为结构化头部和继续提示预留的字符余量。 */
    private static final int RENDER_RESERVE_CHARACTERS = 2_000;

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
    private final WorkspaceReadRegistry readRegistry;
    private final BoundedTextRangeReader rangeReader;

    /**
     * 创建带独立 Read 登记表的读取工具，供只需要只读能力的调用方使用。
     *
     * @param guard 共享路径安全边界
     */
    public ReadFileTool(WorkspaceGuard guard) {
        this(guard, new WorkspaceReadRegistry());
    }

    /**
     * 创建与写工具共享同一 Read 登记表的读取工具。
     *
     * <p>共享是写工具“修改前必须已读过”前置条件成立的唯一途径：登记表由 Composition
     * Root 组合注入，Tool 之间不互相持有引用，也不感知彼此的存在。</p>
     *
     * @param guard 共享路径安全边界
     * @param readRegistry 与 ApplyPatch/Write 共享的有界 Read 证据登记表
     */
    public ReadFileTool(WorkspaceGuard guard, WorkspaceReadRegistry readRegistry) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
        this.readRegistry = Objects.requireNonNull(readRegistry, "readRegistry 不能为空");
        this.rangeReader = new BoundedTextRangeReader(
                LocalToolLimits.MAX_RANGE_SCAN_BYTES,
                LocalToolLimits.MAX_READ_LINE_CHARACTERS,
                LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);
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
            BoundedTextRange range = rangeReader.read(
                    validated.realPath(),
                    startLine,
                    maxLines,
                    invocation.cancellationToken());
            if (range.lines().isEmpty() && startPastEnd(startLine, range)) {
                return ToolExecutionOutcome.failure(new ToolError(
                        ToolErrorCode.INVALID_ARGUMENTS,
                        "startLine 超过文件行数",
                        new JsonObject(Map.of(
                                "lineCount", range.totalLines().orElse(0L)))));
            }
            Rendered rendered = renderWithinBudget(validated.protocolPath(), range);
            Optional<FileIdentity> identity = FileIdentity.of(validated.realPath());
            if (identity.isPresent()) {
                if (unchangedEvidence(
                        invocation, validated.protocolPath(), rendered, identity.get())) {
                    return renderUnchanged(validated.protocolPath(), rendered);
                }
                recordEvidence(
                        invocation, validated.protocolPath(), rendered, identity.get());
            }
            return ToolExecutionOutcome.success(rendered.content, rendered.metadata);
        } catch (WorkspaceAccessException exception) {
            return ToolExecutionOutcome.failure(exception.error());
        }
    }

    private static boolean startPastEnd(int startLine, BoundedTextRange range) {
        if (range.totalLines().isEmpty()) {
            return false;
        }
        long totalLines = range.totalLines().getAsLong();
        return totalLines == 0 ? startLine > 1 : startLine > totalLines;
    }

    /**
     * 在 Tool Definition 声明的字符预算内渲染本页，并据此重新绑定继续读取契约。
     *
     * <p>渲染是继续读取信息的最终权威：若预算只容得下前 N 行，则本页只返回 N 行，
     * 并把下一页起始行改为第 N+1 行。这样 Pipeline 不会因为超限再次裁剪，模型也不会
     * 收到“正文缺少若干行、但 nextStartLine 已经跳过它们”的错位结果。</p>
     */
    private static Rendered renderWithinBudget(String protocolPath, BoundedTextRange range) {
        int budget = LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS - RENDER_RESERVE_CHARACTERS;
        List<String> lines = range.lines();
        StringBuilder body = new StringBuilder();
        int emitted = 0;
        boolean budgetTruncated = false;
        for (String line : lines) {
            String prefix = (range.firstLine() + emitted) + " | ";
            int cost = prefix.length() + line.length() + 1;
            if (emitted > 0 && body.length() + cost > budget) {
                budgetTruncated = true;
                break;
            }
            body.append(prefix).append(line).append('\n');
            emitted++;
        }
        boolean hasMore = range.hasMore() || budgetTruncated;
        int nextStartLine = budgetTruncated
                ? range.firstLine() + emitted
                : (range.hasMore() ? range.nextStartLine() : 0);
        boolean completeFile = !hasMore
                && range.firstLine() == 1
                && range.totalLines().isPresent()
                && range.truncatedLines() == 0;
        OptionalLong totalLines = hasMore ? OptionalLong.empty() : range.totalLines();
        OptionalLong totalBytes = hasMore ? OptionalLong.empty() : range.totalBytes();

        StringBuilder output = new StringBuilder();
        output.append("path: ").append(protocolPath).append('\n');
        output.append("startLine: ").append(range.firstLine()).append('\n');
        output.append("returnedLines: ").append(emitted).append('\n');
        output.append("hasMore: ").append(hasMore).append('\n');
        if (hasMore) {
            output.append("nextStartLine: ").append(nextStartLine).append('\n');
        }
        totalLines.ifPresent(value -> output.append("totalLines: ").append(value).append('\n'));
        totalBytes.ifPresent(value -> output.append("totalBytes: ").append(value).append('\n'));
        if (range.truncatedLines() > 0) {
            output.append("truncatedLines: ").append(range.truncatedLines()).append('\n');
        }
        if (range.scanCeilingReached()) {
            output.append("scanCeilingReached: true").append('\n');
        }
        output.append(body);

        ToolResultTruncationReason reason;
        if (!hasMore && range.truncatedLines() == 0) {
            reason = ToolResultTruncationReason.NONE;
        } else if (range.scanCeilingReached() && !budgetTruncated) {
            reason = ToolResultTruncationReason.SCAN_BYTE_LIMIT;
        } else {
            reason = ToolResultTruncationReason.LINE_LIMIT;
        }
        boolean truncated = reason != ToolResultTruncationReason.NONE;
        JsonObject continuation = hasMore
                ? new JsonObject(continuationValues(protocolPath, nextStartLine))
                : JsonObject.empty();
        String content = output.toString();
        ToolResultMetadata metadata = new ToolResultMetadata(
                truncated,
                reason,
                content.codePointCount(0, content.length()),
                OptionalLong.empty(),
                emitted,
                range.truncatedLines(),
                continuation);
        return new Rendered(
                content,
                metadata,
                range.firstLine(),
                emitted,
                hasMore,
                nextStartLine,
                completeFile,
                range.truncatedLines() == 0,
                totalLines,
                totalBytes,
                continuation,
                ReadEvidence.digestOf(String.join("\n", lines.subList(0, emitted))));
    }

    private static Map<String, Object> continuationValues(String protocolPath, int nextStartLine) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("path", protocolPath);
        values.put("startLine", nextStartLine);
        return values;
    }

    /**
     * 复用上一次相同范围的读取结果，只返回不含正文的轻量确认。
     *
     * <p>该结果仍然携带 {@code hasMore} 与 {@code nextStartLine}，因此不会把部分结果
     * 误报成完整结果；模型若需要正文，可以直接使用上一次同范围结果。</p>
     */
    private static ToolExecutionOutcome renderUnchanged(String protocolPath, Rendered rendered) {
        StringBuilder output = new StringBuilder();
        output.append("path: ").append(protocolPath).append('\n');
        output.append("startLine: ").append(rendered.firstLine).append('\n');
        output.append("returnedLines: ").append(rendered.returnedLines).append('\n');
        output.append("hasMore: ").append(rendered.hasMore).append('\n');
        if (rendered.hasMore) {
            output.append("nextStartLine: ").append(rendered.nextStartLine).append('\n');
        }
        rendered.totalLines.ifPresent(
                value -> output.append("totalLines: ").append(value).append('\n'));
        rendered.totalBytes.ifPresent(
                value -> output.append("totalBytes: ").append(value).append('\n'));
        output.append("unchanged: true\n");
        output.append("note: identical range already returned in this session; body omitted\n");
        String content = output.toString();
        ToolResultTruncationReason reason = rendered.hasMore
                ? ToolResultTruncationReason.LINE_LIMIT
                : ToolResultTruncationReason.NONE;
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                reason != ToolResultTruncationReason.NONE,
                reason,
                content.codePointCount(0, content.length()),
                OptionalLong.empty(),
                rendered.returnedLines,
                0,
                rendered.continuation));
    }

    private boolean unchangedEvidence(
            ToolInvocation invocation,
            String protocolPath,
            Rendered rendered,
            FileIdentity identity) {
        if (!rendered.exactRange || rendered.returnedLines == 0) {
            return false;
        }
        return readRegistry.find(invocation.sessionId(), protocolPath)
                .filter(evidence -> evidence.firstLine() == rendered.firstLine
                        && evidence.lastLine() == rendered.lastLine()
                        && evidence.completeFile() == rendered.completeFile
                        && evidence.sizeBytes() == identity.sizeBytes
                        && evidence.lastModifiedMillis() == identity.lastModifiedMillis
                        && evidence.contentDigest() == rendered.digest)
                .isPresent();
    }

    private void recordEvidence(
            ToolInvocation invocation,
            String protocolPath,
            Rendered rendered,
            FileIdentity identity) {
        if (!rendered.exactRange || rendered.returnedLines == 0) {
            // 只有完整、未被单行预算截断的区间才是可信写入前置条件。
            return;
        }
        readRegistry.record(invocation.sessionId(), new ReadEvidence(
                protocolPath,
                rendered.firstLine,
                rendered.lastLine(),
                rendered.completeFile,
                identity.sizeBytes,
                identity.lastModifiedMillis,
                rendered.digest));
    }

    /** 渲染结果与由它推导出的权威继续读取信息。 */
    private record Rendered(
            String content,
            ToolResultMetadata metadata,
            int firstLine,
            int returnedLines,
            boolean hasMore,
            int nextStartLine,
            boolean completeFile,
            boolean exactRange,
            OptionalLong totalLines,
            OptionalLong totalBytes,
            JsonObject continuation,
            long digest) {

        private int lastLine() {
            return firstLine + returnedLines - 1;
        }
    }

    /**
     * 读取瞬间的文件身份。
     *
     * <p>身份不可得时不登记也不去重：宁可让模型重新读取，也不能用不确定的身份
     * 冒充可信写入前置条件。</p>
     */
    private record FileIdentity(long sizeBytes, long lastModifiedMillis) {

        private static Optional<FileIdentity> of(java.nio.file.Path path) {
            try {
                return Optional.of(new FileIdentity(
                        Files.size(path),
                        Files.getLastModifiedTime(path).toMillis()));
            } catch (IOException exception) {
                return Optional.empty();
            }
        }
    }
}
