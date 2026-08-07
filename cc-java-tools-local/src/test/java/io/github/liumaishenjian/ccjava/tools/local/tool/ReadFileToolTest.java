package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @TempDir
    Path workspace;

    @Test
    void readsUtf8BomWithLineNumbersAndContinuation() throws Exception {
        byte[] body = "第一行\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        Files.write(workspace.resolve("sample.txt"), bytes);
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "sample.txt",
                "startLine", 1,
                "maxLines", 2));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content()).contains("1 | 第一行", "2 | second");
        assertThat(outcome.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.LINE_LIMIT);
        assertThat(outcome.metadata().continuation().values())
                .containsEntry("startLine", 3);
    }

    @Test
    void reportsStructuredEvidenceAndOnlyKnownTotals() throws Exception {
        Files.writeString(workspace.resolve("five.txt"), "a\nb\nc\nd\ne\n");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome partial = execute(tool, Map.of(
                "path", "five.txt", "startLine", 2, "maxLines", 2));
        ToolExecutionOutcome complete = execute(tool, Map.of(
                "path", "five.txt", "startLine", 1, "maxLines", 200));

        assertThat(partial.content())
                .contains(
                        "path: five.txt",
                        "startLine: 2",
                        "returnedLines: 2",
                        "hasMore: true",
                        "nextStartLine: 4",
                        "2 | b",
                        "3 | c")
                .doesNotContain("totalLines:", "totalBytes:");
        assertThat(complete.content())
                .contains("hasMore: false", "totalLines: 5", "totalBytes: 10");
        assertThat(complete.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.NONE);
    }

    @Test
    void continuationWalksWholeFileWithoutGapsOrRepeats() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 12; line++) {
            body.append("line-").append(line).append('\n');
        }
        Files.writeString(workspace.resolve("walk.txt"), body.toString());
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        int startLine = 1;
        StringBuilder collected = new StringBuilder();
        int pages = 0;
        while (true) {
            ToolExecutionOutcome outcome = execute(tool, Map.of(
                    "path", "walk.txt", "startLine", startLine, "maxLines", 5));
            assertThat(outcome.successful()).isTrue();
            outcome.content().lines()
                    .filter(line -> line.contains(" | "))
                    .forEach(line -> collected
                            .append(line.substring(line.indexOf(" | ") + 3))
                            .append('\n'));
            pages++;
            Object next = outcome.metadata().continuation().values().get("startLine");
            if (next == null) {
                break;
            }
            startLine = ((Number) next).intValue();
        }

        assertThat(pages).isEqualTo(3);
        assertThat(collected.toString()).isEqualTo(body.toString());
    }

    @Test
    void readsBoundedRangeFromFileAboveWholeFileCeiling() throws Exception {
        Path file = workspace.resolve("large-range.txt");
        long target = 3L * 1024 * 1024;
        long written = 0;
        int line = 0;
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(file))) {
            while (written < target) {
                byte[] row = ("row-" + (++line) + "-" + "y".repeat(48) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                output.write(row);
                written += row.length;
            }
        }
        assertThat(Files.size(file)).isGreaterThan(LocalToolLimits.MAX_TEXT_FILE_BYTES);
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "large-range.txt", "startLine", 3, "maxLines", 2));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content()).contains("3 | row-3-", "4 | row-4-", "hasMore: true");
        assertThat(outcome.content()).doesNotContain("totalLines:");
    }

    @Test
    void preBudgetsRenderedPageSoPipelineNeverBreaksContinuation() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 500; line++) {
            body.append("x".repeat(600)).append('\n');
        }
        Files.writeString(workspace.resolve("wide.txt"), body.toString());
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "wide.txt", "startLine", 1, "maxLines", 500));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content().codePointCount(0, outcome.content().length()))
                .isLessThanOrEqualTo(LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);
        assertThat(outcome.content()).contains("hasMore: true");
        int returnedLines = Integer.parseInt(headerValue(outcome.content(), "returnedLines"));
        int nextStartLine = Integer.parseInt(headerValue(outcome.content(), "nextStartLine"));
        assertThat(returnedLines).isLessThan(500);
        assertThat(nextStartLine).isEqualTo(returnedLines + 1);
        assertThat(outcome.content()).contains(returnedLines + " | ");
        assertThat(outcome.content()).doesNotContain((returnedLines + 1) + " | ");
    }

    @Test
    void returnsUnchangedResultForRepeatedIdenticalRangeInSameSession() throws Exception {
        Files.writeString(workspace.resolve("dedup.txt"), "a\nb\nc\n");
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace), registry);

        ToolExecutionOutcome first = execute(tool, Map.of(
                "path", "dedup.txt", "startLine", 1, "maxLines", 2));
        ToolExecutionOutcome second = execute(tool, Map.of(
                "path", "dedup.txt", "startLine", 1, "maxLines", 2));

        assertThat(first.content()).contains("1 | a", "2 | b");
        assertThat(second.content())
                .contains("unchanged: true", "hasMore: true", "nextStartLine: 3")
                .doesNotContain("1 | a");
        assertThat(second.metadata().truncationReason())
                .isEqualTo(ToolResultTruncationReason.LINE_LIMIT);
        assertThat(second.metadata().continuation().values()).containsEntry("startLine", 3);
    }

    @Test
    void repeatsBodyForOtherSessionAndAfterFileChanges() throws Exception {
        Path file = Files.writeString(workspace.resolve("changed.txt"), "a\nb\nc\n");
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace), registry);
        Map<String, Object> arguments = Map.of(
                "path", "changed.txt", "startLine", 1, "maxLines", 2);

        execute(tool, arguments, "session-1");
        ToolExecutionOutcome otherSession = execute(tool, arguments, "session-2");
        Files.writeString(file, "a\nB!\nc\n");
        Files.setLastModifiedTime(
                file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        ToolExecutionOutcome afterChange = execute(tool, arguments, "session-1");

        assertThat(otherSession.content()).contains("1 | a", "2 | b");
        assertThat(afterChange.content()).contains("1 | a", "2 | B!");
        assertThat(afterChange.content()).doesNotContain("unchanged: true");
    }

    @Test
    void rejectsUnknownArgumentsAndOutOfRangeLines() throws Exception {
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        assertThat(tool.validate(new JsonObject(Map.of("path", "x", "extra", true))).valid())
                .isFalse();
        assertThat(tool.validate(new JsonObject(Map.of(
                "path", "x", "maxLines", LocalToolLimits.MAX_READ_LINES + 1))).valid())
                .isFalse();
    }

    @Test
    void rejectsBinaryAndInvalidUtf8() throws Exception {
        Files.write(workspace.resolve("binary.bin"), new byte[] {65, 0, 66});
        Files.write(workspace.resolve("invalid.txt"), new byte[] {(byte) 0xC3, 0x28});
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        assertError(tool, "binary.bin", ToolErrorCode.UNSUPPORTED_ENCODING);
        assertError(tool, "invalid.txt", ToolErrorCode.UNSUPPORTED_ENCODING);
    }

    @Test
    void refusesSensitiveAndEscapingPaths() throws Exception {
        Files.writeString(workspace.resolve(".env"), "secret=x");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        assertError(tool, ".env", ToolErrorCode.SENSITIVE_PATH);
        assertError(tool, "../outside.txt", ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
        assertError(tool, "missing.txt", ToolErrorCode.PATH_NOT_FOUND);
    }

    @Test
    void honoursCancellation() throws Exception {
        Files.writeString(workspace.resolve("cancel.txt"), "a\nb\n");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(
                tool, Map.of("path", "cancel.txt"), "session-1", cancelled());

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.OPERATION_CANCELLED);
    }

    @Test
    void returnsCorrectableErrorForStartPastEnd() throws Exception {
        Files.writeString(workspace.resolve("one.txt"), "only");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of("path", "one.txt", "startLine", 2));

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(outcome.error().orElseThrow().details().values()).containsEntry("lineCount", 1L);
    }

    @Test
    void readsEmptyFileAtFirstLineWithoutError() throws Exception {
        Files.writeString(workspace.resolve("empty.txt"), "");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of("path", "empty.txt"));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content()).contains("returnedLines: 0", "totalLines: 0");
    }

    @Test
    void boundsSingleHugeLine() throws Exception {
        Files.writeString(
                workspace.resolve("huge-line.txt"), "q".repeat(200_000) + "\ntail\n");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of("path", "huge-line.txt"));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content()).contains("truncatedLines: 1");
        assertThat(outcome.metadata().filteredItems()).isEqualTo(1);
        assertThat(outcome.content().codePointCount(0, outcome.content().length()))
                .isLessThanOrEqualTo(LocalToolLimits.MAX_TOOL_OUTPUT_CHARACTERS);
    }

    private static String headerValue(String content, String key) {
        return content.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring(key.length() + 2))
                .findFirst()
                .orElseThrow();
    }

    private static void assertError(
            ReadFileTool tool,
            String path,
            ToolErrorCode code) {
        ToolExecutionOutcome outcome = execute(tool, Map.of("path", path));
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(code);
    }

    private static ToolExecutionOutcome execute(ReadFileTool tool, Map<String, ?> arguments) {
        return execute(tool, arguments, "session-1");
    }

    private static ToolExecutionOutcome execute(
            ReadFileTool tool,
            Map<String, ?> arguments,
            String sessionId) {
        return execute(tool, arguments, sessionId, CancellationToken.none());
    }

    private static ToolExecutionOutcome execute(
            ReadFileTool tool,
            Map<String, ?> arguments,
            String sessionId,
            CancellationToken cancellation) {
        ToolCall call = new ToolCall("call-1", "read_file", new JsonObject(arguments));
        return tool.execute(new ToolInvocation(
                new SessionId(sessionId),
                new RunId("run-1"),
                1,
                call,
                cancellation));
    }

    private static CancellationToken cancelled() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            @Override
            public Registration onCancellation(Runnable action) {
                action.run();
                return () -> {
                };
            }
        };
    }
}
