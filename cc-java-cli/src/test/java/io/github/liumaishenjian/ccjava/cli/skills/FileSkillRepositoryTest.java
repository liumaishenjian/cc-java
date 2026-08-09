package io.github.liumaishenjian.ccjava.cli.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.skill.SkillLoadingException;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSkillRepositoryTest {
    @TempDir Path temp;

    @Test
    void metadataScanDoesNotMaterializeBodyAndLoadsLazily() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "inspect", """
                ---
                name: inspect
                description: inspect code
                invocation: both
                allowed-tools:
                  - read
                resources:
                  - template.txt
                ---
                SECRET_BODY_SENTINEL
                """);
        Files.writeString(user.resolve("inspect/template.txt"), "resource", StandardCharsets.UTF_8);

        var repository = new FileSkillRepository(user, temp.resolve("missing"));
        var snapshot = repository.load(CancellationToken.none());

        assertThat(repository.metadataBodyMaterializedBytes()).isZero();
        assertThat(repository.scanMetrics().bodyMaterializedBytes()).isZero();
        assertThat(repository.scanMetrics().frontmatterMaterializedBytes()).isPositive();
        assertThat(repository.scanMetrics().digestBytes()).isEqualTo(
                Files.size(user.resolve("inspect/SKILL.md")));
        assertThat(snapshot.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.description()).isEqualTo("inspect code");
            assertThat(entry.toolRestriction().declared()).isTrue();
        });
        assertThat(snapshot.toString()).doesNotContain("SECRET_BODY_SENTINEL").doesNotContain(temp.toString());
        assertThat(repository.load(snapshot, snapshot.entries().getFirst(), CancellationToken.none())
                .markdown()).contains("SECRET_BODY_SENTINEL");
        assertThat(repository.read(snapshot, snapshot.entries().getFirst(), CancellationToken.none()))
                .singleElement().satisfies(resource -> assertThat(resource.text()).isEqualTo("resource"));
    }

    @Test
    void twentyLargeSkillsQuantifyMetadataMaterializationReductionAgainstEagerBaseline() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        long eagerBytes = 0;
        for (int index = 0; index < 20; index++) {
            String id = "large-" + String.format("%02d", index);
            Path file = writeSkill(user, id, sizedDocument(id, 8 * 1_024, 5));
            eagerBytes += Files.size(file);
        }

        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());
        var metrics = repository.scanMetrics();
        long metadataMaterializedBytes = metrics.frontmatterMaterializedBytes()
                + metrics.bodyMaterializedBytes();
        double reduction = 1.0d - (double) metadataMaterializedBytes / eagerBytes;

        assertThat(snapshot.entries()).hasSize(20);
        assertThat(eagerBytes).isEqualTo(163_840L);
        assertThat(metrics.digestBytes()).isEqualTo(eagerBytes);
        assertThat(metrics.bodyMaterializedBytes()).isZero();
        assertThat(metadataMaterializedBytes).isEqualTo(800L);
        assertThat(reduction).isGreaterThanOrEqualTo(0.90d);
    }

    @Test
    void duplicateUnknownAndUnicodeCaseNamesAreIsolatedWithoutLeakingPaths() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path project = Files.createDirectory(temp.resolve("project"));
        writeSkill(user, "same", document("same"));
        writeSkill(project, "same", document("same"));
        writeSkill(user, "Bad", "---\nname: Bad\ndescription: bad\n---\nbody");
        writeSkill(user, "unicode", "---\nname: Ünicode\ndescription: bad\n---\nbody");
        writeSkill(user, "unknown", "---\nname: unknown\ndescription: bad\nextra: no\n---\nbody");
        writeSkill(user, "duplicate", "---\nname: duplicate\ndescription: first\ndescription: second\n---\nbody");

        var snapshot = new FileSkillRepository(user, project).load(CancellationToken.none());

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains(SkillErrorCode.CONFLICT, SkillErrorCode.INVALID_METADATA);
        assertThat(snapshot.toString()).doesNotContain(temp.toString()).doesNotContain("first");
    }

    @Test
    void rootCeilingRejectsThe129thDirectoryAndTwoRootsPublish256() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path project = Files.createDirectory(temp.resolve("project"));
        for (int index = 0; index < 129; index++) {
            writeSkill(user, "u-" + formatIndex(index), document("u-" + formatIndex(index)));
        }
        for (int index = 0; index < 128; index++) {
            writeSkill(project, "p-" + formatIndex(index), document("p-" + formatIndex(index)));
        }

        var snapshot = new FileSkillRepository(user, project).load(CancellationToken.none());

        assertThat(snapshot.entries()).hasSize(256);
        assertThat(snapshot.entries()).noneMatch(entry -> entry.id().value().equals("u-128"));
        assertThat(snapshot.diagnostics()).anyMatch(diagnostic -> diagnostic.code() == SkillErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void explicitEmptyAllowedToolsDiffersFromMissingField() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "none", "---\nname: none\ndescription: none\nallowed-tools:\n---\nbody\n");
        writeSkill(user, "default", document("default"));

        var snapshot = new FileSkillRepository(user, null).load(CancellationToken.none());

        SkillDescriptor none = find(snapshot.entries(), "none");
        SkillDescriptor defaults = find(snapshot.entries(), "default");
        assertThat(none.toolRestriction().declared()).isTrue();
        assertThat(none.toolRestriction().toolNames()).isEmpty();
        assertThat(defaults.toolRestriction().declared()).isFalse();
    }

    @Test
    void immutableCatalogDoesNotDriftAfterRescan() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path file = writeSkill(user, "stable", document("stable"));
        var repository = new FileSkillRepository(user, null);
        var initial = repository.load(CancellationToken.none());
        var frozen = repository.freezeCatalog();
        Files.writeString(file, document("stable") + "new", StandardCharsets.UTF_8);
        var refreshed = repository.load(CancellationToken.none());

        assertThat(frozen.snapshot()).isSameAs(initial);
        assertThat(frozen.snapshot().snapshotId()).isNotEqualTo(refreshed.snapshotId());
    }

    @Test
    void loadersRejectForeignSnapshotAndForgedDescriptorButHonorIssuedOldSnapshot() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "stable", resourceDocument("stable", "resource.txt"));
        Files.writeString(user.resolve("stable/resource.txt"), "resource", StandardCharsets.UTF_8);
        var repository = new FileSkillRepository(user, null);
        var oldSnapshot = repository.load(CancellationToken.none());
        SkillDescriptor oldDescriptor = oldSnapshot.entries().getFirst();
        var foreignSnapshot = new io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot(
                oldSnapshot.snapshotId(), oldSnapshot.entries(), oldSnapshot.diagnostics());
        var forged = new SkillDescriptor(oldDescriptor.id(), "forged", oldDescriptor.policy(),
                oldDescriptor.source(), oldDescriptor.safeSourceId(), oldDescriptor.contentDigest(),
                oldDescriptor.toolRestriction(), oldDescriptor.resources(), oldDescriptor.hooks());

        assertFailure(() -> repository.load(foreignSnapshot, oldDescriptor, CancellationToken.none()),
                SkillErrorCode.IDENTITY_CHANGED);
        assertFailure(() -> repository.load(oldSnapshot, forged, CancellationToken.none()),
                SkillErrorCode.IDENTITY_CHANGED);
        assertFailure(() -> repository.read(oldSnapshot, forged, CancellationToken.none()),
                SkillErrorCode.IDENTITY_CHANGED);

        var newSnapshot = repository.load(CancellationToken.none());
        assertThat(repository.load(oldSnapshot, oldDescriptor, CancellationToken.none()).snapshotId())
                .isEqualTo(oldSnapshot.snapshotId());
        assertThat(repository.read(oldSnapshot, oldDescriptor, CancellationToken.none()))
                .singleElement().satisfies(resource -> assertThat(resource.text()).isEqualTo("resource"));
        assertThat(repository.load(newSnapshot, newSnapshot.entries().getFirst(), CancellationToken.none())
                .snapshotId()).isEqualTo(newSnapshot.snapshotId());
    }

    @Test
    void digestMutationFailsClosed() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path file = writeSkill(user, "race", document("race"));
        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());
        Files.writeString(file, document("race") + "changed", StandardCharsets.UTF_8);

        assertFailure(() -> repository.load(snapshot, snapshot.entries().getFirst(), CancellationToken.none()), SkillErrorCode.IDENTITY_CHANGED);
    }

    @Test
    void rejectsTraversalAbsoluteInvalidUtf8AndOversizeResources() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "traversal", resourceDocument("traversal", "../outside.txt"));
        writeSkill(user, "absolute", resourceDocument("absolute", "C:/outside.txt"));
        writeSkill(user, "invalid", resourceDocument("invalid", "invalid.txt"));
        Files.write(user.resolve("invalid/invalid.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        writeSkill(user, "large", resourceDocument("large", "large.txt"));
        Files.write(user.resolve("large/large.txt"), new byte[FileSkillRepository.MAX_RESOURCE_BYTES + 1]);

        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());
        assertThat(snapshot.entries()).extracting(entry -> entry.id().value())
                .containsExactlyInAnyOrder("invalid", "large");
        assertFailure(() -> repository.read(snapshot, find(snapshot.entries(), "invalid"), CancellationToken.none()),
                SkillErrorCode.RESOURCE_REJECTED);
        assertFailure(() -> repository.read(snapshot, find(snapshot.entries(), "large"), CancellationToken.none()),
                SkillErrorCode.LIMIT_EXCEEDED);
        assertThat(snapshot.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains(SkillErrorCode.RESOURCE_REJECTED);
    }

    @Test
    void deterministicReparseProbeRejectsRootWithoutPlatformPrivilege() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "inspect", document("inspect"));
        FileSkillRepository.PathSafetyProbe rejectingProbe = path -> {
            if (path.equals(user)) throw new IOException("simulated reparse point");
        };

        var snapshot = new FileSkillRepository(user, null, List.of(), rejectingProbe)
                .load(CancellationToken.none());

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(SkillErrorCode.UNREADABLE));
        assertThat(snapshot.toString()).doesNotContain(user.toString());
    }

    @Test
    void rejectsSymlinkResourceWhenPlatformSupportsIt() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "linked", resourceDocument("linked", "linked.txt"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(user.resolve("linked/linked.txt"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return;
        }
        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());
        assertFailure(() -> repository.read(snapshot, snapshot.entries().getFirst(), CancellationToken.none()),
                SkillErrorCode.RESOURCE_REJECTED);
    }

    @Test
    void exactSkillByteAndLineLimitsAreAcceptedAndPlusOneRejected() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        String exactBytes = sizedDocument("exact-bytes", FileSkillRepository.MAX_SKILL_BYTES, 4);
        writeSkill(user, "exact-bytes", exactBytes);
        writeSkill(user, "too-large", sizedDocument("too-large",
                FileSkillRepository.MAX_SKILL_BYTES + 1, 4));
        writeSkill(user, "exact-lines", lineDocument("exact-lines", FileSkillRepository.MAX_SKILL_LINES));
        writeSkill(user, "too-many-lines", lineDocument("too-many-lines",
                FileSkillRepository.MAX_SKILL_LINES + 1));

        var snapshot = new FileSkillRepository(user, null).load(CancellationToken.none());

        assertThat(snapshot.entries()).extracting(entry -> entry.id().value())
                .contains("exact-bytes", "exact-lines")
                .doesNotContain("too-large", "too-many-lines");
        assertThat(snapshot.diagnostics()).filteredOn(diagnostic -> diagnostic.code() == SkillErrorCode.LIMIT_EXCEEDED)
                .hasSize(2);
    }

    @Test
    void exactResourceAndAggregateLimitsAreAcceptedAndPlusOneRejected() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "single-ok", resourceDocument("single-ok", "data.txt"));
        Files.write(user.resolve("single-ok/data.txt"), new byte[FileSkillRepository.MAX_RESOURCE_BYTES]);
        writeSkill(user, "single-bad", resourceDocument("single-bad", "data.txt"));
        Files.write(user.resolve("single-bad/data.txt"), new byte[FileSkillRepository.MAX_RESOURCE_BYTES + 1]);
        List<String> four = List.of("a.txt", "b.txt", "c.txt", "d.txt");
        writeSkill(user, "total-ok", resourcesDocument("total-ok", four));
        for (String name : four) Files.write(user.resolve("total-ok").resolve(name),
                new byte[FileSkillRepository.MAX_RESOURCE_BYTES]);
        List<String> five = List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt");
        writeSkill(user, "total-bad", resourcesDocument("total-bad", five));
        for (int index = 0; index < five.size(); index++) {
            int size = index < 4 ? FileSkillRepository.MAX_RESOURCE_BYTES : 1;
            Files.write(user.resolve("total-bad").resolve(five.get(index)), new byte[size]);
        }
        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());

        assertThat(repository.read(snapshot, find(snapshot.entries(), "single-ok"), CancellationToken.none()))
                .singleElement().satisfies(resource -> assertThat(resource.text()).hasSize(FileSkillRepository.MAX_RESOURCE_BYTES));
        assertFailure(() -> repository.read(snapshot, find(snapshot.entries(), "single-bad"), CancellationToken.none()),
                SkillErrorCode.LIMIT_EXCEEDED);
        assertThat(repository.read(snapshot, find(snapshot.entries(), "total-ok"), CancellationToken.none()))
                .hasSize(4);
        assertFailure(() -> repository.read(snapshot, find(snapshot.entries(), "total-bad"), CancellationToken.none()),
                SkillErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void invalidUtf8BodyCrLfAndClosingDelimiterBoundariesAreHandled() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path invalid = writeSkill(user, "invalid-body", document("invalid-body"));
        Files.write(invalid, concat("---\nname: invalid-body\ndescription: description\n---\n".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xC3, (byte) 0x28}));
        writeSkill(user, "crlf", "---\r\nname: crlf\r\ndescription: description\r\n---\r\nbody\r\n");
        writeSkill(user, "no-final-newline", "---\nname: no-final-newline\ndescription: description\n---");
        writeSkill(user, "body-no-final-newline", "---\nname: body-no-final-newline\ndescription: description\n---\nbody");
        var repository = new FileSkillRepository(user, null);
        var snapshot = repository.load(CancellationToken.none());

        assertThat(snapshot.entries()).extracting(entry -> entry.id().value())
                .contains("invalid-body", "crlf", "no-final-newline", "body-no-final-newline");
        assertFailure(() -> repository.load(snapshot, find(snapshot.entries(), "invalid-body"),
                CancellationToken.none()), SkillErrorCode.UNREADABLE);
        assertThat(repository.load(snapshot, find(snapshot.entries(), "crlf"), CancellationToken.none()).markdown())
                .isEqualTo("body\n");
        assertThat(repository.load(snapshot, find(snapshot.entries(), "no-final-newline"), CancellationToken.none()).markdown())
                .isEmpty();
        assertThat(repository.load(snapshot, find(snapshot.entries(), "body-no-final-newline"), CancellationToken.none()).markdown())
                .isEqualTo("body");
    }

    @Test
    void total257thAcrossRootsAndPluginGetsDiagnostic() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        Path project = Files.createDirectory(temp.resolve("project"));
        for (int index = 0; index < 128; index++) writeSkill(user, "u-" + formatIndex(index),
                document("u-" + formatIndex(index)));
        for (int index = 0; index < 128; index++) writeSkill(project, "p-" + formatIndex(index),
                document("p-" + formatIndex(index)));
        SkillDescriptor plugin = new SkillDescriptor(new SkillId("z-plugin"), "plugin", SkillInvocationPolicy.BOTH,
                SkillSource.PLUGIN, "plugin/z-plugin", "a".repeat(64), SkillToolRestriction.unspecified(),
                List.of(), List.of());

        var snapshot = new FileSkillRepository(user, project, List.of(plugin)).load(CancellationToken.none());

        assertThat(snapshot.entries()).hasSize(256).noneMatch(entry -> entry.id().equals(plugin.id()));
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.skillId()).isEqualTo(plugin.id());
            assertThat(diagnostic.code()).isEqualTo(SkillErrorCode.LIMIT_EXCEEDED);
        });
    }

    @Test
    void catalogOrderingAndDigestAreStableAcrossCreationOrder() throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first"));
        writeSkill(firstRoot, "zeta", document("zeta"));
        writeSkill(firstRoot, "alpha", document("alpha"));
        var first = new FileSkillRepository(firstRoot, null).load(CancellationToken.none());
        Path secondRoot = Files.createDirectory(temp.resolve("second"));
        writeSkill(secondRoot, "alpha", document("alpha"));
        writeSkill(secondRoot, "zeta", document("zeta"));
        var second = new FileSkillRepository(secondRoot, null).load(CancellationToken.none());

        assertThat(first.entries()).extracting(entry -> entry.id().value()).containsExactly("alpha", "zeta");
        assertThat(second.entries()).extracting(entry -> entry.id().value()).containsExactly("alpha", "zeta");
        assertThat(first.snapshotId()).isEqualTo(second.snapshotId());
    }

    @Test
    void pluginDescriptorIsOnlyAConflictingMetadataCandidate() throws Exception {
        Path user = Files.createDirectory(temp.resolve("user"));
        writeSkill(user, "same", document("same"));
        SkillDescriptor plugin = new SkillDescriptor(new SkillId("same"), "plugin", SkillInvocationPolicy.BOTH,
                SkillSource.PLUGIN, "plugin/same", "a".repeat(64), SkillToolRestriction.unspecified(),
                List.of(), List.of());

        var snapshot = new FileSkillRepository(user, null, List.of(plugin)).load(CancellationToken.none());

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(SkillErrorCode.CONFLICT));
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            SkillErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOf(SkillLoadingException.class)
                .extracting("code").isEqualTo(expected);
    }

    private Path writeSkill(Path root, String id, String content) throws IOException {
        Path directory = Files.createDirectory(root.resolve(id));
        Path file = directory.resolve("SKILL.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static SkillDescriptor find(List<SkillDescriptor> entries, String id) {
        return entries.stream().filter(entry -> entry.id().value().equals(id)).findFirst().orElseThrow();
    }

    private static String formatIndex(int value) {
        return String.format("%03d", value);
    }

    private static String document(String id) {
        return "---\nname: " + id + "\ndescription: description\n---\nbody\n";
    }

    private static String resourceDocument(String id, String resource) {
        return resourcesDocument(id, List.of(resource));
    }

    private static String resourcesDocument(String id, List<String> resources) {
        StringBuilder result = new StringBuilder("---\nname: ").append(id)
                .append("\ndescription: description\nresources:\n");
        resources.forEach(resource -> result.append("  - ").append(resource).append('\n'));
        return result.append("---\nbody\n").toString();
    }

    private static String sizedDocument(String id, int bytes, int minimumLines) {
        String prefix = "---\nname: " + id + "\ndescription: description\n---\n";
        StringBuilder result = new StringBuilder(prefix);
        while (result.toString().getBytes(StandardCharsets.UTF_8).length < bytes) result.append('x');
        while (result.toString().lines().count() < minimumLines) result.append('\n');
        byte[] encoded = result.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > bytes) return result.substring(0, result.length() - (encoded.length - bytes));
        return result.toString();
    }

    private static String lineDocument(String id, int lines) {
        String prefix = "---\nname: " + id + "\ndescription: description\n---\n";
        int current = prefix.split("\n", -1).length - 1;
        StringBuilder result = new StringBuilder(prefix);
        for (int index = current; index < lines; index++) {
            result.append(index == lines - 1 ? "x" : "x\n");
        }
        return result.toString();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
