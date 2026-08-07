package io.github.liumaishenjian.ccjava.cli.mentions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证候选只服务 UX：有界、稳定排序、不跟随链接且不泄漏敏感或越界路径。 */
class FileSuggestionServiceTest {

    @TempDir
    Path root;

    @Test
    void rankingPutsPrefixMatchesBeforeContainsThenBytewisePath() throws IOException {
        Path workspace = workspace();
        write(workspace.resolve("src/alpha.java"), "a");
        write(workspace.resolve("src/beta.java"), "b");
        write(workspace.resolve("other/src-copy.java"), "c");

        List<String> candidates = service(workspace).suggest("src");

        assertThat(candidates).containsExactly(
                "src/alpha.java", "src/beta.java", "other/src-copy.java");
    }

    @Test
    void suggestionsAreBoundedAndDeterministic() throws IOException {
        Path workspace = workspace();
        for (int index = 0; index < 40; index++) {
            write(workspace.resolve("many/file-%02d.txt".formatted(index)), "x");
        }

        List<String> first = service(workspace).suggest("many/");
        List<String> second = service(workspace).suggest("many/");

        assertThat(first).hasSize(FileSuggestionService.MAX_CANDIDATES).isEqualTo(second);
        assertThat(first.getFirst()).isEqualTo("many/file-00.txt");
    }

    @Test
    void rejectsOversizedOrControlCharacterQuery() throws IOException {
        FileSuggestionService service = service(workspace());

        assertThatThrownBy(() -> service.suggest("a".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.suggest("bad\0query"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.suggest("bad\nquery"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverSuggestsSensitiveOrLinkEscapingTargets() throws IOException {
        Path workspace = workspace();
        write(workspace.resolve("safe/readme.md"), "safe");
        write(workspace.resolve(".env"), "openai.api-key=SECRET_SENTINEL");
        Path outside = root.resolve("outside/secret.md");
        write(outside, "OUTSIDE_SENTINEL");
        boolean linked = true;
        try {
            Files.createSymbolicLink(workspace.resolve("escape.md"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            linked = false;
        }

        List<String> candidates = service(workspace).suggest("");

        assertThat(candidates).contains("safe/readme.md");
        assertThat(candidates).noneMatch(value -> value.contains(".env"));
        if (linked) {
            assertThat(candidates).doesNotContain("escape.md");
        }
        assertThat(candidates.toString())
                .doesNotContain("OUTSIDE_SENTINEL", "SECRET_SENTINEL", root.toString());
    }

    private FileSuggestionService service(Path workspace) throws IOException {
        return new FileSuggestionService(new WorkspaceGuard(workspace));
    }

    private Path workspace() throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
