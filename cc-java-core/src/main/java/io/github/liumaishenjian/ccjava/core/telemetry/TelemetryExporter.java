package io.github.liumaishenjian.ccjava.core.telemetry;

import java.time.Duration;

/**
 * 非权威观测出口；失败、拥塞或关闭不得改变 Agent Run。
 *
 * @since 0.1.0
 */
public interface TelemetryExporter extends AutoCloseable {
    /**
     * 尝试导出；实现必须有界且不得抛出到 Runtime。
     *
     * @param signal 已脱敏且有界的观测信号
     */
    void export(TelemetrySignal signal);
    /**
     * 在期限内刷新已接受信号。
     *
     * @param timeout 最大刷新等待时间
     * @return 调用前已接受信号均完成时为 {@code true}
     */
    boolean flush(Duration timeout);
    /** 关闭出口并拒绝迟到信号。 */
    @Override void close();
    /**
     * 返回默认无操作出口。
     *
     * @return 不产生外部副作用的共享出口
     */
    static TelemetryExporter noop() {
        return NoopTelemetryExporter.INSTANCE;
    }
}
