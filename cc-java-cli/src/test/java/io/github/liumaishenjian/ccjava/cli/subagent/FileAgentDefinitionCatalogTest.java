package io.github.liumaishenjian.ccjava.cli.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S12 strict definition discovery 的来源、冲突、schema 与 snapshot 回归。 */
class FileAgentDefinitionCatalogTest {
    @TempDir Path temp;

    @Test
    void freezesValidSnapshotAndRejectsUnknownField() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("research.agent"), definition("research") + "unknown=x\n", StandardCharsets.UTF_8);
        FileAgentDefinitionCatalog invalid = FileAgentDefinitionCatalog.load(null, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none());
        assertThat(invalid.snapshots()).isEmpty(); assertThat(invalid.diagnostics()).contains("project:invalid");

        Files.writeString(project.resolve("research.agent"), definition("research"), StandardCharsets.UTF_8);
        FileAgentDefinitionCatalog valid = FileAgentDefinitionCatalog.load(null, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none());
        assertThat(valid.snapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.id().value()).isEqualTo("research");
            assertThat(snapshot.visibleTools()).containsExactly("read_file");
            assertThat(snapshot.contentDigest()).matches("[0-9a-f]{64}");
        });
        Files.writeString(project.resolve("research.agent"), definition("changed"), StandardCharsets.UTF_8);
        assertThat(valid.snapshots().getFirst().id().value()).isEqualTo("research");
    }

    @Test
    void rejectsProjectDefinitionsUntilExactProjectTrustIsGranted() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("research.agent"), definition("research"));
        FileAgentDefinitionCatalog untrusted = FileAgentDefinitionCatalog.load(null, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none(), false);
        assertThat(untrusted.snapshots()).isEmpty();
        assertThat(untrusted.diagnostics()).containsExactly("project:trust_required");

        FileAgentDefinitionCatalog trusted = FileAgentDefinitionCatalog.load(null, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none(), true);
        assertThat(trusted.snapshots()).singleElement().satisfies(snapshot ->
                assertThat(snapshot.sourceKind()).isEqualTo("project"));
    }

    @Test
    void rejectsSymlinkOrReparseCandidates() throws Exception {
        Path project = Files.createDirectories(temp.resolve("linked-project"));
        Path outside = temp.resolve("outside.agent");
        Files.writeString(outside, definition("linked"));
        Path linked = project.resolve("linked.agent");
        try {
            Files.createSymbolicLink(linked, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        FileAgentDefinitionCatalog catalog = FileAgentDefinitionCatalog.load(null, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none());
        assertThat(catalog.snapshots()).isEmpty();
        assertThat(catalog.diagnostics()).contains("project:invalid");
    }

    @Test
    void isolatesCrossSourceConflictInsteadOfOverriding() throws Exception {
        Path user = Files.createDirectories(temp.resolve("user")); Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(user.resolve("same.agent"), definition("same"));
        Files.writeString(project.resolve("same.agent"), definition("same"));
        FileAgentDefinitionCatalog catalog = FileAgentDefinitionCatalog.load(user, project,
                Set.of("read_file"), Set.of("fake"), CancellationToken.none());
        assertThat(catalog.snapshots()).isEmpty(); assertThat(catalog.diagnostics()).contains("conflict:same");
    }

    private static String definition(String id) {
        return "id=" + id + "\n" +
                "description=readonly researcher\n" +
                "instructions=read only\n" +
                "tools=read_file\n" +
                "permission=PLAN\n" +
                "model=fake\n" +
                "max-model-turns=2\n" +
                "max-tool-calls=2\n" +
                "max-input-tokens=1000\n" +
                "max-output-characters=256\n" +
                "timeout-seconds=5\n" +
                "background=false\n";
    }
}
