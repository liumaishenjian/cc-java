package io.github.liumaishenjian.ccjava.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.skill.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillFoundationTest {
    private static SkillDescriptor descriptor(String id, SkillInvocationPolicy policy, List<String> tools) {
        return new SkillDescriptor(new SkillId(id), "description", policy, SkillSource.USER,
                "user/" + id, "a".repeat(64), SkillToolRestriction.declared(tools), List.of(), List.of());
    }

    @Test
    void narrowerIsStableTwoSetIntersection() {
        var narrower = new SkillToolScopeNarrower();
        assertThat(narrower.narrow(List.of("read", "write", "test"),
                SkillToolRestriction.declared(List.of("test", "read", "missing"))))
                .containsExactly("read", "test");
        assertThat(narrower.narrow(List.of("read", "write"), SkillToolRestriction.unspecified()))
                .containsExactly("read", "write");
        assertThat(narrower.narrow(List.of("read", "write"), SkillToolRestriction.declared(List.of())))
                .isEmpty();
    }

    @Test
    void narrowerMatchesTwoSetIntersectionAcrossDeterministicSamples() {
        var narrower = new SkillToolScopeNarrower();
        for (int mask = 0; mask < 32; mask++) {
            List<String> runtime = List.of("a", "b", "c", "d", "e");
            List<String> allowed = new ArrayList<>();
            for (int bit = 0; bit < runtime.size(); bit++) {
                if ((mask & (1 << bit)) != 0) allowed.add(runtime.get(bit));
            }
            List<String> actual = narrower.narrow(runtime, SkillToolRestriction.declared(allowed));
            assertThat(actual).containsExactlyElementsOf(runtime.stream()
                    .filter(new LinkedHashSet<>(allowed)::contains).toList());
            assertThat(runtime).containsAll(actual);
        }
    }

    @Test
    void scopeRejectsNestedAndDuplicateWhilePreservingStableOrder() {
        var scope = new SkillScope(new RunId("run-1"));
        var first = new SkillId("first"); var second = new SkillId("second");
        assertThat(scope.begin(first)).isNull();
        assertThat(scope.begin(second)).isEqualTo(SkillErrorCode.NESTED_INVOCATION);
        scope.commit(first);
        assertThat(scope.begin(first)).isEqualTo(SkillErrorCode.ALREADY_ACTIVATED);
        assertThat(scope.begin(second)).isNull(); scope.commit(second);
        assertThat(scope.activatedInOrder()).containsExactly(first, second);
        scope.close();
        assertThat(scope.activatedInOrder()).isEmpty();
    }

    @Test
    void invokerPreparesOnlyAfterLoadAndHonorsInvocationPolicy() {
        var entry = descriptor("inspect", SkillInvocationPolicy.EXPLICIT, List.of("read"));
        var snapshot = new SkillCatalogSnapshot("b".repeat(64), List.of(entry), List.of());
        SkillCatalog catalog = () -> snapshot;
        var invoker = new SkillInvoker(catalog,
                (s, d, c) -> new SkillContentSnapshot(d.id(), s.snapshotId(), d.contentDigest(), "body"),
                (s, d, c) -> List.of(), new SkillToolScopeNarrower());
        var scope = new SkillScope(new RunId("run-1"));
        var denied = invoker.invoke(new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.MODEL, ""),
                scope, List.of("read", "write"), CancellationToken.none());
        assertThat(denied.errorCode()).isEqualTo(SkillErrorCode.INVOCATION_NOT_ALLOWED);
        var success = invoker.invoke(new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.EXPLICIT, ""),
                scope, List.of("read", "write"), CancellationToken.none());
        assertThat(success.projection().effectiveVisibleTools()).containsExactly("read");
        assertThat(success.projection().arguments()).isEmpty();
        assertThat(scope.activatedInOrder()).isEmpty();
        scope.abort(entry.id());
    }

    @Test
    void projectionCarriesArgumentsButDiagnosticStringsHideUntrustedText() {
        String sentinel = "PRIVATE_ARGUMENT_SENTINEL";
        var content = new SkillContentSnapshot(new SkillId("inspect"), "b".repeat(64),
                "a".repeat(64), "PRIVATE_BODY_SENTINEL");
        var resource = new SkillResourceSnapshot("template.txt", "c".repeat(64),
                "PRIVATE_RESOURCE_SENTINEL");
        var projection = new SkillProjection(sentinel, content, List.of(resource), List.of("read"));
        var request = new SkillInvocationRequest(new RunId("run-1"), content.skillId(),
                SkillInvocationKind.EXPLICIT, sentinel);

        assertThat(projection.arguments()).isEqualTo(sentinel);
        assertThat(request.toString()).doesNotContain(sentinel);
        assertThat(content.toString()).doesNotContain("PRIVATE_BODY_SENTINEL");
        assertThat(resource.toString()).doesNotContain("PRIVATE_RESOURCE_SENTINEL");
        assertThat(projection.toString()).doesNotContain(sentinel)
                .doesNotContain("PRIVATE_BODY_SENTINEL")
                .doesNotContain("PRIVATE_RESOURCE_SENTINEL");
    }

    @Test
    void cancellationAfterResourceReadDoesNotCommitScope() {
        var entry = descriptor("inspect", SkillInvocationPolicy.BOTH, List.of());
        var snapshot = new SkillCatalogSnapshot("b".repeat(64), List.of(entry), List.of());
        SkillCatalog catalog = () -> snapshot;
        var cancelled = new boolean[1];
        CancellationToken token = new CancellationToken() {
            @Override public boolean isCancellationRequested() { return cancelled[0]; }
            @Override public Registration onCancellation(Runnable action) { return () -> { }; }
        };
        var invoker = new SkillInvoker(catalog,
                (s, d, c) -> new SkillContentSnapshot(d.id(), s.snapshotId(), d.contentDigest(), "body"),
                (s, d, c) -> { cancelled[0] = true; return List.of(); }, new SkillToolScopeNarrower());
        var scope = new SkillScope(new RunId("run-1"));

        var result = invoker.invoke(new SkillInvocationRequest(scope.runId(), entry.id(),
                SkillInvocationKind.MODEL, "args"), scope, List.of("read"), token);

        assertThat(result.errorCode()).isEqualTo(SkillErrorCode.CANCELLED);
        assertThat(scope.activatedInOrder()).isEmpty();
    }

    @Test
    void invokerRejectsReentrantCallDuringContentLoadWithoutChangingScope() {
        var entry = descriptor("inspect", SkillInvocationPolicy.BOTH, List.of());
        var snapshot = new SkillCatalogSnapshot("b".repeat(64), List.of(entry), List.of());
        SkillCatalog catalog = () -> snapshot;
        var scope = new SkillScope(new RunId("run-1"));
        SkillInvocationResult[] nested = new SkillInvocationResult[1];
        SkillInvoker[] holder = new SkillInvoker[1];
        holder[0] = new SkillInvoker(catalog, (currentSnapshot, descriptor, token) -> {
            nested[0] = holder[0].invoke(
                    new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.MODEL, ""),
                    scope, List.of("read"), token);
            return new SkillContentSnapshot(descriptor.id(), currentSnapshot.snapshotId(),
                    descriptor.contentDigest(), "body");
        }, (currentSnapshot, descriptor, token) -> List.of(), new SkillToolScopeNarrower());

        var result = holder[0].invoke(
                new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.MODEL, ""),
                scope, List.of("read"), CancellationToken.none());

        assertThat(result.succeeded()).isTrue();
        assertThat(nested[0].errorCode()).isEqualTo(SkillErrorCode.NESTED_INVOCATION);
        assertThat(scope.activatedInOrder()).isEmpty();
        scope.abort(entry.id());
    }

    @Test
    void recordsDefensivelyCopyCollectionsAndValidateNamesAndDigests() {
        List<String> mutableTools = new ArrayList<>(List.of("read"));
        var restriction = SkillToolRestriction.declared(mutableTools);
        mutableTools.add("write");
        assertThat(restriction.toolNames()).containsExactly("read");
        assertThatThrownBy(() -> restriction.toolNames().add("write"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> SkillToolRestriction.declared(List.of(" read")))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> mutableResources = new ArrayList<>(List.of("template.txt"));
        var descriptor = new SkillDescriptor(new SkillId("inspect"), "description", SkillInvocationPolicy.BOTH,
                SkillSource.USER, "user/inspect", "a".repeat(64), restriction, mutableResources, List.of("audit"));
        mutableResources.add("other.txt");
        assertThat(descriptor.resources()).containsExactly("template.txt");
        assertThatThrownBy(() -> new SkillDescriptor(new SkillId("inspect"), " ", SkillInvocationPolicy.BOTH,
                SkillSource.USER, "user/inspect", "a".repeat(64), restriction, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillDescriptor(new SkillId("inspect"), "description", SkillInvocationPolicy.BOTH,
                SkillSource.USER, "user/inspect", "a".repeat(64), restriction, List.of(" bad"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillContentSnapshot(new SkillId("inspect"), "bad", "a".repeat(64), "body"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillResourceSnapshot("../escape", "a".repeat(64), "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillResourceSnapshot("ok.txt", "bad", "text"))
                .isInstanceOf(IllegalArgumentException.class);

        List<SkillResourceSnapshot> mutableSnapshots = new ArrayList<>();
        var projection = new SkillProjection("args",
                new SkillContentSnapshot(new SkillId("inspect"), "b".repeat(64), "a".repeat(64), "body"),
                mutableSnapshots, List.of("read"));
        mutableSnapshots.add(new SkillResourceSnapshot("late.txt", "c".repeat(64), "late"));
        assertThat(projection.resources()).isEmpty();
    }

    @Test
    void recoveryRequiresEveryPrivacySafeDigestWithoutCreatingScope() {
        var entry = descriptor("inspect", SkillInvocationPolicy.BOTH, List.of());
        var snapshot = new SkillCatalogSnapshot("c".repeat(64), List.of(entry), List.of());
        String empty = sha256EmptySet();
        var identity = new SkillRecoveryIdentity(entry.id(), "1".repeat(64), "2".repeat(64),
                entry.contentDigest(), empty, empty, empty, empty, empty, empty);
        SkillRecoveryIdentityCatalog identities = id -> id.equals(entry.id())
                ? java.util.Optional.of(identity) : java.util.Optional.empty();
        var verifier = new SkillRecoveryVerifier();
        var matching = record(entry.id(), snapshot.snapshotId(), identity, empty);
        assertThat(verifier.verify(snapshot, List.of(matching), identities, List.of()).matched()).isTrue();

        List<java.util.function.UnaryOperator<SkillRecoveryRecord>> mutations = List.of(
                value -> new SkillRecoveryRecord(value.skillId(), "d".repeat(64), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> record(value.skillId(), value.snapshotId(), new SkillRecoveryIdentity(value.skillId(),
                        "d".repeat(64), identity.bodyDigest(), identity.contentDigest(), identity.resourcesDigest(),
                        identity.toolRestrictionDigest(), identity.hookSetDigest(), identity.pluginTreeDigest(),
                        identity.pluginManifestDigest(), identity.mcpConfigDigest()), empty),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        "d".repeat(64), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), "d".repeat(64), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), "d".repeat(64), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), "d".repeat(64),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        "d".repeat(64), value.pluginTreeDigest(), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), "d".repeat(64), value.pluginManifestDigest(), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), "d".repeat(64), value.mcpConfigDigest()),
                value -> new SkillRecoveryRecord(value.skillId(), value.snapshotId(), value.manifestDigest(),
                        value.bodyDigest(), value.contentDigest(), value.resourcesDigest(), value.effectiveToolDigest(),
                        value.hookSetDigest(), value.pluginTreeDigest(), value.pluginManifestDigest(), "d".repeat(64)));
        for (var mutation : mutations) {
            assertThat(verifier.verify(snapshot, List.of(mutation.apply(matching)), identities, List.of()).matched())
                    .isFalse();
        }
    }

    private static SkillRecoveryRecord record(SkillId id, String snapshot,
            SkillRecoveryIdentity identity, String effective) {
        return new SkillRecoveryRecord(id, snapshot, identity.manifestDigest(), identity.bodyDigest(),
                identity.contentDigest(), identity.resourcesDigest(), effective, identity.hookSetDigest(),
                identity.pluginTreeDigest(), identity.pluginManifestDigest(), identity.mcpConfigDigest());
    }

    private static String sha256EmptySet() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
