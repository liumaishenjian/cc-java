package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextReduction;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStrategy;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryDiagnostic;
import io.github.liumaishenjian.ccjava.domain.SummaryOutcome;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SummaryTier;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SummaryReductionCoordinatorTest {

    private static final ContextTokenEstimator ESTIMATOR =
            new CodePointContextTokenEstimator();
    private static final RunId RUN = new RunId("run-summary");

    @Test
    void adoptsC3AndPreservesCanonicalTranscriptDeeply() {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("old fact KEEP-A " + "x".repeat(60)),
                AssistantMessage.text("old answer " + "y".repeat(60)),
                new UserMessage("active request " + "z".repeat(30)));
        List<AgentMessage> canonicalCopy = deepCopy(canonical);
        Fixture fixture = fixture(canonical, 75, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer(
                request -> candidate(request, "KEEP-A concise", 14));

        SummaryOutcome outcome = coordinator(summarizer).reduce(
                RUN,
                fixture.request(),
                fixture.projection(),
                policy(3, true, List.of("KEEP-A"), List.of()),
                CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.ADOPTED);
        assertThat(outcome.attemptedTiers()).containsExactly(SummaryTier.C3_ROLLING);
        assertThat(outcome.projection().appliedReductions())
                .extracting(ContextReduction::strategy)
                .containsExactly(ContextReductionStrategy.ROLLING_MEMORY);
        assertThat(outcome.projection().messages())
                .anyMatch(ContextSummaryMessage.class::isInstance);
        assertThat(canonical).isEqualTo(canonicalCopy);
        assertThat(fixture.request().canonicalMessages()).isEqualTo(canonicalCopy);
    }

    @Test
    void explicitReductionSummarizesEvenWhenAutomaticReductionFitsBudget() {
        List<AgentMessage> canonical = textHistory();
        Fixture fixture = fixture(canonical, 1_000, 1);
        ScriptedSummarizer automaticSummarizer = new ScriptedSummarizer(
                request -> candidate(request, "short", 1));
        SummaryOutcome automatic = coordinator(automaticSummarizer).reduce(
                RUN, fixture.request(), fixture.projection(), policy(3, true, List.of(), List.of()), CancellationToken.none());
        assertThat(automatic.status()).isEqualTo(SummaryOutcome.Status.UNCHANGED);
        assertThat(automatic.diagnostics()).extracting(SummaryDiagnostic::kind)
                .containsExactly(SummaryDiagnostic.Kind.SUMMARY_NOT_REQUIRED);
        assertThat(automaticSummarizer.requests()).isEmpty();

        ScriptedSummarizer explicitSummarizer = new ScriptedSummarizer(
                request -> candidate(request, "short", 1));
        SummaryOutcome explicit = coordinator(explicitSummarizer).reduceExplicitly(
                RUN, fixture.request(), fixture.projection(), policy(3, true, List.of(), List.of()), CancellationToken.none());
        assertThat(explicit.status()).isEqualTo(SummaryOutcome.Status.ADOPTED);
        assertThat(explicitSummarizer.requests()).hasSize(1);
        assertThat(explicit.projection().messages()).anyMatch(ContextSummaryMessage.class::isInstance);
    }

    @Test
    void fallsBackToC4OnlyAfterC3CannotFit() {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("rolling " + "a".repeat(60)),
                AssistantMessage.text("middle " + "b".repeat(80)),
                new UserMessage("later " + "c".repeat(80)),
                new UserMessage("protected " + "d".repeat(20)));
        Fixture fixture = fixture(canonical, 80, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer(
                request -> candidate(request, "c3 still long " + "e".repeat(45), 58),
                request -> candidate(request, "c4 compact", 10));

        SummaryOutcome outcome = coordinator(summarizer).reduce(
                RUN,
                fixture.request(),
                fixture.projection(),
                policy(2, true, List.of(), List.of()),
                CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.ADOPTED);
        assertThat(outcome.attemptedTiers())
                .containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
        assertThat(summarizer.requests()).extracting(SummaryRequest::tier)
                .containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
        assertThat(outcome.projection().appliedReductions().getLast().strategy())
                .isEqualTo(ContextReductionStrategy.FULL_SUMMARY);
        assertThat(outcome.diagnostics()).extracting(SummaryDiagnostic::kind)
                .contains(SummaryDiagnostic.Kind.CAPACITY_STILL_EXCEEDED);
    }

    @Test
    void skipsC3AndC4WhenGatesAreClosed() {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("history " + "x".repeat(80)),
                new UserMessage("active"));
        Fixture fixture = fixture(canonical, 20, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer();

        SummaryOutcome outcome = coordinator(summarizer).reduce(
                RUN,
                fixture.request(),
                fixture.projection(),
                policy(0, false, List.of(), List.of()),
                CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.UNCHANGED);
        assertThat(outcome.attemptedTiers()).isEmpty();
        assertThat(outcome.diagnostics()).extracting(SummaryDiagnostic::kind)
                .containsExactly(
                        SummaryDiagnostic.Kind.ROLLING_WINDOW_INELIGIBLE,
                        SummaryDiagnostic.Kind.FULL_SUMMARY_PREREQUISITES_MISSING);
        assertThat(summarizer.requests()).isEmpty();
    }

    @Test
    void rejectsStaleRevisionAndCoverageMismatchThenFallsBackInOrder() {
        Fixture fixture = fixture(textHistory(), 35, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer(
                request -> new SummaryCandidate(
                        request.tier(), "stale", request.sourceRevision() + 1,
                        request.sourceMessageIds(), 5, 5),
                request -> new SummaryCandidate(
                        request.tier(), "coverage", request.sourceRevision(),
                        List.of(request.sourceMessageIds().getFirst()), 8, 8));

        SummaryOutcome outcome = coordinator(summarizer).reduce(
                RUN,
                fixture.request(),
                fixture.projection(),
                policy(2, true, List.of(), List.of()),
                CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.UNCHANGED);
        assertThat(outcome.attemptedTiers())
                .containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
        assertThat(outcome.diagnostics()).extracting(SummaryDiagnostic::kind)
                .containsExactly(
                        SummaryDiagnostic.Kind.STALE_SOURCE_REVISION,
                        SummaryDiagnostic.Kind.SOURCE_COVERAGE_MISMATCH);
        assertThat(outcome.projection()).isEqualTo(fixture.projection());
    }

    @Test
    void isolatesEmptyOversizeAndNoReductionCandidates() {
        assertRejected(
                new ScriptedSummarizer(request -> null),
                SummaryDiagnostic.Kind.EMPTY_CANDIDATE,
                policy(2, false, List.of(), List.of()));
        assertRejected(
                new ScriptedSummarizer(request -> candidate(
                        request, "x".repeat(80), 20)),
                SummaryDiagnostic.Kind.OUTPUT_BYTE_LIMIT_EXCEEDED,
                new SummaryReductionPolicy(2, false, List.of(), List.of(), 10, 50));
        assertRejected(
                new ScriptedSummarizer(request -> candidate(
                        request, "same", request.sourceEstimatedTokens())),
                SummaryDiagnostic.Kind.NO_TOKEN_REDUCTION,
                policy(2, false, List.of(), List.of()));
    }

    @Test
    void rejectsProtectedAnchorLossAndToolProtocolContamination() {
        assertRejected(
                new ScriptedSummarizer(request -> candidate(request, "compact", 7)),
                SummaryDiagnostic.Kind.PROTECTED_ANCHOR_LOSS,
                policy(2, false, List.of("ANCHOR"), List.of()));
        assertRejected(
                new ScriptedSummarizer(request -> candidate(
                        request, "ANCHOR <tool_call id=x>", 7)),
                SummaryDiagnostic.Kind.TOOL_PROTOCOL_CONTAMINATION,
                policy(2, false, List.of("ANCHOR"), List.of()));
    }

    @Test
    void convertsSummarizerFailureAndCancellationWithoutMutation() {
        Fixture failureFixture = fixture(textHistory(), 35, 1);
        ScriptedSummarizer failed = new ScriptedSummarizer(request -> {
            throw new IllegalStateException("secret raw failure");
        });
        SummaryOutcome failure = coordinator(failed).reduce(
                RUN,
                failureFixture.request(),
                failureFixture.projection(),
                policy(2, false, List.of(), List.of()),
                CancellationToken.none());
        assertThat(failure.status()).isEqualTo(SummaryOutcome.Status.UNCHANGED);
        assertThat(failure.diagnostics()).extracting(SummaryDiagnostic::kind)
                .contains(SummaryDiagnostic.Kind.SUMMARIZER_FAILED);
        assertThat(failure.toString()).doesNotContain("secret raw failure");

        CancellationSource source = new CancellationSource();
        Fixture cancelledFixture = fixture(textHistory(), 35, 1);
        ScriptedSummarizer cancelling = new ScriptedSummarizer(request -> {
            source.cancel();
            return candidate(request, "compact", 7);
        });
        RunId cancelledRun = new RunId("run-cancel");
        SummaryOutcome cancelled = coordinator(cancelledRun, cancelling).reduce(
                cancelledRun,
                cancelledFixture.request(),
                cancelledFixture.projection(),
                policy(2, true, List.of(), List.of()),
                source.token());
        assertThat(cancelled.status()).isEqualTo(SummaryOutcome.Status.CANCELLED);
        assertThat(cancelled.projection()).isEqualTo(cancelledFixture.projection());
        assertThat(cancelling.requests()).hasSize(1);
    }

    @Test
    void preservesCanonicalProtectedTailAndExactToolCallResultOrdering() {
        AssistantMessage calls = AssistantMessage.tools(List.of(
                new ToolCall("call-a", "read_a", JsonObject.empty()),
                new ToolCall("call-b", "read_b", JsonObject.empty())));
        ToolResultMessage resultA = new ToolResultMessage(
                ToolResult.success("call-a", "read_a", "result-a"));
        ToolResultMessage resultB = new ToolResultMessage(
                ToolResult.success("call-b", "read_b", "result-b"));
        UserMessage protectedTail = new UserMessage("active protected tail");
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("old " + "x".repeat(80)),
                calls,
                resultA,
                resultB,
                protectedTail);
        Fixture fixture = fixture(canonical, 100, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer(
                request -> candidate(request, "compact", 7));

        SummaryOutcome outcome = coordinator(summarizer).reduce(
                RUN, fixture.request(), fixture.projection(),
                policy(5, false, List.of(), List.of()), CancellationToken.none());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.ADOPTED);
        assertThat(outcome.projection().messages().getLast()).isEqualTo(protectedTail);
        assertThat(outcome.projection().messages()).containsExactly(
                new SystemMessage("system"),
                new ContextSummaryMessage(
                        SummaryTier.C3_ROLLING,
                        "compact",
                        List.of("r7:m1", "r7:m2", "r7:m3", "r7:m4")),
                protectedTail);
        assertThat(canonical).containsExactly(
                new SystemMessage("system"),
                new UserMessage("old " + "x".repeat(80)),
                calls, resultA, resultB, protectedTail);
        assertThat(canonical.subList(2, 5)).containsExactly(calls, resultA, resultB);

        ArrayList<AgentMessage> altered = new ArrayList<>(canonical);
        altered.set(altered.size() - 1, new UserMessage("different tail"));
        ContextProjection invalidProjection = new ContextProjection(
                altered,
                ESTIMATOR.estimate(altered, fixture.request().capacity()),
                List.of(),
                fixture.request().sourceRevision());
        RunId mismatchRun = new RunId("run-tail-mismatch");
        assertThatThrownBy(() -> coordinator(mismatchRun, new ScriptedSummarizer()).reduce(
                mismatchRun,
                fixture.request(),
                invalidProjection,
                policy(2, false, List.of(), List.of()),
                CancellationToken.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected tail");
    }

    @Test
    void cancellationAfterC3RejectionPreventsC4AndPreservesProjection() {
        Fixture fixture = fixture(textHistory(), 35, 1);
        CancellationSource source = new CancellationSource();
        ScriptedSummarizer summarizer = new ScriptedSummarizer(request -> {
            source.cancel();
            return Optional.<SummaryCandidate>empty();
        });

        RunId runId = new RunId("run-cancel-between-tiers");
        SummaryOutcome outcome = coordinator(runId, summarizer).reduce(
                runId,
                fixture.request(), fixture.projection(),
                policy(2, true, List.of(), List.of()), source.token());

        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.CANCELLED);
        assertThat(outcome.projection()).isEqualTo(fixture.projection());
        assertThat(summarizer.requests()).singleElement()
                .extracting(SummaryRequest::tier)
                .isEqualTo(SummaryTier.C3_ROLLING);
    }

    @Test
    void cooldownPreventsRepeatedPressureAndEachTierRunsOncePerRevision() {
        Fixture fixture = fixture(textHistory(), 35, 1);
        ScriptedSummarizer summarizer = new ScriptedSummarizer(
                request -> Optional.<SummaryCandidate>empty(),
                request -> Optional.<SummaryCandidate>empty());
        SummaryReductionCoordinator coordinator = coordinator(summarizer);
        SummaryReductionPolicy policy = policy(2, true, List.of(), List.of());

        SummaryOutcome first = coordinator.reduce(
                RUN, fixture.request(), fixture.projection(), policy,
                CancellationToken.none());
        SummaryOutcome second = coordinator.reduce(
                RUN, fixture.request(), fixture.projection(), policy,
                CancellationToken.none());

        assertThat(first.attemptedTiers())
                .containsExactly(SummaryTier.C3_ROLLING, SummaryTier.C4_FULL);
        assertThat(second.attemptedTiers()).isEmpty();
        assertThat(second.diagnostics()).extracting(SummaryDiagnostic::kind)
                .containsExactly(
                        SummaryDiagnostic.Kind.ATTEMPT_COOLDOWN,
                        SummaryDiagnostic.Kind.ATTEMPT_COOLDOWN);
        assertThat(summarizer.requests()).hasSize(2);
    }

    private void assertRejected(
            ScriptedSummarizer summarizer,
            SummaryDiagnostic.Kind expected,
            SummaryReductionPolicy policy) {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("history ANCHOR " + "x".repeat(80)),
                new UserMessage("active"));
        Fixture fixture = fixture(canonical, 20, 1);
        RunId runId = new RunId("run-" + expected.name());
        SummaryOutcome outcome = coordinator(runId, summarizer).reduce(
                runId,
                fixture.request(),
                fixture.projection(),
                policy,
                CancellationToken.none());
        assertThat(outcome.status()).isEqualTo(SummaryOutcome.Status.UNCHANGED);
        assertThat(outcome.diagnostics()).extracting(SummaryDiagnostic::kind)
                .contains(expected);
        assertThat(outcome.projection()).isEqualTo(fixture.projection());
    }

    private SummaryReductionCoordinator coordinator(ContextSummarizer summarizer) {
        return coordinator(RUN, summarizer);
    }

    private SummaryReductionCoordinator coordinator(
            RunId runId,
            ContextSummarizer summarizer) {
        return new SummaryReductionCoordinator(
                summarizer, ESTIMATOR, new SummaryAttemptGuard(runId));
    }

    private Fixture fixture(
            List<AgentMessage> canonical,
            long availableTokens,
            int protectedMessages) {
        ContextCapacity capacity = capacity(availableTokens);
        ProjectionRequest request = new ProjectionRequest(
                canonical, capacity, 7, protectedMessages, true);
        ContextUsage usage = ESTIMATOR.estimate(canonical, capacity);
        ContextProjection projection = new ContextProjection(
                canonical, usage, List.of(), 7);
        return new Fixture(request, projection);
    }

    private SummaryReductionPolicy policy(
            int rollingEnd,
            boolean fullEligible,
            List<String> rollingAnchors,
            List<String> fullAnchors) {
        return new SummaryReductionPolicy(
                rollingEnd, fullEligible, rollingAnchors, fullAnchors, 1_000, 200);
    }

    private List<AgentMessage> textHistory() {
        return List.of(
                new SystemMessage("system"),
                new UserMessage("history one " + "x".repeat(70)),
                AssistantMessage.text("history two " + "y".repeat(70)),
                new UserMessage("active"));
    }

    private SummaryCandidate candidate(
            SummaryRequest request,
            String text,
            long tokens) {
        return new SummaryCandidate(
                request.tier(),
                text,
                request.sourceRevision(),
                request.sourceMessageIds(),
                text.getBytes(StandardCharsets.UTF_8).length,
                tokens);
    }

    private List<AgentMessage> deepCopy(List<AgentMessage> source) {
        return List.copyOf(new ArrayList<>(source));
    }

    private ContextCapacity capacity(long availableTokens) {
        return new ContextCapacity("offline", availableTokens + 2, 1, 1);
    }

    private record Fixture(
            ProjectionRequest request,
            ContextProjection projection) {
    }

    @FunctionalInterface
    private interface Step {
        Object run(SummaryRequest request);
    }

    private final class ScriptedSummarizer implements ContextSummarizer {
        private final ArrayDeque<Step> steps = new ArrayDeque<>();
        private final ArrayList<SummaryRequest> requests = new ArrayList<>();

        private ScriptedSummarizer(Step... steps) {
            this.steps.addAll(List.of(steps));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<SummaryCandidate> summarize(
                SummaryRequest request,
                CancellationToken cancellationToken) {
            requests.add(request);
            if (steps.isEmpty()) {
                throw new AssertionError("unexpected summary call");
            }
            Object result = steps.removeFirst().run(request);
            if (result == null) {
                return Optional.empty();
            }
            if (result instanceof Optional<?> optional) {
                return (Optional<SummaryCandidate>) optional;
            }
            return Optional.of((SummaryCandidate) result);
        }

        private List<SummaryRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
