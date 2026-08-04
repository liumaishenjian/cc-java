package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextReductionOutcomeTest {

    private static final ContextReduction REDUCTION = new ContextReduction(
            ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION,
            100,
            60,
            1);

    @Test
    void acceptsValidReducedAndUnchangedOutcomes() {
        ContextUsage initial = usage(100, -10);
        ContextUsage reducedUsage = usage(60, 5);
        ContextReductionOutcome reduced = new ContextReductionOutcome(
                ContextReductionStatus.REDUCED,
                projection(reducedUsage, List.of(REDUCTION)),
                initial,
                reducedUsage,
                ContextReductionReason.LARGE_PAYLOAD_REDUCED);

        ContextUsage unchangedUsage = usage(40, 20);
        ContextReductionOutcome unchanged = new ContextReductionOutcome(
                ContextReductionStatus.UNCHANGED,
                projection(unchangedUsage, List.of()),
                unchangedUsage,
                unchangedUsage,
                ContextReductionReason.WITHIN_CAPACITY);

        assertThat(reduced.status()).isEqualTo(ContextReductionStatus.REDUCED);
        assertThat(unchanged.status()).isEqualTo(ContextReductionStatus.UNCHANGED);
    }

    @Test
    void rejectsInvalidReducedOutcomes() {
        ContextUsage initial = usage(100, -10);
        ContextUsage fitting = usage(60, 5);

        assertThatThrownBy(() -> new ContextReductionOutcome(
                        ContextReductionStatus.REDUCED,
                        projection(fitting, List.of()),
                        initial,
                        fitting,
                        ContextReductionReason.LARGE_PAYLOAD_REDUCED))
                .isInstanceOf(IllegalArgumentException.class);

        ContextUsage overflowing = usage(60, -1);
        assertThatThrownBy(() -> new ContextReductionOutcome(
                        ContextReductionStatus.REDUCED,
                        projection(overflowing, List.of(REDUCTION)),
                        initial,
                        overflowing,
                        ContextReductionReason.LARGE_PAYLOAD_REDUCED))
                .isInstanceOf(IllegalArgumentException.class);

        ContextUsage sameTotal = usage(100, 5);
        assertThatThrownBy(() -> new ContextReductionOutcome(
                        ContextReductionStatus.REDUCED,
                        projection(sameTotal, List.of(REDUCTION)),
                        initial,
                        sameTotal,
                        ContextReductionReason.LARGE_PAYLOAD_REDUCED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidUnchangedOutcomes() {
        ContextUsage initial = usage(70, 5);
        ContextUsage changed = usage(60, 15);

        assertThatThrownBy(() -> new ContextReductionOutcome(
                        ContextReductionStatus.UNCHANGED,
                        projection(changed, List.of()),
                        initial,
                        changed,
                        ContextReductionReason.WITHIN_CAPACITY))
                .isInstanceOf(IllegalArgumentException.class);

        ContextUsage overflowing = usage(70, -1);
        assertThatThrownBy(() -> new ContextReductionOutcome(
                        ContextReductionStatus.UNCHANGED,
                        projection(overflowing, List.of()),
                        overflowing,
                        overflowing,
                        ContextReductionReason.WITHIN_CAPACITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFailureAndCancellationWithReductionOrUsageChange() {
        for (ContextReductionStatus status : List.of(
                ContextReductionStatus.CONTEXT_LIMIT_REACHED,
                ContextReductionStatus.CANCELLED)) {
            ContextReductionReason reason = status == ContextReductionStatus.CANCELLED
                    ? ContextReductionReason.CANCELLED
                    : ContextReductionReason.NO_SAFE_REDUCTION_AVAILABLE;
            ContextUsage initial = usage(100, -10);

            assertThatThrownBy(() -> new ContextReductionOutcome(
                            status,
                            projection(initial, List.of(REDUCTION)),
                            initial,
                            initial,
                            reason))
                    .isInstanceOf(IllegalArgumentException.class);

            ContextUsage changed = usage(90, -1);
            assertThatThrownBy(() -> new ContextReductionOutcome(
                            status,
                            projection(changed, List.of()),
                            initial,
                            changed,
                            reason))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ContextProjection projection(
            ContextUsage usage,
            List<ContextReduction> reductions) {
        return new ContextProjection(List.of(), usage, reductions, 1);
    }

    private ContextUsage usage(long totalTokens, long remainingTokens) {
        return new ContextUsage(
                0,
                0,
                totalTokens,
                0,
                0,
                totalTokens,
                remainingTokens,
                ContextEstimateKind.ESTIMATED);
    }
}
