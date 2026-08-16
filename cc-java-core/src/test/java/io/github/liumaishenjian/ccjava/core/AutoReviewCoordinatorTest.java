package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutoReviewCoordinatorTest {

    private static final SessionId SESSION = new SessionId("session-1");
    private static final RunId RUN = new RunId("run-1");
    private static final PermissionSelector SCOPE = new PermissionSelector(
            "run_command", ToolSource.BUILT_IN, "scope-digest");

    @Test
    void onlyFinalAskCanReachGatewayAndStrictAllowIsOnceOnly() {
        AtomicInteger calls = new AtomicInteger();
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) -> {
            calls.incrementAndGet();
            return ApprovalReviewResult.allowOnce();
        });
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.DENY), request(),
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.NOT_FINAL_ASK);
            assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ALLOW), request(),
                    CancellationToken.none(), circuit).status())
                    .isEqualTo(AutoReviewDecision.Status.NOT_FINAL_ASK);
            assertThat(calls).hasValue(0);

            InMemorySessionPermissionState sessionState = new InMemorySessionPermissionState();
            AutoReviewDecision decision = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                    CancellationToken.none(), circuit);
            assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
            assertThat(calls).hasValue(1);
            assertThat(sessionState.rules(SESSION)).isEmpty();
        }
    }

    @Test
    void providerTimeoutParseInternalNullAndExceptionFailClosed() {
        for (ApprovalReviewResult.FailureKind kind : new ApprovalReviewResult.FailureKind[]{
                ApprovalReviewResult.FailureKind.PROVIDER,
                ApprovalReviewResult.FailureKind.TIMEOUT,
                ApprovalReviewResult.FailureKind.PARSE,
                ApprovalReviewResult.FailureKind.INTERNAL
        }) {
            AutoReviewCoordinator coordinator = new AutoReviewCoordinator(
                    (request, token) -> ApprovalReviewResult.failure(kind));
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                        CancellationToken.none(), circuit);
                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(kind);
            }
        }
        for (ApprovalReviewGateway gateway : new ApprovalReviewGateway[]{
                (request, token) -> null,
                (request, token) -> { throw new IllegalStateException("sentinel"); }
        }) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = new AutoReviewCoordinator(gateway).reviewFinalAsk(
                        outcome(PermissionDecision.ASK), request(), CancellationToken.none(), circuit);
                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(ApprovalReviewResult.FailureKind.INTERNAL);
            }
        }
    }

    @Test
    void cancellationPropagatesAndDoesNotCountCircuit() {
        CancellationSource before = new CancellationSource();
        before.cancel();
        AtomicInteger calls = new AtomicInteger();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) -> {
                calls.incrementAndGet();
                return ApprovalReviewResult.allowOnce();
            }).reviewFinalAsk(outcome(PermissionDecision.ASK), request(), before.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(calls).hasValue(0);
            assertThat(circuit.consecutiveFailures()).isZero();
        }

        CancellationSource during = new CancellationSource();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) -> {
                during.cancel();
                return ApprovalReviewResult.allowOnce();
            }).reviewFinalAsk(outcome(PermissionDecision.ASK), request(), during.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(circuit.consecutiveFailures()).isZero();
        }
        CancellationSource reported = new CancellationSource();
        reported.cancel();
        try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
            assertThatThrownBy(() -> new AutoReviewCoordinator((request, token) ->
                    ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.CANCELLED))
                    .reviewFinalAsk(outcome(PermissionDecision.ASK), request(), reported.token(), circuit))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(circuit.consecutiveFailures()).isZero();
        }
    }

    @Test
    void fabricatedGatewayCancellationFailsClosedAndCountsAsInternal() {
        for (ApprovalReviewGateway gateway : new ApprovalReviewGateway[]{
                (request, token) -> { throw new java.util.concurrent.CancellationException("fabricated"); },
                (request, token) -> ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.CANCELLED)
        }) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN)) {
                AutoReviewDecision decision = new AutoReviewCoordinator(gateway).reviewFinalAsk(
                        outcome(PermissionDecision.ASK), request(), CancellationToken.none(), circuit);

                assertThat(decision.status()).isEqualTo(AutoReviewDecision.Status.FAILED_CLOSED);
                assertThat(decision.failure()).contains(ApprovalReviewResult.FailureKind.INTERNAL);
                assertThat(circuit.consecutiveFailures()).isOne();
            }
        }
    }

    @Test
    void denyAndFailureAccumulateAllowResetsAndThirdCurrentDecisionRequestsStop() {
        AtomicInteger calls = new AtomicInteger();
        ApprovalReviewResult[] results = {
                ApprovalReviewResult.deny(),
                ApprovalReviewResult.allowOnce(),
                ApprovalReviewResult.deny(),
                ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.PROVIDER),
                ApprovalReviewResult.deny()
        };
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator((request, token) ->
                results[calls.getAndIncrement()]);
        AutoReviewCircuit circuit = new AutoReviewCircuit(RUN);
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        assertThat(circuit.consecutiveFailures()).isOne();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status()).isEqualTo(AutoReviewDecision.Status.ALLOW_ONCE);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).stopAfterCurrentDeny()).isFalse();
        AutoReviewDecision third = coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit);
        assertThat(third.status()).isEqualTo(AutoReviewDecision.Status.DENY);
        assertThat(third.stopAfterCurrentDeny()).isTrue();
        assertThat(circuit.consecutiveFailures()).isEqualTo(3);
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status())
                .isEqualTo(AutoReviewDecision.Status.CIRCUIT_OPEN);
        assertThat(calls).hasValue(5);
        circuit.close();
        assertThat(coordinator.reviewFinalAsk(outcome(PermissionDecision.ASK), request(),
                CancellationToken.none(), circuit).status())
                .isEqualTo(AutoReviewDecision.Status.RUN_CLOSED);
    }

    private static PermissionOutcome outcome(PermissionDecision decision) {
        return PermissionOutcome.of(decision, PermissionReason.EFFECT_DEFAULT, SCOPE);
    }

    private static ApprovalReviewRequest request() {
        return new ApprovalReviewRequest(
                SESSION, RUN, "call-1", "run_command", ToolEffect.EXECUTE_PROCESS,
                ToolSource.BUILT_IN, true, "执行受控测试命令");
    }
}
