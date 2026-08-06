package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureStage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 验证模式过滤、精确关联和 sink 失败隔离。 */
class ModelDiagnosticRecorderTest {

    @Test
    void offAvoidsClockAndSinkWorkWhileSafeFiltersLifecycle() {
        AtomicInteger clockReads = new AtomicInteger();
        AtomicInteger sinkCalls = new AtomicInteger();
        ModelDiagnosticRecorder off = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.OFF,
                ignored -> sinkCalls.incrementAndGet(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> { clockReads.incrementAndGet(); return 1L; });

        long started = off.startNanos();
        off.record(ModelDiagnosticKind.FAILURE, request(), ModelFailureStage.REQUEST_TRANSPORT,
                ModelFailureReason.UNKNOWN, ModelDiagnosticStatusClass.NONE, false, false, started);

        assertThat(started).isZero();
        assertThat(clockReads).hasValue(0);
        assertThat(sinkCalls).hasValue(0);

        ModelDiagnosticRecorder safe = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                ignored -> sinkCalls.incrementAndGet(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> 10L);
        safe.record(ModelDiagnosticKind.ATTEMPT_STARTED, request(),
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.UNKNOWN,
                ModelDiagnosticStatusClass.NONE, false, false, 0L);
        assertThat(sinkCalls).hasValue(0);
    }

    @Test
    void startAndRecordIsolateFaultyTimeSources() {
        AtomicInteger nanoReads = new AtomicInteger();
        ModelDiagnosticRecorder recorder = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                ignored -> { throw new AssertionError("sink must not be reached"); },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> {
                    nanoReads.incrementAndGet();
                    throw new IllegalStateException("CLOCK_SENTINEL");
                });

        assertThatCode(() -> {
            long started = recorder.startNanos();
            assertThat(started).isZero();
            recorder.record(ModelDiagnosticKind.FAILURE, request(),
                    ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.TIMEOUT,
                    ModelDiagnosticStatusClass.NONE, false, false, started);
        }).doesNotThrowAnyException();
        assertThat(nanoReads).hasValue(2);
    }

    @Test
    void recordIsolatesWallClockFailure() {
        Clock failingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("CLOCK_SENTINEL");
            }
        };
        ModelDiagnosticRecorder recorder = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE, ignored -> { }, failingClock, () -> 1L);

        assertThatCode(() -> recorder.record(ModelDiagnosticKind.FAILURE, request(),
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.NETWORK_IO,
                ModelDiagnosticStatusClass.NONE, false, false, 0L)).doesNotThrowAnyException();
    }

    @Test
    void correlatesExactSessionRunTurnAttemptAndIsolatesSinkFailure() {
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        ModelDiagnosticRecorder recorder = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                events::add,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
                () -> 5_000_000L);

        try (ModelDiagnosticAttempt.Scope ignored = ModelDiagnosticAttempt.open(3)) {
            recorder.record(ModelDiagnosticKind.FAILURE, request(),
                    ModelFailureStage.TOOL_ARGUMENTS, ModelFailureReason.TOOL_JSON_INVALID,
                    ModelDiagnosticStatusClass.CLIENT_ERROR, true, false, 1_000_000L);
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.sessionCorrelation()).isNotNull();
            assertThat(event.runCorrelation()).isNotNull()
                    .isNotEqualTo(event.sessionCorrelation());
            assertThat(event.toString()).doesNotContain("session-exact", "run-exact");
            assertThat(event.turnNumber()).isEqualTo(7);
            assertThat(event.attemptNumber()).isEqualTo(3);
            assertThat(event.elapsedMillis()).isEqualTo(4);
        });

        ModelDiagnosticRecorder failing = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                ignored -> { throw new IllegalStateException("SINK_SENTINEL"); },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> 1L);
        assertThatCode(() -> failing.record(ModelDiagnosticKind.FAILURE, request(),
                ModelFailureStage.RESPONSE_DECODE, ModelFailureReason.INVALID_RESPONSE,
                ModelDiagnosticStatusClass.NONE, true, false, 0L)).doesNotThrowAnyException();
    }

    @Test
    void adversarialIdentifiersArePseudonymizedButRemainCorrelatable() {
        String sessionSentinel = "SESSION_ATTACK_\\n_token=secret";
        String runSentinel = "RUN_ATTACK_}\\n{secret";
        List<ModelDiagnosticEvent> events = new ArrayList<>();
        ModelDiagnosticRecorder recorder = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.SAFE,
                events::add,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> 1_000_000L);
        ModelRequest adversarial = new ModelRequest(
                new SessionId(sessionSentinel), new RunId(runSentinel), 1,
                List.of(new UserMessage("prompt")), List.of());

        recorder.record(ModelDiagnosticKind.FAILURE, adversarial,
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.NETWORK_IO,
                ModelDiagnosticStatusClass.NONE, false, false, 0L);
        recorder.record(ModelDiagnosticKind.FAILURE, adversarial,
                ModelFailureStage.REQUEST_TRANSPORT, ModelFailureReason.TIMEOUT,
                ModelDiagnosticStatusClass.NONE, false, false, 0L);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).sessionCorrelation())
                .isEqualTo(events.get(1).sessionCorrelation());
        assertThat(events.get(0).runCorrelation())
                .isEqualTo(events.get(1).runCorrelation());
        assertThat(events).allSatisfy(event -> assertThat(event.toString())
                .doesNotContain(sessionSentinel, runSentinel, "token=secret"));
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new SessionId("session-exact"),
                new RunId("run-exact"),
                7,
                List.of(new UserMessage("PROMPT_SENTINEL")),
                List.of());
    }
}
