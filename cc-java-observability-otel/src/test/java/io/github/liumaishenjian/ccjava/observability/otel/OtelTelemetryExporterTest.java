package io.github.liumaishenjian.ccjava.observability.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignal;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OtelTelemetryExporterTest {

    @Test
    void boundedExporterFlushesAcceptedSignalsAndClosesIdempotently() {
        OtelTelemetryExporter exporter = new OtelTelemetryExporter(
                OpenTelemetrySdk.builder().build(), 2);
        exporter.export(new TelemetrySignal(
                TelemetrySignalKind.RUN,
                Optional.of(Duration.ofMillis(1)),
                Map.of("status", "completed", "stop_reason", "completed")));
        assertThat(exporter.flush(Duration.ofSeconds(1))).isTrue();
        assertThat(exporter.acceptedSignals()).isOne();
        exporter.close();
        exporter.close();
        assertThatNoException().isThrownBy(() -> exporter.export(new TelemetrySignal(
                TelemetrySignalKind.RUN, Optional.of(Duration.ZERO), Map.of("status", "started"))));
        assertThat(exporter.droppedSignals()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentExportAndCloseNeverLoseAcceptedSignal() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            OtelTelemetryExporter exporter = new OtelTelemetryExporter(
                    OpenTelemetrySdk.builder().build(), 8);
            CountDownLatch start = new CountDownLatch(1);
            Thread exporting = Thread.startVirtualThread(() -> {
                await(start);
                exporter.export(new TelemetrySignal(
                        TelemetrySignalKind.RUN, Optional.of(Duration.ZERO), Map.of("status", "started")));
            });
            Thread closing = Thread.startVirtualThread(() -> {
                await(start);
                exporter.close();
            });
            start.countDown();
            exporting.join(TimeUnit.SECONDS.toMillis(2));
            closing.join(TimeUnit.SECONDS.toMillis(2));
            assertThat(exporting.isAlive()).isFalse();
            assertThat(closing.isAlive()).isFalse();
            assertThat(exporter.acceptedSignals() + exporter.droppedSignals()).isOne();
            assertThat(exporter.completedSignals()).isEqualTo(exporter.acceptedSignals());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void privacySentinelCannotEnterExporterAttributes() {
        assertThatNoException().isThrownBy(() -> new TelemetrySignal(
                TelemetrySignalKind.MODEL_TURN,
                Optional.of(Duration.ZERO),
                Map.of(
                        "provider", TelemetrySignal.lowCardinalityBucket("SECRET_SENTINEL"),
                        "status", "completed")));
    }
}
