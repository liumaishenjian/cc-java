package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证 evidence ledger 的绑定、完成和显式 skip 不变量。 */
class PlanEvidenceLedgerTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void requiresRealReferenceBeforeCompletion() {
        var ledger = PlanEvidenceLedger.planning(new SessionId("session-evidence"), "plan-evidence", NOW)
                .declare(new PlanEvidenceRequirement("tests", PlanEvidenceKind.VERIFICATION,
                        "run_command", "tests pass", true), NOW)
                .bind(2, "a".repeat(64), "b".repeat(64), NOW);
        assertThat(ledger.completionSatisfied()).isFalse();
        assertThat(ledger.firstBlockingRequirement()).contains("tests");
        ledger = ledger.record(new PlanEvidenceReference("tests", PlanEvidenceStatus.PASSED,
                "TOOL_RESULT", "call-tests", Optional.of("c".repeat(64)), "TOOL_SUCCEEDED", NOW), NOW);
        assertThat(ledger.completionSatisfied()).isTrue();
    }

    @Test
    void draftRequirementCanBeCorrectedInPlaceWithoutChangingIdentityOrCount() {
        var ledger = PlanEvidenceLedger.planning(new SessionId("session-correction"), "plan-correction", NOW)
                .declare(new PlanEvidenceRequirement("tests", PlanEvidenceKind.VERIFICATION,
                        "validation-output", "old validation", true), NOW);
        var corrected = ledger.declare(new PlanEvidenceRequirement("tests", PlanEvidenceKind.VERIFICATION,
                "run_command", "tests pass", true), NOW.plusSeconds(1));
        assertThat(corrected.requirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.requirementId()).isEqualTo("tests");
            assertThat(requirement.locator()).isEqualTo("run_command");
            assertThat(requirement.label()).isEqualTo("tests pass");
        });
        assertThat(corrected.createdAt()).isEqualTo(ledger.createdAt());
        assertThat(corrected.updatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void skipMustBeExplicitUserDecisionAndRequirementsFreezeAtApproval() {
        var ledger = PlanEvidenceLedger.planning(new SessionId("session-skip"), "plan-skip", NOW)
                .declare(new PlanEvidenceRequirement("artifact", PlanEvidenceKind.DELIVERABLE,
                        "result.txt", "result exists", true), NOW)
                .bind(1, "a".repeat(64), "b".repeat(64), NOW);
        assertThatThrownBy(() -> ledger.declare(new PlanEvidenceRequirement("other",
                PlanEvidenceKind.DELIVERABLE, "other.txt", "other", true), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PlanEvidenceReference("artifact", PlanEvidenceStatus.SKIPPED,
                "MODEL_TEXT", "claim", Optional.empty(), "SKIPPED", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
