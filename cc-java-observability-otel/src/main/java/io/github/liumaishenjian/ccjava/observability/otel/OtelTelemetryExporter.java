package io.github.liumaishenjian.ccjava.observability.otel;

import io.github.liumaishenjian.ccjava.core.telemetry.TelemetryExporter;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignal;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用直接 OpenTelemetry SDK 的有界、故障隔离 Adapter。
 *
 * <p>队列满时丢弃观测信号而不阻塞 Run；worker/exporter 失败只关闭观察面。flush 使用条件等待，
 * 不 busy-spin；graceful close 先停止接收再尽力排空 accepted signal，超时才 forced shutdown。
 * Core 已把属性收窄为封闭值域，本类不接受正文、路径或任意异常文本。</p>
 *
 * @since 0.1.0
 */
public final class OtelTelemetryExporter implements TelemetryExporter {
    private final OpenTelemetrySdk sdk;
    private final ArrayBlockingQueue<TelemetrySignal> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final Object acceptanceFence = new Object();
    private final Object progress = new Object();
    private final Thread worker;
    private final LongCounter count;
    private final DoubleHistogram duration;
    private final Tracer tracer;

    /**
     * 创建有界 OTel 出口与单 worker 队列。
     *
     * @param sdk 已由 Composition Root 配置的 OpenTelemetry SDK
     * @param queueCapacity 接收队列容量
     */
    public OtelTelemetryExporter(OpenTelemetrySdk sdk, int queueCapacity) {
        this.sdk = Objects.requireNonNull(sdk, "sdk 不能为空");
        if (queueCapacity < 1 || queueCapacity > 65_536) {
            throw new IllegalArgumentException("queueCapacity 非法");
        }
        queue = new ArrayBlockingQueue<>(queueCapacity);
        Meter meter = sdk.getMeter("cc-java");
        count = meter.counterBuilder("cc_java_events_total").build();
        duration = meter.histogramBuilder("cc_java_duration_ms").setUnit("ms").build();
        tracer = sdk.getTracer("cc-java");
        worker = Thread.ofVirtual().name("cc-java-otel-export").start(this::drain);
    }

    /** 非阻塞接收信号；关闭、故障或队列满只增加 drop counter。 */
    @Override
    public void export(TelemetrySignal signal) {
        Objects.requireNonNull(signal, "signal 不能为空");
        synchronized (acceptanceFence) {
            if (!accepting.get() || failed.get() || !queue.offer(signal)) {
                dropped.incrementAndGet();
                return;
            }
            accepted.incrementAndGet();
        }
    }

    /** 等待调用前已接受的信号完成并 flush SDK；不等待随后新增的信号。 */
    @Override
    public boolean flush(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long target = accepted.get();
        long deadline = deadline(timeout);
        synchronized (progress) {
            while (completed.get() < target && !failed.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(progress, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        if (failed.get()) {
            return false;
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            sdk.getSdkTracerProvider().forceFlush().join(remaining, TimeUnit.NANOSECONDS);
            remaining = Math.max(0, deadline - System.nanoTime());
            sdk.getSdkMeterProvider().forceFlush().join(remaining, TimeUnit.NANOSECONDS);
            return completed.get() >= target;
        } catch (RuntimeException exporterFailure) {
            disableObservation();
            return false;
        }
    }

    /**
     * 返回因容量、关闭或 exporter 故障被丢弃的信号数。
     *
     * @return 累计丢弃信号数
     */
    public long droppedSignals() {
        return dropped.get();
    }

    /**
     * 返回成功进入出口队列的累计信号数。
     *
     * @return 累计接受信号数
     */
    public long acceptedSignals() {
        return accepted.get();
    }

    /** 返回已经由 worker 处理或故障清理收敛的信号数，供关闭一致性诊断。 */
    long completedSignals() {
        return completed.get();
    }

    /**
     * 幂等 graceful close；一秒内未排空时中断 worker，accepted-but-unexported 计为 drop。
     */
    @Override
    public void close() {
        synchronized (acceptanceFence) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            accepting.set(false);
        }
        boolean graceful = flush(Duration.ofSeconds(1));
        if (!graceful) {
            dropped.addAndGet(queue.size());
            queue.clear();
        }
        worker.interrupt();
        try {
            worker.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            sdk.close();
        } catch (RuntimeException ignored) {
            // exporter 故障不能改变调用方 Run。
        }
    }

    private void drain() {
        try {
            while (accepting.get() || !queue.isEmpty()) {
                TelemetrySignal signal = queue.poll(100, TimeUnit.MILLISECONDS);
                if (signal == null) {
                    continue;
                }
                try {
                    record(signal);
                } catch (RuntimeException exporterFailure) {
                    disableObservation();
                    return;
                } finally {
                    completed.incrementAndGet();
                    synchronized (progress) {
                        progress.notifyAll();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (progress) {
                progress.notifyAll();
            }
        }
    }

    private void disableObservation() {
        if (failed.compareAndSet(false, true)) {
            accepting.set(false);
            int discarded = queue.size();
            queue.clear();
            dropped.addAndGet(discarded);
            completed.addAndGet(discarded);
            synchronized (progress) {
                progress.notifyAll();
            }
        }
    }

    private void record(TelemetrySignal signal) {
        var attributes = io.opentelemetry.api.common.Attributes.builder()
                .put("signal.kind", signal.kind().name().toLowerCase(java.util.Locale.ROOT));
        signal.attributes().forEach((key, value) -> attributes.put(AttributeKey.stringKey(key), value));
        var built = attributes.build();
        count.add(1, built);
        signal.duration().ifPresent(value ->
                duration.record(value.toNanos() / 1_000_000.0, built));
        Span span = tracer.spanBuilder(
                "cc-java." + signal.kind().name().toLowerCase(java.util.Locale.ROOT)).startSpan();
        try {
            signal.attributes().forEach(span::setAttribute);
        } finally {
            span.end();
        }
    }

    private static long deadline(Duration timeout) {
        try {
            return Math.addExact(System.nanoTime(), timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
