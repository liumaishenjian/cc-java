package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.telemetry.TelemetryExporter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless production telemetry exporter 工厂。
 *
 * <p>默认始终 No-op。嵌入方或 CLI composition 可以在创建 Session 前安装一个本进程工厂；
 * 每个 Session 获得独立 exporter 生命周期，禁止共享已关闭实例。工厂失败会安全降级 No-op。</p>
 *
 * @since 0.1.0
 */
public final class TelemetryExporters {
    private static final AtomicReference<Factory> FACTORY =
            new AtomicReference<>(TelemetryExporter::noop);

    private TelemetryExporters() {
    }

    /**
     * 安装生产 exporter 工厂；返回句柄用于恢复默认，适合 SDK/测试的结构化生命周期。
     *
     * @param factory 每个 Session 创建独占 exporter 的工厂
     * @return 关闭后恢复前一个工厂的 lease
     */
    public static AutoCloseable install(Factory factory) {
        Factory checked = Objects.requireNonNull(factory, "factory 不能为空");
        Factory previous = FACTORY.getAndSet(checked);
        return () -> FACTORY.compareAndSet(checked, previous);
    }

    static TelemetryExporter production() {
        try {
            TelemetryExporter exporter = FACTORY.get().create();
            return exporter == null ? TelemetryExporter.noop() : exporter;
        } catch (RuntimeException failure) {
            return TelemetryExporter.noop();
        }
    }

    /** 创建一个 Session-owned exporter。 */
    @FunctionalInterface
    public interface Factory {
        /**
         * 创建一个由单个 Session 独占的 exporter。
         *
         * @return 新 exporter；返回空会安全降级为 No-op
         */
        TelemetryExporter create();
    }
}
