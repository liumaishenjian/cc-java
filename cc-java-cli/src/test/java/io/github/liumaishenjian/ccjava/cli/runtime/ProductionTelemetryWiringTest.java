package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetryExporter;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignal;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionTelemetryWiringTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void runLifecycleExportsWhitelistedSignalsAndFlushesOnClose() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        try (AutoCloseable installed = TelemetryExporters.install(() -> exporter)) {
            ModelGateway model = request -> ModelTurn.text("done");
            try (HeadlessRuntimeSession session = new HeadlessRuntimeSession(
                    model, AgentEventSink.noop(),
                    new HeadlessRuntimeOptions(temp, "fake", Duration.ofSeconds(2)))) {
                session.open();
                session.run("SECRET_SENTINEL prompt");
            }
        }
        assertThat(exporter.signals).extracting(TelemetrySignal::kind)
                .contains(io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind.RUN,
                        io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind.MODEL_TURN,
                        io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind.TOKEN_USAGE,
                        io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind.STOP);
        assertThat(exporter.signals.stream().filter(signal -> signal.duration().isPresent())
                .map(signal -> signal.duration().orElseThrow()).anyMatch(value -> !value.isZero())).isTrue();
        assertThat(exporter.signals).anyMatch(signal -> "false".equals(signal.attributes().get("usage_known")));
        assertThat(exporter.signals.toString()).doesNotContain("SECRET_SENTINEL", temp.toString());
        assertThat(exporter.flushed).isTrue();
        assertThat(exporter.closed).isTrue();
    }

    @Test
    void exporterFailureNeverChangesCompletedRun() throws Exception {
        try (AutoCloseable installed = TelemetryExporters.install(() -> new TelemetryExporter() {
            @Override public void export(TelemetrySignal signal) { throw new IllegalStateException("sink"); }
            @Override public boolean flush(Duration timeout) { throw new IllegalStateException("flush"); }
            @Override public void close() { }
        })) {
            try (HeadlessRuntimeSession session = new HeadlessRuntimeSession(
                    request -> ModelTurn.text("done"), AgentEventSink.noop(),
                    new HeadlessRuntimeOptions(temp, "fake", Duration.ofSeconds(2)))) {
                session.open();
                assertThat(session.run("hello").finalText()).contains("done");
            }
        }
    }

    private static final class RecordingExporter implements TelemetryExporter {
        private final List<TelemetrySignal> signals = new ArrayList<>();
        private final AtomicBoolean flushed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void export(TelemetrySignal signal) { signals.add(signal); }
        @Override public boolean flush(Duration timeout) { flushed.set(true); return true; }
        @Override public void close() { closed.set(true); }
    }
}
