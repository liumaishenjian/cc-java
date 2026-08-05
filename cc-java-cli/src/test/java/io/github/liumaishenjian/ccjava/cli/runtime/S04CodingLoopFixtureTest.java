package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 以公开、确定性的最小 Java 仓库验证 S04 完整 Coding Loop。
 *
 * <p>Scripted Model 必须消费每一步真实 Tool Result，而不是直接修改 Fixture。测试故意
 * 先提出越权 Patch，再提交一个会使验收失败的修复，随后依据非零退出证据纠正代码。
 * 由此同时证明 Permission、读取、Patch、Command、失败恢复和 Git Diff 仍经过同一
 * Runtime/Pipeline。</p>
 */
class S04CodingLoopFixtureTest {

    private static final String FIXTURE = "fixtures/s04-coding-loop";
    private static final String TEST_COMMAND = "java src/Calculator.java --self-test";

    @TempDir
    Path sessionStoreRoot;

    private Path workspace;

    @BeforeEach
    void createWorkspaceBelowBuildDirectory() throws Exception {
        Path fixtureRoot = Path.of("target", "s04-fixture-workspaces")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(fixtureRoot);
        workspace = Files.createTempDirectory(fixtureRoot, "coding-loop-");
    }

    @AfterEach
    void removeWorkspace() throws Exception {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5 && Files.exists(workspace); attempt++) {
            try {
                deleteWorkspaceTree();
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                Thread.sleep(50L * (attempt + 1));
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    private void deleteWorkspaceTree() throws IOException {
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (AccessDeniedException exception) {
                    if (!path.toFile().setWritable(true)) {
                        throw exception;
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void rejectsScopeEscapeThenRecoversFromFailedTestAndCompletesCodingLoop()
            throws Exception {
        copyFixture(workspace);
        byte[] forbiddenBaseline = Files.readAllBytes(workspace.resolve("DO_NOT_EDIT.txt"));
        initializeGitRepository(workspace);
        ScriptedCodingModel model = new ScriptedCodingModel(lineSeparator(
                Files.readString(
                        workspace.resolve("src/Calculator.java"), StandardCharsets.UTF_8)));
        List<String> approvalCalls = new ArrayList<>();

        AgentRunResult run;
        try (HeadlessRuntimeSession session = new HeadlessRuntimeSession(
                model,
                AgentEventSink.noop(),
                new HeadlessRuntimeOptions(
                        workspace,
                        "s04-scripted-fixture",
                        Duration.ofSeconds(30),
                        PermissionMode.DEFAULT,
                        List.of(),
                        SessionOpenRequest.create(),
                        sessionStoreRoot),
                (invocation, ignoredDefinition, ignoredOutcome) -> {
                    approvalCalls.add(invocation.call().id());
                    String path = invocation.call().arguments().string("path").orElse("");
                    String command = invocation.call().arguments().string("command").orElse("");
                    boolean allowedPatch = invocation.call().name().equals("apply_patch")
                            && path.equals("src/Calculator.java");
                    boolean allowedCommand = invocation.call().name().equals("run_command")
                            && command.equals(TEST_COMMAND);
                    return allowedPatch || allowedCommand
                            ? io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce()
                            : io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny();
                })) {
            session.open();
            run = session.run("按照 TASK.md 修复代码，测试失败时根据证据继续修复。");
        }

        assertThat(run.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(run.finalText()).hasValueSatisfying(text ->
                assertThat(text).contains("测试通过", "仅修改 src/Calculator.java"));
        assertThat(run.toolCalls()).isEqualTo(9);
        assertThat(model.requests()).hasSize(10);
        assertThat(approvalCalls).containsExactly(
                "call-forbidden",
                "call-buggy-divide",
                "call-add-tests",
                "call-failing-test",
                "call-correct-patch",
                "call-passing-test");
        assertThat(Files.readString(
                workspace.resolve("src/Calculator.java"), StandardCharsets.UTF_8))
                .contains(
                        "static int divide(int dividend, int divisor)",
                        "throw new IllegalArgumentException(",
                        "\"divisor must not be zero\"",
                        "expected zero-divisor exception")
                .doesNotContain("return divisor == 0 ? 0");
        assertThat(Files.readAllBytes(workspace.resolve("DO_NOT_EDIT.txt")))
                .isEqualTo(forbiddenBaseline);
        assertThat(git(workspace, "status", "--short"))
                .contains("M src/Calculator.java")
                .doesNotContain("DO_NOT_EDIT.txt", "TASK.md", "AGENTS.md");
    }

    private static String lineSeparator(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static final class ScriptedCodingModel implements ModelGateway {

        private final List<ModelRequest> requests = new ArrayList<>();
        private final String newline;

        private ScriptedCodingModel(String newline) {
            this.newline = newline;
        }

        @Override
        public ModelTurn complete(ModelRequest request) {
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> tool("call-task", "read_file", Map.of("path", "TASK.md"));
                case 2 -> {
                    assertSuccess(request, "call-task")
                            .contains(
                                    "divide(int, int)",
                                    "IllegalArgumentException",
                                    "只允许修改",
                                    "DO_NOT_EDIT.txt",
                                    "git_diff");
                    yield tool(
                            "call-source",
                            "read_file",
                            Map.of("path", "src/Calculator.java"));
                }
                case 3 -> {
                    assertSuccess(request, "call-source")
                            .contains("return left + right;")
                            .doesNotContain("static int divide");
                    yield tool(
                            "call-forbidden",
                            "apply_patch",
                            Map.of(
                                    "path", "DO_NOT_EDIT.txt",
                                    "oldText", "Keep it unchanged.",
                                    "newText", "Tampered."));
                }
                case 4 -> {
                    assertResult(request, "call-forbidden")
                            .satisfies(result -> {
                                assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
                                assertThat(result.error()).isPresent();
                            });
                    yield tool(
                            "call-buggy-divide",
                            "apply_patch",
                            Map.of(
                                    "path", "src/Calculator.java",
                                    "oldText",
                                    lines(
                                            "        return left + right;",
                                            "    }",
                                            "",
                                            "    public static void main"),
                                    "newText",
                                    lines(
                                            "        return left + right;",
                                            "    }",
                                            "",
                                            "    static int divide(int dividend, int divisor) {",
                                            "        return divisor == 0 ? 0 : dividend / divisor;",
                                            "    }",
                                            "",
                                            "    public static void main")));
                }
                case 5 -> {
                    assertSuccess(request, "call-buggy-divide")
                            .contains("operation: modified", "src/Calculator.java");
                    yield tool(
                            "call-add-tests",
                            "apply_patch",
                            Map.of(
                                    "path", "src/Calculator.java",
                                    "oldText",
                                    "        System.out.println(\"ACCEPTANCE_OK\");",
                                    "newText",
                                    lines(
                                            "        int quotient = divide(8, 2);",
                                            "        if (quotient != 4) {",
                                            "            throw new AssertionError(",
                                            "                    \"expected quotient 4 but was \" + quotient);",
                                            "        }",
                                            "        try {",
                                            "            divide(1, 0);",
                                            "            throw new AssertionError(",
                                            "                    \"expected zero-divisor exception\");",
                                            "        } catch (IllegalArgumentException expected) {",
                                            "            if (!\"divisor must not be zero\".equals(",
                                            "                    expected.getMessage())) {",
                                            "                throw new AssertionError(",
                                            "                        \"unexpected exception message\");",
                                            "            }",
                                            "        }",
                                            "        System.out.println(\"ACCEPTANCE_OK\");")));
                }
                case 6 -> {
                    assertSuccess(request, "call-add-tests")
                            .contains("operation: modified", "src/Calculator.java");
                    yield tool(
                            "call-failing-test",
                            "run_command",
                            Map.of("command", TEST_COMMAND, "timeoutSeconds", 20));
                }
                case 7 -> {
                    assertSuccess(request, "call-failing-test")
                            .contains(
                                    "timedOut: false",
                                    "cancelled: false",
                                    "expected zero-divisor exception")
                            .doesNotContain("exitCode: 0");
                    yield tool(
                            "call-correct-patch",
                            "apply_patch",
                            Map.of(
                                    "path", "src/Calculator.java",
                                    "oldText",
                                    "        return divisor == 0 ? 0 : dividend / divisor;",
                                    "newText",
                                    lines(
                                            "        if (divisor == 0) {",
                                            "            throw new IllegalArgumentException(",
                                            "                    \"divisor must not be zero\");",
                                            "        }",
                                            "        return dividend / divisor;")));
                }
                case 8 -> {
                    assertSuccess(request, "call-correct-patch")
                            .contains("operation: modified", "src/Calculator.java");
                    yield tool(
                            "call-passing-test",
                            "run_command",
                            Map.of("command", TEST_COMMAND, "timeoutSeconds", 20));
                }
                case 9 -> {
                    assertSuccess(request, "call-passing-test")
                            .contains("exitCode: 0", "ACCEPTANCE_OK");
                    yield tool("call-diff", "git_diff", Map.of("mode", "unstaged"));
                }
                case 10 -> {
                    assertSuccess(request, "call-diff")
                            .contains(
                                    "static int divide",
                                    "divisor must not be zero",
                                    "expected zero-divisor exception")
                            .doesNotContain("DO_NOT_EDIT.txt", "Tampered.");
                    yield ModelTurn.text(
                            "修复完成：测试通过，且仅修改 src/Calculator.java。");
                }
                default -> throw new AssertionError("Scripted Model 收到意外额外回合");
            };
        }

        List<ModelRequest> requests() {
            return List.copyOf(requests);
        }

        private String lines(String... values) {
            return String.join(newline, values);
        }

        private static ModelTurn tool(
                String id,
                String name,
                Map<String, ?> arguments) {
            return new ModelTurn(
                    AssistantMessage.tools(List.of(
                            new ToolCall(id, name, new JsonObject(arguments)))),
                    ModelTurnMetadata.unknown());
        }

        private static org.assertj.core.api.AbstractStringAssert<?> assertSuccess(
                ModelRequest request,
                String callId) {
            ToolResult result = result(request, callId);
            assertThat(result.status())
                    .as(
                            "callId=%s, errorCode=%s, content=%s",
                            callId,
                            result.error().map(error -> error.code().name()).orElse("none"),
                            result.content())
                    .isEqualTo(ToolResultStatus.SUCCESS);
            return assertThat(result.content());
        }

        private static org.assertj.core.api.ObjectAssert<ToolResult> assertResult(
                ModelRequest request,
                String callId) {
            return assertThat(result(request, callId));
        }

        private static ToolResult result(ModelRequest request, String callId) {
            return request.messages().stream()
                    .filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast)
                    .map(ToolResultMessage::result)
                    .filter(result -> result.callId().equals(callId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "缺少 Tool Result: " + callId));
        }
    }

    private static void copyFixture(Path destination) throws Exception {
        URI fixtureUri = Objects.requireNonNull(
                S04CodingLoopFixtureTest.class.getClassLoader().getResource(FIXTURE),
                "S04 Fixture 资源不存在").toURI();
        Path source = Path.of(fixtureUri);
        try (var paths = Files.walk(source)) {
            for (Path input : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path output = destination.resolve(source.relativize(input).toString());
                if (Files.isDirectory(input)) {
                    Files.createDirectories(output);
                } else {
                    Files.write(output, Files.readAllBytes(input));
                }
            }
        }
    }

    private static void initializeGitRepository(Path directory) throws Exception {
        git(directory, "init");
        git(directory, "config", "user.name", "S04 Fixture");
        git(directory, "config", "user.email", "s04-fixture@example.invalid");
        git(directory, "add", ".");
        git(directory, "commit", "-m", "fixture baseline");
    }

    private static String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = directory.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Fixture Git failed (" + exitCode + "): " + output);
        }
        return output;
    }
}
