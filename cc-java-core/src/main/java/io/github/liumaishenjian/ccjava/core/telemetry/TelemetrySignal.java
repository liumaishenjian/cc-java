package io.github.liumaishenjian.ccjava.core.telemetry;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 可导出的类型化白名单观测信号。
 *
 * <p>每个属性除了 key 白名单外还有封闭值域。Provider/model 只接受不可逆低基数 bucket，
 * 不能携带原始标识、路径、Prompt、Tool 参数/结果、异常文本或 Secret sentinel。</p>
 *
 * @param kind 信号种类
 * @param duration 可选耗时；未知时为空，禁止用零伪装已测 latency
 * @param attributes 封闭白名单属性
 * @since 0.1.0
 */
public record TelemetrySignal(
        TelemetrySignalKind kind,
        java.util.Optional<Duration> duration,
        Map<String, String> attributes) {

    private static final Map<String, Set<String>> ENUM_VALUES = Map.ofEntries(
            Map.entry("status", Set.of("started", "completed", "stopped", "failed", "cancelled", "closed")),
            Map.entry("stop_reason", Set.of(
                    "completed", "cancelled", "limit", "model_error", "tool_error", "internal_error")),
            Map.entry("finish_reason", Set.of("stop", "tool_calls", "length", "content_filter", "unknown")),
            Map.entry("recovery", Set.of("none", "retry", "fallback", "checkpoint", "compaction")),
            Map.entry("usage_known", Set.of("true", "false")),
            Map.entry("cost_known", Set.of("true", "false")),
            Map.entry("retry_kind", Set.of("rate_limit", "server", "transport", "timeout", "unknown")),
            Map.entry("tool_effect", Set.of("read", "write", "command", "external", "unknown")),
            Map.entry("event_type", Set.of(
                    "run_started", "run_finished", "model_turn_started", "model_turn_completed",
                    "tool_started", "tool_completed", "assistant_delta", "assistant_final", "observed")));
    private static final Set<String> BUCKET_KEYS = Set.of("provider", "model");

    /** 校验信号类型、可选耗时与所有低基数白名单属性。 */
    public TelemetrySignal {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        duration = Objects.requireNonNull(duration, "duration 不能为空");
        duration.ifPresent(value -> {
            if (value.isNegative()) throw new IllegalArgumentException("duration 不能为负数");
        });
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes 不能为空"));
        if (attributes.size() > 12) {
            throw new IllegalArgumentException("telemetry 属性超出白名单");
        }
        attributes.forEach(TelemetrySignal::validateAttribute);
    }

    /**
     * 将任意 Provider/model 标识投影为不可逆的固定 64 桶值。
     *
     * @param value 原始标识；不会被保存
     * @return {@code bucket-00..63}
     */
    public static String lowCardinalityBucket(String value) {
        Objects.requireNonNull(value, "value 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value 不能为空白");
        }
        int bucket = Math.floorMod(value.hashCode(), 64);
        return "bucket-" + String.format(java.util.Locale.ROOT, "%02d", bucket);
    }

    private static void validateAttribute(String key, String value) {
        Objects.requireNonNull(key, "telemetry key 不能为空");
        Objects.requireNonNull(value, "telemetry value 不能为空");
        Set<String> allowed = ENUM_VALUES.get(key);
        if (allowed != null) {
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException("telemetry 枚举值非法");
            }
            return;
        }
        if (BUCKET_KEYS.contains(key) && value.matches("bucket-(0[0-9]|[1-5][0-9]|6[0-3])")) {
            return;
        }
        throw new IllegalArgumentException("telemetry 属性超出白名单");
    }
}
