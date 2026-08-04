package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextOverflowRetryCoordinatorTest {

    private static final ContextTokenEstimator ESTIMATOR =
            new CodePointContextTokenEstimator();
    private static final RunId RUN = new RunId("run-overflow");

    @Test
    void retriesExactlyOnceAfterOverflowAndNeverThirdTime() {
        Fixture fixture = fixture(true);
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextSummarizer summarizer = (request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "compact"));
        };
        ContextOverflowRetryCoordinator coordinator = coordinator(summarizer);
        AtomicInteger requests = new AtomicInteger();
        ArrayList<ContextProjection> projections = new ArrayList<>();

        ContextOverflowRetryCoordinator.Outcome<String> outcome = coordinator.execute(
                RUN,
                fixture.request(),
                fixture.projection(),
                policy(),
                CancellationToken.none(),
                projection -> {
                    projections.add(projection);
                    requests.incrementAndGet();
                    return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                });

        assertThat(outcome.modelRequestAttempts()).isEqualTo(2);
        assertThat(outcome.result().status())
                .isEqualTo(ContextOverflowRetryCoordinator.AttemptStatus.OVERFLOW);
        assertThat(requests).hasValue(2);
        assertThat(summaryCalls).hasValue(1);
        assertThat(projections.getFirst()).isEqualTo(fixture.projection());
        assertThat(projections.getLast().messages())
                .anyMatch(ContextSummaryMessage.class::isInstance);
    }

    @Test
    void repeatedOverflowForSameRunRevisionCannotRetryOrResummarize() {
        Fixture fixture = fixture(true);
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextSummarizer summarizer = (request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "compact"));
        };
        ContextOverflowRetryCoordinator coordinator = coordinator(summarizer);
        AtomicInteger requests = new AtomicInteger();
        ContextOverflowRetryCoordinator.ProjectionAttempt<String> attempt = projection -> {
            requests.incrementAndGet();
            return ContextOverflowRetryCoordinator.AttemptResult.overflow();
        };

        ContextOverflowRetryCoordinator.Outcome<String> first = coordinator.execute(
                RUN, fixture.request(), fixture.projection(),
                policy(), CancellationToken.none(), attempt);
        ContextOverflowRetryCoordinator.Outcome<String> second = coordinator.execute(
                RUN, fixture.request(), fixture.projection(),
                policy(), CancellationToken.none(), attempt);

        assertThat(first.modelRequestAttempts()).isEqualTo(2);
        assertThat(second.modelRequestAttempts()).isOne();
        assertThat(requests).hasValue(3);
        assertThat(summaryCalls).hasValue(1);
    }

    @Test
    void cancellationAfterInitialOverflowPreventsSummaryAndRetry() {
        Fixture fixture = fixture(true);
        CancellationSource source = new CancellationSource();
        AtomicInteger summaryCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ContextOverflowRetryCoordinator coordinator = coordinator((request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "compact"));
        });

        ContextOverflowRetryCoordinator.Outcome<String> outcome = coordinator.execute(
                RUN, fixture.request(), fixture.projection(), policy(), source.token(),
                projection -> {
                    modelCalls.incrementAndGet();
                    source.cancel();
                    return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                });

        assertThat(outcome.result().status())
                .isEqualTo(ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED);
        assertThat(outcome.modelRequestAttempts()).isOne();
        assertThat(modelCalls).hasValue(1);
        assertThat(summaryCalls).hasValue(0);
        assertThat(outcome.projection()).isEqualTo(fixture.projection());
    }

    @Test
    void cancellationAfterAdoptionPreventsRetry() {
        Fixture fixture = fixture(true);
        CancellationSource source = new CancellationSource();
        AtomicInteger modelCalls = new AtomicInteger();
        ContextOverflowRetryCoordinator coordinator = coordinator((request, cancellation) -> {
            source.cancel();
            return Optional.of(candidate(request, "compact"));
        });

        ContextOverflowRetryCoordinator.Outcome<String> outcome = coordinator.execute(
                RUN, fixture.request(), fixture.projection(), policy(), source.token(),
                projection -> {
                    modelCalls.incrementAndGet();
                    return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                });

        assertThat(outcome.result().status())
                .isEqualTo(ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED);
        assertThat(outcome.modelRequestAttempts()).isOne();
        assertThat(modelCalls).hasValue(1);
        assertThat(outcome.projection()).isEqualTo(fixture.projection());
    }

    @Test
    void disabledRecoveryAndCancellationDoNotSummarizeOrRetry() {
        Fixture disabled = fixture(false);
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextOverflowRetryCoordinator coordinator = coordinator((request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "compact"));
        });
        AtomicInteger disabledRequests = new AtomicInteger();
        ContextOverflowRetryCoordinator.Outcome<String> disabledOutcome = coordinator.execute(
                RUN, disabled.request(), disabled.projection(),
                policy(), CancellationToken.none(), projection -> {
                    disabledRequests.incrementAndGet();
                    return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                });

        CancellationSource source = new CancellationSource();
        source.cancel();
        Fixture cancelled = fixture(true);
        AtomicInteger cancelledRequests = new AtomicInteger();
        ContextOverflowRetryCoordinator.Outcome<String> cancelledOutcome = coordinator.execute(
                RUN, cancelled.request(), cancelled.projection(),
                policy(), source.token(), projection -> {
                    cancelledRequests.incrementAndGet();
                    return ContextOverflowRetryCoordinator.AttemptResult.overflow();
                });

        assertThat(disabledOutcome.modelRequestAttempts()).isOne();
        assertThat(disabledRequests).hasValue(1);
        assertThat(cancelledOutcome.modelRequestAttempts()).isZero();
        assertThat(cancelledOutcome.result().status())
                .isEqualTo(ContextOverflowRetryCoordinator.AttemptStatus.CANCELLED);
        assertThat(cancelledRequests).hasValue(0);
        assertThat(summaryCalls).hasValue(0);
    }

    private ContextOverflowRetryCoordinator coordinator(ContextSummarizer summarizer) {
        SummaryReductionCoordinator summary = new SummaryReductionCoordinator(
                summarizer, ESTIMATOR, new SummaryAttemptGuard(RUN));
        return new ContextOverflowRetryCoordinator(RUN, summary);
    }

    private Fixture fixture(boolean recoveryAvailable) {
        List<AgentMessage> canonical = List.of(
                new SystemMessage("system"),
                new UserMessage("old history " + "x".repeat(90)),
                new UserMessage("active"));
        ContextCapacity capacity = new ContextCapacity("offline", 32, 1, 1);
        ProjectionRequest request = new ProjectionRequest(
                canonical, capacity, 9, 1, recoveryAvailable);
        ContextUsage usage = ESTIMATOR.estimate(canonical, capacity);
        ContextProjection projection = new ContextProjection(
                canonical, usage, List.of(), 9);
        return new Fixture(request, projection);
    }

    private SummaryReductionPolicy policy() {
        return new SummaryReductionPolicy(
                2, true, List.of(), List.of(), 1_000, 200);
    }

    private SummaryCandidate candidate(SummaryRequest request, String text) {
        return new SummaryCandidate(
                request.tier(),
                text,
                request.sourceRevision(),
                request.sourceMessageIds(),
                text.getBytes(StandardCharsets.UTF_8).length,
                7);
    }

    private record Fixture(
            ProjectionRequest request,
            ContextProjection projection) {
    }
}
