package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticKind;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelFailureReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureStage;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 构造并隔离模型诊断事件的 Core 边界。
 *
 * <p>OFF 在读取时钟或创建事件前立即返回；SAFE 过滤非失败事件；任何 sink 异常均被
 * 吞掉。Recorder 不接收或保存 Prompt、响应、异常、路径或 Provider 数据。</p>
 *
 * @since 0.1.0
 */
public final class ModelDiagnosticRecorder {

    private final ModelDiagnosticMode mode;
    private static final byte[] CORRELATION_KEY = newCorrelationKey();

    private final ModelDiagnosticSink sink;
    private final Clock clock;
    private final LongSupplier nanoTime;

    /**
     * 创建诊断记录器。
     *
     * @param mode 固定记录模式
     * @param sink best-effort 出口
     * @param clock 记录时间来源
     * @param nanoTime 单调耗时来源
     */
    public ModelDiagnosticRecorder(
            ModelDiagnosticMode mode,
            ModelDiagnosticSink sink,
            Clock clock,
            LongSupplier nanoTime) {
        this.mode = Objects.requireNonNull(mode, "mode 不能为空");
        this.sink = Objects.requireNonNull(sink, "sink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime 不能为空");
    }

    /**
     * 创建生产时钟记录器。
     *
     * @param mode 固定记录模式
     * @param sink best-effort 出口
     */
    public ModelDiagnosticRecorder(ModelDiagnosticMode mode, ModelDiagnosticSink sink) {
        this(mode, sink, Clock.systemUTC(), System::nanoTime);
    }

    /**
     * 判断诊断平面是否完全关闭。
     *
     * @return OFF 模式时为 {@code true}
     */
    public boolean isOff() {
        return mode == ModelDiagnosticMode.OFF;
    }

    /**
     * 获取当前单调时钟值，供一次诊断计时开始时保存。
     *
     * @return 当前纳秒值；OFF 或时钟故障时返回 0，绝不影响 Provider 工作
     */
    public long startNanos() {
        if (isOff()) {
            return 0L;
        }
        try {
            return nanoTime.getAsLong();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    /**
     * 以 best-effort 方式记录一个封闭诊断事件。
     *
     * @param kind 事件种类
     * @param request 当前模型请求，仅派生不可逆关联 ID 和回合号
     * @param stage 失败或完成阶段
     * @param reason 归一化原因
     * @param statusClass 脱敏状态类别
     * @param receivedProviderFrame 是否收到过 Provider frame
     * @param emittedUserText 是否已向用户输出模型文本
     * @param startedNanos 本次尝试开始时的单调时钟值
     */
    public void record(
            ModelDiagnosticKind kind,
            ModelRequest request,
            ModelFailureStage stage,
            ModelFailureReason reason,
            ModelDiagnosticStatusClass statusClass,
            boolean receivedProviderFrame,
            boolean emittedUserText,
            long startedNanos) {
        Objects.requireNonNull(kind, "kind 不能为空");
        if (mode == ModelDiagnosticMode.OFF
                || (mode == ModelDiagnosticMode.SAFE && kind != ModelDiagnosticKind.FAILURE)) {
            return;
        }
        try {
            long elapsed = Math.max(0L, (nanoTime.getAsLong() - startedNanos) / 1_000_000L);
            sink.record(new ModelDiagnosticEvent(
                    ModelDiagnosticEvent.CURRENT_SCHEMA_VERSION,
                    kind,
                    correlate("session", request.sessionId().value()),
                    correlate("run", request.runId().value()),
                    request.turnNumber(),
                    ModelDiagnosticAttempt.current(),
                    stage,
                    reason,
                    statusClass,
                    receivedProviderFrame,
                    emittedUserText,
                    elapsed,
                    clock.instant()));
        } catch (RuntimeException ignored) {
            // 诊断是可丢失旁路，sink 或时钟故障不能改变模型回合。
        }
    }

    private static UUID correlate(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CORRELATION_KEY, "HmacSHA256"));
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", impossible);
        }
    }

    private static byte[] newCorrelationKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * 返回默认关闭且不写出记录的共享实例。
     *
     * @return OFF 模式记录器
     */
    public static ModelDiagnosticRecorder off() {
        return OffHolder.INSTANCE;
    }

    private static final class OffHolder {
        private static final ModelDiagnosticRecorder INSTANCE = new ModelDiagnosticRecorder(
                ModelDiagnosticMode.OFF,
                ModelDiagnosticSink.noop(),
                Clock.systemUTC(),
                System::nanoTime);
    }
}
