package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextUsageViewTest {

    @Test
    void derivesExactCapacityFreeAndOverflowWithoutContent() {
        ContextProjection projection = projection(120, 0, List.of(new ContextReduction(
                ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION, 160, 120, 1)));
        ContextUsageView view = ContextUsageView.prepared(
                projection, new ContextCapacity("PRIVATE_MODEL", 150, 20, 10));

        assertThat(view.availableInputTokens()).isEqualTo(120);
        assertThat(view.freeTokens()).isZero();
        assertThat(view.overflowTokens()).isZero();
        assertThat(view.usage().instructionTokens()).isZero();
        assertThat(view.reasonCodes()).containsExactly(
                ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM);
        assertThat(view.toString()).doesNotContain("PRIVATE_MODEL", "PRIVATE_PROMPT", "PRIVATE_PATH");
    }

    @Test
    void reportsNegativeFreeAndPositiveOverflow() {
        ContextUsageView view = ContextUsageView.prepared(
                projection(140, -20, List.of()), new ContextCapacity("model", 150, 20, 10));

        assertThat(view.freeTokens()).isEqualTo(-20);
        assertThat(view.overflowTokens()).isEqualTo(20);
    }

    @Test
    void rejectsInconsistentDerivedCounts() {
        ContextUsage usage = new ContextUsage(10, 0, 20, 30, 40, 100, 20, ContextEstimateKind.ESTIMATED);

        assertThatThrownBy(() -> new ContextUsageView(
                usage, 150, 20, 10, 120, 20, 1, 1, List.of(),
                ContextPreparationStatus.PREPARED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyConsistentPreparedAndRecoveredConstruction() {
        assertThat(new ContextUsageView(
                usage(0), 150, 20, 10, 120, 20, 0, 1, List.of(),
                ContextPreparationStatus.PREPARED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 0).status())
                .isEqualTo(ContextPreparationStatus.PREPARED);
        assertThat(new ContextUsageView(
                usage(0), 150, 20, 10, 120, 20, 0, 1, List.of(),
                ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(
                        ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW), 1).status())
                .isEqualTo(ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED);
        assertThat(new ContextUsageView(
                usage(0), 150, 20, 10, 120, 20, 0, 1, List.of(),
                ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(
                        ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW,
                        ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED), 2).status())
                .isEqualTo(ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED);
    }

    @Test
    void rejectsContradictoryDirectConstruction() {
        assertThatThrownBy(() -> view(
                new ContextUsage(10, 1, 20, 30, 40, 101, 19, ContextEstimateKind.ESTIMATED),
                ContextPreparationStatus.PREPARED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 0))
                .isInstanceOf(IllegalArgumentException.class);
        for (ContextUsageReasonCode recoveryCode : List.of(
                ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW,
                ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED,
                ContextUsageReasonCode.OVERFLOW_SUMMARY_UNCHANGED,
                ContextUsageReasonCode.OVERFLOW_RECOVERY_CANCELLED)) {
            assertThatThrownBy(() -> view(
                    usage(0), ContextPreparationStatus.PREPARED,
                    List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM, recoveryCode), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> view(
                usage(0), ContextPreparationStatus.PREPARED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view(
                usage(0), ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view(
                usage(0), ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(
                        ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view(
                usage(0), ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(
                        ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW,
                        ContextUsageReasonCode.OVERFLOW_SUMMARY_ADOPTED,
                        ContextUsageReasonCode.OVERFLOW_SUMMARY_UNCHANGED), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view(
                usage(0), ContextPreparationStatus.OVERFLOW_RECOVERY_COMPLETED,
                List.of(
                        ContextUsageReasonCode.INSTRUCTIONS_COALESCED_WITH_SYSTEM,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW,
                        ContextUsageReasonCode.TYPED_CONTEXT_OVERFLOW), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ContextUsageView view(
            ContextUsage usage,
            ContextPreparationStatus status,
            List<ContextUsageReasonCode> reasonCodes,
            int attempts) {
        return new ContextUsageView(usage, 150, 20, 10, 120, 20, 0, 1, List.of(), status,
                reasonCodes, attempts);
    }

    private ContextUsage usage(long instructionTokens) {
        return new ContextUsage(10, instructionTokens, 20, 30, 40, 100 + instructionTokens,
                20 - instructionTokens, ContextEstimateKind.ESTIMATED);
    }

    private ContextProjection projection(
            long total,
            long remaining,
            List<ContextReduction> reductions) {
        return new ContextProjection(
                List.of(new UserMessage("PRIVATE_PROMPT PRIVATE_PATH")),
                new ContextUsage(10, 0, 20, 30, total - 60, total, remaining,
                        ContextEstimateKind.ESTIMATED),
                reductions,
                1);
    }
}
