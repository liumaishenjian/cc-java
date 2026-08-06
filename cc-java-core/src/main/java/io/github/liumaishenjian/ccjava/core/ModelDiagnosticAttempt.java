package io.github.liumaishenjian.ccjava.core;

/**
 * 在同步模型调用栈中传播当前重试序号。
 *
 * <p>Session、Run 与 Turn 已由 ModelRequest 携带；该短生命周期作用域只补齐不属于
 * Provider 请求的 attempt。作用域不跨线程持久化，也不进入 Session、事件或请求正文。</p>
 *
 * @since 0.1.0
 */
public final class ModelDiagnosticAttempt {

    private static final ThreadLocal<Integer> CURRENT = ThreadLocal.withInitial(() -> 1);

    private ModelDiagnosticAttempt() {
    }

    /**
     * 返回当前尝试序号；未显式装饰时为 1。
     *
     * @return 从 1 开始的尝试序号
     */
    public static int current() {
        return CURRENT.get();
    }

    /**
     * 临时绑定尝试序号。
     *
     * @param attempt 从 1 开始的序号
     * @return 必须关闭的恢复作用域
     */
    public static Scope open(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须从 1 开始");
        }
        int previous = CURRENT.get();
        CURRENT.set(attempt);
        return () -> {
            if (previous == 1) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** 恢复先前尝试关联的作用域。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /** 恢复调用前的尝试关联。 */
        @Override
        void close();
    }
}
