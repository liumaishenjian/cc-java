package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PlanModeCoordinatorTest {
    private static PlanDocument plan() {
        return new PlanDocument("plan-1", "safe change", List.of(
                new PlanStep(1, "inspect", "read only", "digest-a"),
                new PlanStep(2, "edit", "approved edit", "digest-a")),
                PlanStatus.DRAFT, "digest-a");
    }

    @Test void createsArtifactAndBlocksSideEffectsBeforeApproval() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        assertThat(coordinator.state().approvalGate()).isEqualTo(PlanApprovalGate.PENDING);
        assertThat(coordinator.beginNext("digest-a")).isEmpty();
        assertThat(coordinator.state().status()).isEqualTo(PlanStatus.AWAITING_APPROVAL);
    }

    @Test void approvesAndSequencesOneStepAtATime() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        coordinator.approve("digest-a");
        assertThat(coordinator.beginNext("digest-a")).contains(plan().steps().get(0));
        assertThat(coordinator.beginNext("digest-a")).isEmpty();
        coordinator.completeStep("digest-a");
        assertThat(coordinator.beginNext("digest-a")).contains(plan().steps().get(1));
        assertThat(coordinator.completeStep("digest-a").status()).isEqualTo(PlanStatus.COMPLETED);
    }

    @Test void completionRequiresMatchingDigestAndRollsForwardExpectedDigest() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        coordinator.approve("digest-a");
        coordinator.beginNext("digest-a");
        assertThat(coordinator.completeStep("digest-b").status()).isEqualTo(PlanStatus.DIGEST_CONFLICT);
        assertThat(coordinator.completeStep("digest-a").status()).isEqualTo(PlanStatus.DIGEST_CONFLICT);
    }

    @Test void legacyCompletionFailsClosed() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        coordinator.approve("digest-a");
        coordinator.beginNext("digest-a");
        org.assertj.core.api.Assertions.assertThatThrownBy(coordinator::completeStep)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void digestConflictPausesAndRejectsRecoveryUntilStable() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        assertThat(coordinator.approve("changed").status()).isEqualTo(PlanStatus.DIGEST_CONFLICT);
        assertThat(coordinator.state().sideEffectsAllowed()).isFalse();
        assertThat(coordinator.reject().status()).isEqualTo(PlanStatus.REJECTED);
    }

    @Test void approvedAgentRunCompletesOnlyAfterRuntimeTerminalAndAcceptsChangedDigest() {
        PlanModeCoordinator coordinator = new PlanModeCoordinator(plan());
        coordinator.approve("digest-a");
        assertThat(coordinator.beginAgentRun("digest-a").status()).isEqualTo(PlanStatus.EXECUTING);
        assertThat(coordinator.state().activeStep()).isEqualTo(1);
        assertThat(coordinator.completeAgentRun("digest-after-tools").status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(coordinator.document().workspaceDigest()).isEqualTo("digest-after-tools");
    }
}
