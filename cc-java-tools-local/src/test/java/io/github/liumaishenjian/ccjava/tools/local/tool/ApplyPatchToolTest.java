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
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyPatchToolTest {

    @TempDir
    Path workspace;

    @Test
    void replacesUniqueContextAndPreservesBomAndUnrelatedDirtyContent() throws Exception {
        byte[] body = "user-dirty\r\nold block\r\ntail\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = new byte[body.length + 3];
        original[0] = (byte) 0xEF;
        original[1] = (byte) 0xBB;
        original[2] = (byte) 0xBF;
        System.arraycopy(body, 0, original, 3, body.length);
        Path file = workspace.resolve("sample.txt");
        Files.write(file, original);
        ApplyPatchTool tool = new ApplyPatchTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "sample.txt",
                "oldText", "old block",
                "newText", "new block"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readAllBytes(file)).startsWith(
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("user-dirty\r\nnew block\r\ntail");
        assertThat(outcome.content())
                .contains("operation: modified", "- old block", "+ new block");
    }

    @Test
    void rejectsMissingOrAmbiguousContextWithoutChangingFile() throws Exception {
        Path file = Files.writeString(workspace.resolve("many.txt"), "same\nmiddle\nsame\n");
        ApplyPatchTool tool = new ApplyPatchTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome ambiguous = execute(tool, Map.of(
                "path", "many.txt",
                "oldText", "same",
                "newText", "changed"));
        ToolExecutionOutcome missing = execute(tool, Map.of(
                "path", "many.txt",
                "oldText", "absent",
                "newText", "changed"));

        assertThat(ambiguous.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(missing.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(Files.readString(file)).isEqualTo("same\nmiddle\nsame\n");
    }

    @Test
    void replacesAllOnlyWhenExplicitlyRequested() throws Exception {
        Path file = Files.writeString(workspace.resolve("all.txt"), "same\nsame\n");
        ApplyPatchTool tool = new ApplyPatchTool(new WorkspaceGuard(workspace));

        ToolExecutionOutcome outcome = execute(tool, Map.of(
                "path", "all.txt",
                "oldText", "same",
                "newText", "changed",
                "replaceAll", true));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("changed\nchanged\n");
        assertThat(outcome.content()).contains("replacements: 2");
    }

    @Test
    void refusesSensitiveTraversalOversizeAndCancelledCalls() throws Exception {
        Files.writeString(workspace.resolve(".env"), "secret=x");
        ApplyPatchTool tool = new ApplyPatchTool(new WorkspaceGuard(workspace));

        assertError(tool, Map.of(
                "path", ".env", "oldText", "x", "newText", "y"),
                ToolErrorCode.SENSITIVE_PATH, CancellationToken.none());
        assertError(tool, Map.of(
                "path", "../outside.txt", "oldText", "x", "newText", "y"),
                ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, CancellationToken.none());
        assertThat(tool.validate(new JsonObject(Map.of(
                "path", "x",
                "oldText", "a".repeat(512 * 1024 + 1),
                "newText", "b"))).valid()).isFalse();
        Path file = Files.writeString(workspace.resolve("cancel.txt"), "old");
        assertError(tool, Map.of(
                "path", "cancel.txt", "oldText", "old", "newText", "new"),
                ToolErrorCode.OPERATION_CANCELLED, cancelled());
        assertThat(Files.readString(file)).isEqualTo("old");
    }

    private static void assertError(
            ApplyPatchTool tool,
            Map<String, ?> arguments,
            ToolErrorCode expected,
            CancellationToken cancellation) {
        ToolExecutionOutcome outcome = execute(tool, arguments, cancellation);
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(expected);
    }

    private static ToolExecutionOutcome execute(
            ApplyPatchTool tool,
            Map<String, ?> arguments) {
        return execute(tool, arguments, CancellationToken.none());
    }

    private static ToolExecutionOutcome execute(
            ApplyPatchTool tool,
            Map<String, ?> arguments,
            CancellationToken cancellation) {
        ToolCall call = new ToolCall("call-1", "apply_patch", new JsonObject(arguments));
        return tool.execute(new ToolInvocation(
                new SessionId("session-1"),
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
