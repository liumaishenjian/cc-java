package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证 Core 恢复边界不接收互相矛盾的 Plan 投影。 */
class PlanRecoveryInvariantTest {
    private static final SessionId SESSION = new SessionId("session-plan-recovery");
    private static final SessionSpec SPEC = SessionSpec.of("test");
    private static final List<PlanStep> STEPS = List.of(new PlanStep(1, "step", "detail", "digest"));

    @Test
    void projectionRejectsMismatchedStatusDigestGateAndCursor() {
        PlanDocument awaiting = document("plan-recovery", PlanStatus.AWAITING_APPROVAL, "digest");
        assertThatThrownBy(() -> new PlanRecoveryProjection(awaiting,
                state("plan-recovery", PlanApprovalGate.PENDING, 1, null, PlanStatus.APPROVED, "digest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanRecoveryProjection(awaiting,
                state("plan-recovery", PlanApprovalGate.PENDING, 1, null,
                        PlanStatus.AWAITING_APPROVAL, "other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanRecoveryProjection(awaiting,
                state("plan-recovery", PlanApprovalGate.APPROVED, 1, null,
                        PlanStatus.AWAITING_APPROVAL, "digest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanRecoveryProjection(awaiting,
                state("plan-recovery", PlanApprovalGate.PENDING, 2, null,
                        PlanStatus.AWAITING_APPROVAL, "digest")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotCrossChecksArtifactIdentityStatusAndSessionWhileKeepingLegacyShapesLegal() {
        PlanRecoveryProjection projection = new PlanRecoveryProjection(
                document("plan-recovery", PlanStatus.AWAITING_APPROVAL, "digest"),
                state("plan-recovery", PlanApprovalGate.PENDING, 1, null,
                        PlanStatus.AWAITING_APPROVAL, "digest"));
        PlanArtifact artifact = PlanArtifact.create("plan-recovery", SESSION, "# Plan",
                PlanStatus.AWAITING_APPROVAL, Instant.parse("2026-08-20T00:00:00Z"));

        assertThatCode(() -> snapshot(Optional.of(projection), Optional.empty())).doesNotThrowAnyException();
        assertThatCode(() -> snapshot(Optional.empty(), Optional.of(artifact))).doesNotThrowAnyException();
        assertThatCode(() -> snapshot(Optional.of(projection), Optional.of(artifact))).doesNotThrowAnyException();
        assertThatThrownBy(() -> snapshot(Optional.of(projection), Optional.of(
                PlanArtifact.create("plan-other", SESSION, "# Plan", PlanStatus.AWAITING_APPROVAL,
                        Instant.parse("2026-08-20T00:00:00Z")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Optional.of(projection), Optional.of(
                artifact.nextRevision("# Plan", PlanStatus.APPROVED,
                        Instant.parse("2026-08-20T00:00:01Z")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Optional.empty(), Optional.of(
                PlanArtifact.create("plan-recovery", new SessionId("session-other"), "# Plan",
                        PlanStatus.AWAITING_APPROVAL, Instant.parse("2026-08-20T00:00:00Z")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SessionRecoverySnapshot snapshot(
            Optional<PlanRecoveryProjection> projection, Optional<PlanArtifact> artifact) {
        return new SessionRecoverySnapshot(SESSION, SPEC, List.of(), List.of(), Optional.empty(),
                List.of(), List.of(), projection, artifact);
    }

    private static PlanDocument document(String id, PlanStatus status, String digest) {
        return new PlanDocument(id, "objective", STEPS, status, digest);
    }

    private static PlanExecutionState state(String id, PlanApprovalGate gate, Integer next,
            Integer active, PlanStatus status, String digest) {
        return new PlanExecutionState(id, gate, next, active, status, digest);
    }
}
