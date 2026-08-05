package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.DeterministicInstructionDiscovery;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionDiscovery;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionDiscoveryRequest;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoader;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 S08 A2 指令投影只在短生命周期模型请求中存在，并保持原子刷新。 */
class InstructionProjectionStateTest {

    @TempDir
    Path temporary;

    @Test
    void projectsUserProjectAndLocalOnceWithoutLeakingBodiesOutsideTransientRequest() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.writeString(home.resolve(".cc-java/instructions/AGENTS.md"), "USER_SENTINEL");
        Files.writeString(workspace.resolve("AGENTS.md"), "PROJECT_SENTINEL");
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/AGENTS.local.md"), "LOCAL_SENTINEL");

        InstructionProjectionState state = state(home, workspace, ignoredLocalPolicy(workspace));
        ModelRequest canonical = canonical();
        ModelRequest projected = state.project(canonical, CancellationToken.none());
        String body = system(projected);

        assertThat(body).containsSubsequence("USER_SENTINEL", "PROJECT_SENTINEL", "LOCAL_SENTINEL");
        assertThat(body).containsOnlyOnce("USER_SENTINEL");
        assertThat(body).containsOnlyOnce("PROJECT_SENTINEL");
        assertThat(body).containsOnlyOnce("LOCAL_SENTINEL");
        assertThat(canonical.messages()).noneMatch(message -> message.toString().contains("SENTINEL"));
        assertThat(canonical.toString()).doesNotContain("SENTINEL");
        assertThat(state.latestRevision().orElseThrow()).doesNotContain("SENTINEL");
    }

    @Test
    void successfulFixedPathCallsActivateReverifiedTargetsOnNextProjection() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.createDirectories(workspace.resolve("module/deep"));
        Files.writeString(workspace.resolve("module/AGENTS.md"), "MODULE_SENTINEL");
        Files.writeString(workspace.resolve("module/deep/AGENTS.md"), "DEEP_SENTINEL");
        Files.writeString(workspace.resolve("module/deep/code.txt"), "code");

        assertActivatesOnNextProjection(home, workspace, "read_file", Map.of("path", "module/deep/code.txt"));
        assertActivatesOnNextProjection(home, workspace, "write_file", Map.of("path", "module/deep/code.txt"));
        assertActivatesOnNextProjection(home, workspace, "apply_patch", Map.of("path", "module/deep/code.txt"));
        assertActivatesOnNextProjection(home, workspace, "list_files", Map.of("path", "module/deep"));
        assertActivatesOnNextProjection(home, workspace, "search_text", Map.of("path", "module/deep"));
    }

    @Test
    void deniedFailedMismatchedMissingAndInertResultsDoNotActivateTargets() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.createDirectories(workspace.resolve("module/deep"));
        Files.writeString(workspace.resolve("module/AGENTS.md"), "MODULE_SENTINEL");
        Files.writeString(workspace.resolve("module/deep/AGENTS.md"), "DEEP_SENTINEL");
        Files.writeString(workspace.resolve("module/deep/code.txt"), "code");
        InstructionProjectionState state = state(home, workspace);

        state.recordSuccessfulTool(call("denied", "write_file", Map.of("path", "module/deep/code.txt")),
                ToolResult.denied("denied", "write_file", "result text"), CancellationToken.none());
        state.recordSuccessfulTool(call("failed", "apply_patch", Map.of("path", "module/deep/code.txt")),
                ToolResult.failure("failed", "apply_patch", ToolError.of(ToolErrorCode.FILE_CONFLICT, "result text")),
                CancellationToken.none());
        state.recordSuccessfulTool(call("mismatch", "apply_patch", Map.of("path", "module/deep/code.txt")),
                ToolResult.success("other", "apply_patch", "result text"), CancellationToken.none());
        state.recordSuccessfulTool(call("missing", "apply_patch", Map.of()),
                ToolResult.success("missing", "apply_patch", "module/deep/code.txt"), CancellationToken.none());
        state.recordSuccessfulTool(call("command", "run_command", Map.of("path", "module/deep")),
                ToolResult.success("command", "run_command", "module/deep/code.txt"), CancellationToken.none());
        assertThat(system(state.project(canonical(), CancellationToken.none())))
                .doesNotContain("MODULE_SENTINEL", "DEEP_SENTINEL");
    }

    @Test
    void directoryHitMissDepthAndByteChangesPublishOnlyCompleteNextSnapshot() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.createDirectories(workspace.resolve("a/b"));
        Files.writeString(workspace.resolve("a/AGENTS.md"), "A_ONE");
        Files.writeString(workspace.resolve("a/b/AGENTS.md"), "B_ONE");
        Files.writeString(workspace.resolve("a/b/file.txt"), "file");
        InstructionProjectionState state = state(home, workspace);
        ToolCall hit = call("hit", "list_files", Map.of("path", "a/b"));
        state.recordSuccessfulTool(hit, ToolResult.success("hit", "list_files", "unused"), CancellationToken.none());
        ModelRequest first = state.project(canonical(), CancellationToken.none());
        String firstRevision = state.latestRevision().orElseThrow();
        assertThat(system(first)).containsSubsequence("A_ONE", "B_ONE");

        Files.writeString(workspace.resolve("a/b/AGENTS.md"), "B_TWO_WITH_CHANGED_BYTES");
        ModelRequest changed = state.project(canonical(), CancellationToken.none());
        String changedRevision = state.latestRevision().orElseThrow();
        assertThat(system(changed)).contains("B_TWO_WITH_CHANGED_BYTES");
        assertThat(changedRevision).isNotEqualTo(firstRevision);

        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel();
        assertThat(system(state.project(canonical(), cancelled.token())))
                .contains("B_TWO_WITH_CHANGED_BYTES");
        assertThat(state.latestRevision()).isNotEqualTo(java.util.Optional.of(firstRevision));
    }

    @Test
    void discoveryFailurePreservesPreviousCompleteSnapshotWithoutPartialText() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java/instructions"));
        Files.writeString(workspace.resolve("AGENTS.md"), "STABLE_SENTINEL");
        InstructionFoundationFactory.InstructionFoundation real = InstructionFoundationFactory.open(home, workspace);
        AtomicBoolean fail = new AtomicBoolean();
        InstructionDiscovery discovery = (request, token) -> {
            if (fail.get()) {
                throw new IllegalStateException("adapter failure must not publish partial content");
            }
            return real.discovery().discover(request, token);
        };
        InstructionProjectionState state = new InstructionProjectionState(new InstructionFoundationFactory.InstructionFoundation(
                real.userRoot(), real.workspaceGuard(), real.gitIgnorePolicy(), real.userLoader(),
                real.workspaceLoader(), real.planner(), discovery));
        assertThat(system(state.project(canonical(), CancellationToken.none()))).contains("STABLE_SENTINEL");
        fail.set(true);
        assertThat(system(state.project(canonical(), CancellationToken.none())))
                .contains("STABLE_SENTINEL").doesNotContain("partial content");
    }

    private static void assertActivatesOnNextProjection(
            Path home, Path workspace, String toolName, Map<String, String> arguments) throws Exception {
        InstructionProjectionState state = state(home, workspace);
        assertThat(system(state.project(canonical(), CancellationToken.none())))
                .doesNotContain("MODULE_SENTINEL", "DEEP_SENTINEL");
        state.recordSuccessfulTool(call("call-" + toolName, toolName, arguments),
                ToolResult.success("call-" + toolName, toolName, "untrusted result text"), CancellationToken.none());
        assertThat(system(state.project(canonical(), CancellationToken.none())))
                .containsSubsequence("MODULE_SENTINEL", "DEEP_SENTINEL");
    }

    private static InstructionProjectionState state(Path home, Path workspace) throws Exception {
        return new InstructionProjectionState(InstructionFoundationFactory.open(home, workspace));
    }

    private static InstructionProjectionState state(Path home, Path workspace, GitIgnorePolicy gitIgnorePolicy)
            throws Exception {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        UserInstructionRootGuard userRoot = new UserInstructionRootGuard(home);
        UserInstructionLoader userLoader = new UserInstructionLoader(userRoot);
        WorkspaceInstructionLoader workspaceLoader = new WorkspaceInstructionLoader(guard, gitIgnorePolicy);
        InstructionLoader routedLoader = (candidate, token) -> candidate.sourceKind() == InstructionSourceKind.USER
                ? userLoader.load(candidate, token)
                : workspaceLoader.load(candidate, token);
        return new InstructionProjectionState(new InstructionFoundationFactory.InstructionFoundation(
                userRoot, guard, gitIgnorePolicy, userLoader, workspaceLoader, new InstructionCandidatePlanner(),
                new DeterministicInstructionDiscovery(routedLoader)));
    }

    private static GitIgnorePolicy ignoredLocalPolicy(Path workspace) {
        return new GitIgnorePolicy(workspace, builder -> new CompletedProcess(0));
    }

    private static final class CompletedProcess implements GitIgnorePolicy.ProcessHandle {
        private final int exitCode;

        private CompletedProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public java.io.InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public java.io.InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public boolean await(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public void destroyForcibly() {
        }
    }

    private static ToolCall call(String id, String name, Map<String, String> arguments) {
        return new ToolCall(id, name, new JsonObject(arguments));
    }

    private static ModelRequest canonical() {
        return new ModelRequest(new SessionId("session"), new RunId("run"), 1,
                List.of(new SystemMessage("BASE_SYSTEM"), new UserMessage("canonical user")), List.of());
    }

    private static String system(ModelRequest request) {
        return ((SystemMessage) request.messages().getFirst()).content();
    }
}
