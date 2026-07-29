package io.github.liumaishenjian.ccjava.core;

/**
 * 把 Runtime 取消请求传播到模型或工具适配器的框架无关端口。
 *
 * <p>注册回调必须幂等且快速；Adapter 只负责停止自身资源，不得发布 Run 终态。</p>
 *
 * @since 0.1.0
 */
public interface CancellationToken {

    /**
     * 判断取消是否已经发生。
     *
     * @return 已请求取消时为 {@code true}
     */
    boolean isCancellationRequested();

    /**
     * 注册一次取消动作。若取消已经发生，动作在返回前执行。
     *
     * @param action 取消时执行的资源释放动作
     * @return 用于解除尚未触发回调的注册
     */
    Registration onCancellation(Runnable action);

    /**
     * 返回永远不会取消的共享 Token。
     *
     * @return no-op Token
     */
    static CancellationToken none() {
        return NoCancellationToken.INSTANCE;
    }

    /**
     * 取消回调注册句柄。
     */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        /** 解除尚未执行的回调；已执行时无效果。 */
        @Override
        void close();
    }

    /**
     * 永不取消的内部实现。
     */
    enum NoCancellationToken implements CancellationToken {
        /** 共享实例。 */
        INSTANCE;

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public Registration onCancellation(Runnable action) {
            java.util.Objects.requireNonNull(action, "action 不能为空");
            return () -> {
            };
        }
    }
}
