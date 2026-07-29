package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.Objects;

/**
 * 单个 Tool Call 的隐私安全耗时投影。
 *
 * <p>只暴露 Run 内序号和边界耗时，不暴露 Tool 名称、参数、结果或错误正文。</p>
 *
 * @param ordinal Run 内从 1 开始的 Tool Call 序号
 * @param elapsed 开始边界到完成边界或 Run 结束边界的耗时
 * @param completed 是否收到规范的 Tool 完成事件
 * @since 0.1.0
 */
public record ToolCallTelemetry(
        int ordinal,
        Duration elapsed,
        boolean completed) {

    /**
     * 校验调用序号和耗时。
     */
    public ToolCallTelemetry {
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal 必须从 1 开始");
        }
        elapsed = Objects.requireNonNull(elapsed, "elapsed 不能为空");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed 不能为负数");
        }
    }
}
