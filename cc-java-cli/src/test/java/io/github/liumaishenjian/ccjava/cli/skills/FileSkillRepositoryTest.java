package io.github.liumaishenjian.ccjava.cli.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.skill.SkillLoadingException;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSkillRepositoryTest {
    @TempDir Path temp;

    @Test
    void metadataScanDoesNotMaterializeBodyAndLoadsLazily() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "inspect", "---\nname: inspect\ndescription: inspect code\ninvocation: both\nallowed-tools:\n  - read\nresources:\n  - template.txt\n---\nSECRET_BODY_SENTINEL\n");
        Files.writeString(user.resolve("inspect/template.txt"), "resource", StandardCharsets.UTF_8);
        var repository = new FileSkillRepository(user, temp.resolve("missing"));
        var snapshot = repository.load(CancellationToken.none());
        assertThat(repository.metadataBodyMaterializedBytes()).isZero();
        assertThat(snapshot.entries()).singleElement().satisfies(e -> assertThat(e.description()).isEqualTo("inspect code"));
        var content = repository.load(snapshot.entries().getFirst(), snapshot.snapshotId(), CancellationToken.none());
        assertThat(content.markdown()).contains("SECRET_BODY_SENTINEL");
        assertThat(repository.read(snapshot.entries().getFirst(), CancellationToken.none())).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo("resource"));
        assertThat(snapshot.toString()).doesNotContain("SECRET_BODY_SENTINEL").doesNotContain(temp.toString());
    }

    @Test
    void duplicateUnknownAndUnicodeCaseNamesAreIsolated() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user")); Path project = Files.createDirectory(temp.resolve("project"));
        writeSkill(user, "same", doc("same")); writeSkill(project, "same", doc("same"));
        writeSkill(user, "Bad", "---\nname: Bad\ndescription: bad\n---\nbody");
        writeSkill(user, "unknown", "---\nname: unknown\ndescription: bad\nextra: no\n---\nbody");
        var snapshot = new FileSkillRepository(user, project).load(CancellationToken.none());
        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.diagnostics()).extracting(d -> d.code()).contains(SkillErrorCode.CONFLICT, SkillErrorCode.INVALID_METADATA);
    }

    @Test
    void totalCatalogStopsAt256() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user")); Path project = Files.createDirectory(temp.resolve("project"));
        for (int i = 0; i < 128; i++) writeSkill(user, "u-" + i, doc("u-" + i));
        for (int i = 0; i < 128; i++) writeSkill(project, "p-" + i, doc("p-" + i));
        var snapshot = new FileSkillRepository(user, project).load(CancellationToken.none());
        assertThat(snapshot.entries()).hasSize(256);
    }

    @Test
    void rejectsTraversalAbsoluteAndSymlinkResources() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "escape", "---\nname: escape\ndescription: escape\nresources:\n  - ../outside.txt\n---\nbody");
        var repository = new FileSkillRepository(user, null); var snapshot = repository.load(CancellationToken.none());
        assertThatThrownBy(() -> repository.read(snapshot.entries().getFirst(), CancellationToken.none()))
                .isInstanceOf(SkillLoadingException.class).extracting("code").isEqualTo(SkillErrorCode.RESOURCE_REJECTED);
    }

    @Test
    void digestMutationFailsClosed() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user")); Path file = writeSkill(user, "race", doc("race"));
        var repository = new FileSkillRepository(user, null); var snapshot = repository.load(CancellationToken.none());
        Files.writeString(file, doc("race") + "changed", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> repository.load(snapshot.entries().getFirst(), snapshot.snapshotId(), CancellationToken.none()))
                .isInstanceOf(SkillLoadingException.class).extracting("code").isEqualTo(SkillErrorCode.IDENTITY_CHANGED);
    }

    private Path writeSkill(Path root, String id, String content) throws IOException {
        Path dir = Files.createDirectory(root.resolve(id)); Path file = dir.resolve("SKILL.md"); Files.writeString(file, content, StandardCharsets.UTF_8); return file;
    }
    private static String doc(String id) { return "---\nname: " + id + "\ndescription: description\n---\nbody\n"; }
}
