package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SummaryContractsTest {

    @Test
    void summaryRequestDefensivelyCopiesBoundedSnapshotIdsAndAnchors() {
        ArrayList<String> ids = new ArrayList<>(List.of("m-1", "m-2"));
        ArrayList<String> anchors = new ArrayList<>(List.of("KEEP-FACT"));

        SummaryRequest request = request(ids, anchors);
        ids.clear();
        anchors.clear();

        assertThat(request.sourceMessageIds()).containsExactly("m-1", "m-2");
        assertThat(request.requiredProtectedAnchors()).containsExactly("KEEP-FACT");
        assertThatThrownBy(() -> request.sourceMessageIds().add("m-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void summaryRequestRejectsInvalidRevisionCoverageAndBudgets() {
        assertThatThrownBy(() -> new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "source KEEP-FACT",
                -1,
                List.of("m-1"),
                List.of("KEEP-FACT"),
                100,
                20,
                40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(List.of("m-1", "m-1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(List.of("bad id"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(List.of("m-1"), List.of("MISSING")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "source",
                7,
                List.of("m-1"),
                List.of(),
                0,
                20,
                40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "source",
                7,
                List.of("m-1"),
                List.of(),
                100,
                40,
                40))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryRequestRejectsUnpairedSurrogateAndHardBounds() {
        assertThatThrownBy(() -> new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "invalid\uD800",
                7,
                List.of("m-1"),
                List.of(),
                100,
                20,
                40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");

        List<String> tooManyIds = java.util.stream.IntStream.rangeClosed(
                        1, SummaryRequest.MAX_SOURCE_MESSAGES + 1)
                .mapToObj(index -> "m-" + index)
                .toList();
        assertThatThrownBy(() -> request(tooManyIds, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryCandidateChecksLocalUtf8AccountingAndBounds() {
        SummaryCandidate candidate = candidate(SummaryTier.C3_ROLLING, "summary", 7, List.of("m-1"), 10);

        assertThat(candidate.utf8Bytes()).isEqualTo(7);
        assertThat(candidate.sourceMessageIds()).containsExactly("m-1");
        assertThatThrownBy(() -> new SummaryCandidate(
                SummaryTier.C3_ROLLING,
                " ",
                7,
                List.of("m-1"),
                1,
                10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryCandidate(
                SummaryTier.C3_ROLLING,
                "summary",
                7,
                List.of("m-1"),
                6,
                10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(
                SummaryTier.C3_ROLLING, "summary", -1, List.of("m-1"), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(
                SummaryTier.C3_ROLLING, "summary", 7, List.of("m-1"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticsEnforceGlobalAndTierSpecificShapes() {
        assertThat(SummaryDiagnostic.global(
                SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED).tier()).isEmpty();
        assertThat(SummaryDiagnostic.tier(
                SummaryDiagnostic.Kind.STALE_SOURCE_REVISION,
                SummaryTier.C3_ROLLING).tier())
                .contains(SummaryTier.C3_ROLLING);

        assertThatThrownBy(() -> SummaryDiagnostic.tier(
                SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED,
                SummaryTier.C3_ROLLING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SummaryDiagnostic.global(
                SummaryDiagnostic.Kind.CANCELLED).tier()).isEmpty();
        assertThatThrownBy(() -> SummaryDiagnostic.global(
                SummaryDiagnostic.Kind.EMPTY_CANDIDATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryDiagnostic.tier(
                SummaryDiagnostic.Kind.ROLLING_WINDOW_INELIGIBLE,
                SummaryTier.C4_FULL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unchangedAndCancelledOutcomesMustDeeplyPreservePreviousProjection() {
        ContextProjection previous = projection(120, -20, List.of());
        SummaryOutcome unchanged = new SummaryOutcome(
                SummaryOutcome.Status.UNCHANGED,
                previous,
                previous,
                List.of(SummaryTier.C3_ROLLING),
                List.of(SummaryDiagnostic.tier(
                        SummaryDiagnostic.Kind.EMPTY_CANDIDATE,
                        SummaryTier.C3_ROLLING)),
                Optional.empty());
        SummaryOutcome cancelled = new SummaryOutcome(
                SummaryOutcome.Status.CANCELLED,
                previous,
                previous,
                List.of(SummaryTier.C3_ROLLING),
                List.of(SummaryDiagnostic.tier(
                        SummaryDiagnostic.Kind.CANCELLED,
                        SummaryTier.C3_ROLLING)),
                Optional.empty());

        assertThat(unchanged.projection()).isEqualTo(previous);
        assertThat(cancelled.projection()).isEqualTo(previous);
        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.UNCHANGED,
                previous,
                projection(110, -10, List.of()),
                List.of(),
                List.of(SummaryDiagnostic.global(
                        SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.CANCELLED,
                previous,
                previous,
                List.of(),
                List.of(SummaryDiagnostic.global(
                        SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adoptedOutcomeAppendsExactlyOneMatchingReduction() {
        ContextProjection previous = projection(120, -20, List.of());
        SummaryCandidate candidate = candidate(
                SummaryTier.C3_ROLLING, "summary", 7, List.of("m-1", "m-2"), 20);
        ContextReduction summaryReduction = new ContextReduction(
                ContextReductionStrategy.ROLLING_MEMORY,
                120,
                70,
                2);
        ContextProjection adopted = projection(70, 30, List.of(summaryReduction));

        SummaryOutcome outcome = new SummaryOutcome(
                SummaryOutcome.Status.ADOPTED,
                previous,
                adopted,
                List.of(SummaryTier.C3_ROLLING),
                List.of(),
                Optional.of(candidate));

        assertThat(outcome.projection().appliedReductions())
                .containsExactly(summaryReduction);
        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.ADOPTED,
                previous,
                adopted,
                List.of(SummaryTier.C4_FULL),
                List.of(),
                Optional.of(candidate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.ADOPTED,
                previous,
                previous,
                List.of(SummaryTier.C3_ROLLING),
                List.of(),
                Optional.of(candidate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptedTiersAreOrderedUniqueAndBounded() {
        ContextProjection previous = projection(120, -20, List.of());
        SummaryDiagnostic diagnostic = SummaryDiagnostic.tier(
                SummaryDiagnostic.Kind.EMPTY_CANDIDATE,
                SummaryTier.C3_ROLLING);

        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.UNCHANGED,
                previous,
                previous,
                List.of(SummaryTier.C4_FULL, SummaryTier.C3_ROLLING),
                List.of(diagnostic),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryOutcome(
                SummaryOutcome.Status.UNCHANGED,
                previous,
                previous,
                List.of(SummaryTier.C3_ROLLING, SummaryTier.C3_ROLLING),
                List.of(diagnostic),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SummaryRequest request(List<String> ids, List<String> anchors) {
        return new SummaryRequest(
                SummaryTier.C3_ROLLING,
                "source KEEP-FACT",
                7,
                ids,
                anchors,
                100,
                20,
                40);
    }

    private SummaryCandidate candidate(
            SummaryTier tier,
            String text,
            long revision,
            List<String> ids,
            long tokens) {
        return new SummaryCandidate(
                tier,
                text,
                revision,
                ids,
                text.getBytes(StandardCharsets.UTF_8).length,
                tokens);
    }

    private ContextProjection projection(
            long total,
            long remaining,
            List<ContextReduction> reductions) {
        ContextUsage usage = new ContextUsage(
                0,
                0,
                total,
                0,
                0,
                total,
                remaining,
                ContextEstimateKind.ESTIMATED);
        return new ContextProjection(List.of(), usage, reductions, 7);
    }
}
