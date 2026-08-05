package io.github.liumaishenjian.ccjava.cli.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFixedSourceLoaderTest {
    @TempDir
    Path temporary;

    @Test
    void missingOptionalSourcesAreNoOps() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        SettingsFixedSourceLoader loader = loader(home, workspace);

        assertThat(loader.loadUser(CancellationToken.none()).snapshot()).isEmpty();
        assertThat(loader.loadUser(CancellationToken.none()).diagnostics()).isEmpty();
        assertThat(loader.loadProjectShared(CancellationToken.none()).snapshot()).isEmpty();
        assertThat(loader.loadProjectShared(CancellationToken.none()).diagnostics()).isEmpty();
        assertThat(loader.loadProjectLocal(CancellationToken.none()).snapshot()).isEmpty();
        assertThat(loader.loadProjectLocal(CancellationToken.none()).diagnostics()).isEmpty();
    }

    @Test
    void readsOnlyFixedUserAndSharedProjectCandidates() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"model\":{\"name\":\"user\"}}");
        Files.writeString(workspace.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"model\":{\"name\":\"project\"}}");
        SettingsFixedSourceLoader loader = loader(home, workspace);

        assertThat(loader.loadUser(CancellationToken.none()).snapshot()).isPresent();
        assertThat(loader.loadProjectShared(CancellationToken.none()).snapshot()).isPresent();
    }

    @Test
    void invalidSourceReturnsSafeDiagnosticWithoutSnapshot() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"unknown\":\"secret-value\"}");

        SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader(home, workspace)
                .loadProjectShared(CancellationToken.none());

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.UNKNOWN_FIELD);
        assertThat(result.toString()).doesNotContain("secret-value", workspace.toString());
    }

    @Test
    void oversizedAndGrowingFilesReturnByteLimit() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Path settings = Files.writeString(home.resolve(".cc-java/settings.json"), " ".repeat(32 * 1024 + 1));
        SettingsFixedSourceLoader loader = loader(home, workspace);

        assertThat(loader.loadUser(CancellationToken.none()).diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.BYTE_LIMIT);
        Files.writeString(settings, "{\"schemaVersion\":1}");
        SettingsFixedSourceLoader.SettingsSourceLoadResult grown = loader.loadUser(CancellationToken.none(), () -> {
            try {
                Files.writeString(settings, " ".repeat(32 * 1024 + 1));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        assertThat(grown.snapshot()).isEmpty();
        assertThat(grown.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.BYTE_LIMIT);
    }

    @Test
    void detectsSameSizeMutationAfterRead() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Path settings = Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1}");
        Files.setLastModifiedTime(settings, FileTime.from(Instant.parse("2026-08-06T00:00:00Z")));
        SettingsFixedSourceLoader loader = loader(home, workspace);

        SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader.loadUser(CancellationToken.none(), () -> {
            try {
                Files.writeString(settings, "{\"schemaVersion\":2}");
                Files.setLastModifiedTime(settings, FileTime.from(Instant.parse("2026-08-06T00:00:01Z")));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.IDENTITY_CHANGED);
    }

    @Test
    void localSourceRequiresExplicitGitIgnoreButLoadsWhenProved() throws Exception {
        assumeGitAvailable();
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        initializeRepository(workspace);
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/settings.local.json"), "{\"schemaVersion\":1}");
        SettingsFixedSourceLoader loader = loader(home, workspace);

        assertThat(loader.loadProjectLocal(CancellationToken.none()).diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.LOCAL_NOT_GITIGNORED);
        Files.writeString(workspace.resolve(".gitignore"), ".cc-java/settings.local.json\n");
        assertThat(loader.loadProjectLocal(CancellationToken.none()).snapshot()).isPresent();
    }

    @Test
    void cancellationPreventsAnyRead() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1}");
        CancellationSource source = new CancellationSource();
        source.cancel();

        SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader(home, workspace).loadUser(source.token());

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.CANCELLED);
    }

    @Test
    void rejectsUserRootSymbolicLinkWhenPlatformPermitsIt() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outsideRoot = Files.createDirectory(temporary.resolve("outside-root"));
        Files.writeString(outsideRoot.resolve("settings.json"), "{\"schemaVersion\":1}");
        try {
            Files.createSymbolicLink(home.resolve(".cc-java"), outsideRoot);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }

        SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader(home, workspace).loadUser(CancellationToken.none());

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.UNSAFE_FILE);
    }

    @Test
    void rejectsExternalSymbolicLinkWhenPlatformPermitsIt() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Path outside = Files.writeString(temporary.resolve("outside.json"), "{\"schemaVersion\":1}");
        try {
            Files.createSymbolicLink(home.resolve(".cc-java/settings.json"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }

        SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader(home, workspace).loadUser(CancellationToken.none());

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ConfigurationDiagnosticCode.UNSAFE_FILE);
    }

    private static SettingsFixedSourceLoader loader(Path home, Path workspace) throws Exception {
        return new SettingsFixedSourceLoader(home, new WorkspaceGuard(workspace), new SettingsV1SourceParser(Set.of("read_file")));
    }

    private static void initializeRepository(Path workspace) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet").directory(workspace.toFile()).start();
        assertThat(process.waitFor()).isZero();
    }

    private static void assumeGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            Assumptions.assumeTrue(process.waitFor() == 0, "Git 不可用");
        } catch (Exception exception) {
            Assumptions.abort("Git 不可用");
        }
    }
}
