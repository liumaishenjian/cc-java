package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
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
    void rejectsUnknownArgumentsAndOutOfRangeLines() throws Exception {
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        assertThat(tool.validate(new JsonObject(Map.of("path", "x", "extra", true))).valid())
                .isFalse();
        assertThat(tool.validate(new JsonObject(Map.of(
                "path", "x", "maxLines", LocalToolLimits.MAX_READ_LINES + 1))).valid())
                .isFalse();
    }

    @Test
    void rejectsBinaryInvalidUtf8AndOversizedFiles() throws Exception {
        Files.write(workspace.resolve("binary.bin"), new byte[] {65, 0, 66});
        Files.write(workspace.resolve("invalid.txt"), new byte[] {(byte) 0xC3, 0x28});
        try (var output = Files.newOutputStream(workspace.resolve("large.txt"))) {
            output.write(new byte[(int) LocalToolLimits.MAX_TEXT_FILE_BYTES + 1]);
        }
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        assertError(tool, "binary.bin", ToolErrorCode.UNSUPPORTED_ENCODING);
        assertError(tool, "invalid.txt", ToolErrorCode.UNSUPPORTED_ENCODING);
        assertError(tool, "large.txt", ToolErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void returnsCorrectableErrorForStartPastEnd() throws Exception {
        Files.writeString(workspace.resolve("one.txt"), "only");
        ReadFileTool tool = new ReadFileTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of("path", "one.txt", "startLine", 2));

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(outcome.error().orElseThrow().details().values()).containsEntry("lineCount", 1);
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
        ToolCall call = new ToolCall("call-1", "read_file", new JsonObject(arguments));
        return tool.execute(new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                call));
    }
}
