package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolsTest {

    @TempDir
    Path temporary;

    @Test
    void reportsStagedUnstagedAndUntrackedAndReadsBothDiffModes() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        git(repository, "init");
        git(repository, "config", "user.name", "Fixture");
        git(repository, "config", "user.email", "fixture@example.invalid");
        Files.writeString(repository.resolve("tracked.txt"), "base\n");
        git(repository, "add", "tracked.txt");
        git(repository, "commit", "-m", "base");
        Files.writeString(repository.resolve("tracked.txt"), "staged\n");
        git(repository, "add", "tracked.txt");
        Files.writeString(repository.resolve("tracked.txt"), "unstaged\n");
        Files.writeString(repository.resolve("new file.txt"), "new\n");
        WorkspaceGuard guard = new WorkspaceGuard(repository);
        GitReadClient client = new GitReadClient(guard.workspace());

        ToolExecutionOutcome status = execute(new GitStatusTool(guard, client), Map.of());
        ToolExecutionOutcome staged = execute(new GitDiffTool(guard, client), Map.of("mode", "staged"));
        ToolExecutionOutcome unstaged = execute(new GitDiffTool(guard, client), Map.of(
                "mode", "unstaged", "path", "tracked.txt"));

        assertThat(status.content()).contains("staged (1)", "unstaged (1)", "untracked (1)");
        assertThat(status.content()).contains("tracked.txt", "new file.txt");
        assertThat(staged.content()).contains("mode: staged", "+staged");
        assertThat(unstaged.content()).contains("mode: unstaged", "+unstaged");
    }

    @Test
    void returnsStructuredNonRepositoryError() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plain"));
        WorkspaceGuard guard = new WorkspaceGuard(directory);

        ToolExecutionOutcome outcome = execute(
                new GitStatusTool(guard, new GitReadClient(guard.workspace())), Map.of());

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(outcome.error().orElseThrow().message()).doesNotContain(directory.toString());
    }

    @Test
    void rejectsArbitraryGitOptionsInPath() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repo-options"));
        git(repository, "init");
        WorkspaceGuard guard = new WorkspaceGuard(repository);
        GitDiffTool tool = new GitDiffTool(guard, new GitReadClient(guard.workspace()));

        ToolExecutionOutcome outcome = execute(tool, Map.of("path", "../outside"));

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    private static void git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Fixture Git failed: " + output);
        }
    }

    private static ToolExecutionOutcome execute(AgentTool tool, Map<String, ?> arguments)
            throws Exception {
        return tool.execute(new ToolInvocation(
                new SessionId("session-1"),
                new RunId("run-1"),
                1,
                new ToolCall("call-1", tool.definition().name(), new JsonObject(arguments))));
    }
}
