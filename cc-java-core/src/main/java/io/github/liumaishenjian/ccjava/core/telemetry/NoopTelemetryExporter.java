package io.github.liumaishenjian.ccjava.core.telemetry;

import java.time.Duration;

/** 默认不导出任何数据的安全出口。 */
final class NoopTelemetryExporter implements TelemetryExporter {
    static final NoopTelemetryExporter INSTANCE = new NoopTelemetryExporter();
    private NoopTelemetryExporter() {}
    @Override public void export(TelemetrySignal signal) {}
    @Override public boolean flush(Duration timeout) { return true; }
    @Override public void close() {}
}
