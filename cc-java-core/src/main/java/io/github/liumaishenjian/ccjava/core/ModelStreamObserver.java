package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;

import java.time.Duration;
import java.util.Objects;

/**
 * 观察一个模型回合的 Provider-neutral 文本增量。
 *
 * <p>Observer 失败不得改变模型或 Agent 决策；调用方应隔离终端和遥测异常。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModelStreamObserver {

    /**
     * 按 Provider 顺序消费非空文本增量。
     *
     * @param textDelta 文本增量
     */
    void onTextDelta(String textDelta);

    /**
     * 一个实际 Provider attempt 即将开始。
     *
     * <p>默认实现保持旧 lambda Observer 兼容；事件只含有界计数，不携带 Provider、
     * Prompt、Header 或错误正文。</p>
     *
     * @param attempt 当前从 1 开始的实际请求序号
     * @param maxAttempts 本回合允许的最大请求数
     */
    default void onAttemptStarted(int attempt, int maxAttempts) {
    }

    /**
     * 瞬时失败已被策略接受，下一次 attempt 将在有界等待后开始。
     *
     * @param failedAttempt 刚失败的请求序号
     * @param nextAttempt 下一次请求序号
     * @param maxAttempts 本回合最大请求数
     * @param delay 实际采用的有界等待
     * @param category 隐私安全失败类别
     */
    default void onRetryScheduled(
            int failedAttempt,
            int nextAttempt,
            int maxAttempts,
            Duration delay,
            ModelFailureCategory category) {
        Objects.requireNonNull(delay, "delay 不能为空");
        Objects.requireNonNull(category, "category 不能为空");
    }

    /**
     * 返回忽略全部增量的 Observer。
     *
     * @return no-op Observer
     */
    static ModelStreamObserver noop() {
        return ignored -> {
        };
    }
}
