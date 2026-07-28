package io.github.liumaishenjian.ccjava.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 创建并拥有一次 Run 的可触发取消信号。
 *
 * <p>{@link #cancel()} 幂等且线程安全。取消回调在锁外执行，单个回调失败不会
 * 阻止其余回调；Token 的事件门控则与取消状态共享同步边界，因此取消返回后
 * 不会再开始新的旧 Run Delta 发布。</p>
 *
 * @since 0.1.0
 */
public final class CancellationSource {

    private final Object monitor = new Object();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final CancellationToken token = new SourceToken();
    private boolean cancelled;

    /**
     * 创建尚未取消的信号源。
     */
    public CancellationSource() {
    }

    /**
     * 返回只能观察和注册取消的 Token。
     *
     * @return 与本 Source 绑定的稳定 Token
     */
    public CancellationToken token() {
        return token;
    }

    /**
     * 原子标记取消并执行当前全部回调。
     *
     * @return 本次调用首次触发取消时为 {@code true}
     */
    public boolean cancel() {
        List<Runnable> callbacksToRun;
        synchronized (monitor) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            callbacksToRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        callbacksToRun.forEach(CancellationSource::runSafely);
        return true;
    }

    private static void runSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // 取消必须继续通知其余订阅；S14 再增加安全诊断出口。
        }
    }

    private final class SourceToken implements CancellationToken {

        @Override
        public boolean isCancellationRequested() {
            synchronized (monitor) {
                return cancelled;
            }
        }

        @Override
        public Registration onCancellation(Runnable callback) {
            Objects.requireNonNull(callback, "callback 不能为空");
            boolean runImmediately;
            synchronized (monitor) {
                runImmediately = cancelled;
                if (!runImmediately) {
                    callbacks.add(callback);
                }
            }
            if (runImmediately) {
                runSafely(callback);
                return Registration.noop();
            }
            return () -> {
                synchronized (monitor) {
                    callbacks.remove(callback);
                }
            };
        }

        @Override
        public boolean runIfActive(Runnable action) {
            Objects.requireNonNull(action, "action 不能为空");
            synchronized (monitor) {
                if (cancelled) {
                    return false;
                }
                action.run();
                return true;
            }
        }
    }
}
