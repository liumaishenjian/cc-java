package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;

/**
 * 把用户取消信号从 CLI/应用层传播到模型 Adapter 的只读契约。
 *
 * <p>Token 不拥有线程，也不直接中断进程。Adapter 应通过
 * {@link #onCancellation(Runnable)} 释放模型订阅；Runtime 使用
 * {@link #runIfActive(Runnable)} 防止取消完成后继续发布旧 Run 的事件。</p>
 *
 * @since 0.1.0
 */
public interface CancellationToken {

    /**
     * 判断取消是否已经生效。
     *
     * @return 已取消时返回 {@code true}
     */
    boolean isCancellationRequested();

    /**
     * 注册取消时执行一次的回调。
     *
     * <p>Token 已取消时回调在本方法返回前执行。回调应保持幂等且不得包含
     * 敏感日志。</p>
     *
     * @param callback 用于释放订阅或唤醒等待者的回调
     * @return 可撤销尚未执行回调的注册句柄
     */
    default Registration onCancellation(Runnable callback) {
        Objects.requireNonNull(callback, "callback 不能为空");
        if (isCancellationRequested()) {
            callback.run();
        }
        return Registration.noop();
    }

    /**
     * 仅在尚未取消时执行一个短小动作。
     *
     * <p>默认实现只提供尽力而为的检查；{@link CancellationSource} 生成的
     * Token 会把检查和动作置于同一个同步边界，保证 {@code cancel()} 返回后
     * 不会再开始新的事件发布。</p>
     *
     * @param action 需要取消门控的动作
     * @return 动作已经执行时为 {@code true}
     */
    default boolean runIfActive(Runnable action) {
        Objects.requireNonNull(action, "action 不能为空");
        if (isCancellationRequested()) {
            return false;
        }
        action.run();
        return true;
    }

    /**
     * 返回永不取消的 Token。
     *
     * @return 共享的无取消 Token
     */
    static CancellationToken none() {
        return NeverCancelledHolder.INSTANCE;
    }

    /**
     * 可关闭的取消回调注册。
     *
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Registration extends AutoCloseable {

        /**
         * 撤销尚未执行的回调；回调已经开始或完成时无副作用。
         */
        @Override
        void close();

        /**
         * 返回无需清理的注册句柄。
         *
         * @return 无副作用句柄
         */
        static Registration noop() {
            return () -> {
            };
        }
    }

    /**
     * 延迟持有共享实例，避免接口字段暴露为可变扩展点。
     */
    final class NeverCancelledHolder {

        private static final CancellationToken INSTANCE = new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }
        };

        private NeverCancelledHolder() {
        }
    }
}
