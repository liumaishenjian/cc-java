package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicUtf8FileWriterTest {

    @TempDir
    Path workspace;

    @Test
    void detectsConcurrentReplacementAndKeepsCompetingContent() throws Exception {
        Path target = Files.writeString(workspace.resolve("target.txt"), "original");

        assertThatThrownBy(() -> AtomicUtf8FileWriter.replace(
                target,
                "original".getBytes(StandardCharsets.UTF_8),
                "agent".getBytes(StandardCharsets.UTF_8),
                CancellationToken.none(),
                () -> overwrite(target, "user")))
                .isInstanceOf(WorkspaceAccessException.class)
                .satisfies(failure -> assertThat(((WorkspaceAccessException) failure)
                        .error().code()).isEqualTo(ToolErrorCode.FILE_CONFLICT));

        assertThat(Files.readString(target)).isEqualTo("user");
        assertNoStagedFiles();
    }

    @Test
    void detectsConcurrentNewFileAndNeverOverwritesIt() throws Exception {
        Path target = workspace.resolve("new.txt");

        assertThatThrownBy(() -> AtomicUtf8FileWriter.create(
                target,
                "agent".getBytes(StandardCharsets.UTF_8),
                CancellationToken.none(),
                () -> overwrite(target, "user")))
                .isInstanceOf(WorkspaceAccessException.class)
                .satisfies(failure -> assertThat(((WorkspaceAccessException) failure)
                        .error().code()).isEqualTo(ToolErrorCode.FILE_CONFLICT));

        assertThat(Files.readString(target)).isEqualTo("user");
        assertNoStagedFiles();
    }

    private void assertNoStagedFiles() throws Exception {
        try (var paths = Files.list(workspace)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".cc-java-write-")))
                    .isEmpty();
        }
    }

    private static void overwrite(Path target, String content) {
        try {
            Files.writeString(target, content);
        } catch (java.io.IOException exception) {
            throw new AssertionError("测试无法制造并发文件变化", exception);
        }
    }
}
