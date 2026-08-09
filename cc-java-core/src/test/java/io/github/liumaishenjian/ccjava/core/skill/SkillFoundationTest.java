package io.github.liumaishenjian.ccjava.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.skill.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillFoundationTest {
    private static SkillDescriptor descriptor(String id, SkillInvocationPolicy policy, List<String> tools) {
        return new SkillDescriptor(new SkillId(id), "description", policy, SkillSource.USER,
                "user/" + id, "a".repeat(64), tools, List.of(), List.of());
    }

    @Test
    void narrowerIsStableTwoSetIntersection() {
        var narrower = new SkillToolScopeNarrower();
        assertThat(narrower.narrow(List.of("read", "write", "test"), List.of("test", "read", "missing")))
                .containsExactly("read", "test");
        assertThat(narrower.narrow(List.of("read", "write"), List.of())).containsExactly("read", "write");
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
    void invokerCommitsOnlyAfterLoadAndHonorsInvocationPolicy() {
        var entry = descriptor("inspect", SkillInvocationPolicy.EXPLICIT, List.of("read"));
        var snapshot = new SkillCatalogSnapshot("b".repeat(64), List.of(entry), List.of());
        SkillCatalog catalog = () -> snapshot;
        var invoker = new SkillInvoker(catalog,
                (d, s, c) -> new SkillContentSnapshot(d.id(), s, d.contentDigest(), "body"),
                (d, c) -> List.of(), new SkillToolScopeNarrower());
        var scope = new SkillScope(new RunId("run-1"));
        var denied = invoker.invoke(new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.MODEL, ""),
                scope, List.of("read", "write"), CancellationToken.none());
        assertThat(denied.errorCode()).isEqualTo(SkillErrorCode.INVOCATION_NOT_ALLOWED);
        var success = invoker.invoke(new SkillInvocationRequest(scope.runId(), entry.id(), SkillInvocationKind.EXPLICIT, ""),
                scope, List.of("read", "write"), CancellationToken.none());
        assertThat(success.projection().effectiveVisibleTools()).containsExactly("read");
        assertThat(scope.activatedInOrder()).containsExactly(entry.id());
    }

    @Test
    void recoveryReportsDigestMismatchWithoutCreatingScope() {
        var entry = descriptor("inspect", SkillInvocationPolicy.BOTH, List.of());
        var snapshot = new SkillCatalogSnapshot("c".repeat(64), List.of(entry), List.of());
        var verifier = new SkillRecoveryVerifier();
        assertThat(verifier.verify(snapshot, List.of(new SkillRecoveryRecord(entry.id(), snapshot.snapshotId(), entry.contentDigest()))).matched()).isTrue();
        var mismatch = verifier.verify(snapshot, List.of(new SkillRecoveryRecord(entry.id(), snapshot.snapshotId(), "d".repeat(64))));
        assertThat(mismatch.matched()).isFalse();
        assertThat(mismatch.mismatches()).containsExactly(entry.id());
    }
}
