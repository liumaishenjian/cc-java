package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime 持有的线程安全取消源。
 *
 * <p>调用 {@link #cancel()} 只改变取消状态并通知已注册 Adapter；它不决定
 * Agent Run 的最终状态，终态仍由 Runtime 状态机产生。</p>
 *
 * @since 0.1.0
 */
public final class CancellationSource {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final CancellationToken token = new SourceToken();

    /**
     * 创建尚未取消且没有监听器的取消源。
     */
    public CancellationSource() {
    }

    /**
     * 返回只读取消 Token。
     *
     * @return 可传给 Adapter 的 Token
     */
    public CancellationToken token() {
        return token;
    }

    /**
     * 首次调用时通知全部回调；后续调用无效果。
     *
     * @return 本次调用是否首次触发取消
     */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
        callbacks.clear();
        return true;
    }

    private final class SourceToken implements CancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public Registration onCancellation(Runnable action) {
            Objects.requireNonNull(action, "action 不能为空");
            if (cancelled.get()) {
                action.run();
                return () -> {
                };
            }
            callbacks.add(action);
            if (cancelled.get() && callbacks.remove(action)) {
                action.run();
            }
            return () -> callbacks.remove(action);
        }
    }
}
