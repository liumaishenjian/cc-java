package io.github.liumaishenjian.ccjava.core;

/**
 * 在 Agent Run 边界冻结底层模型 route 的 Gateway。
 *
 * <p>Headless Runtime 在任何模型调用前打开一次 scope，并在唯一 run 终态后关闭；实现可借此绑定
 * credential generation 与取消回调。该接口不允许在模型回合内部改选 Provider。</p>
 */
public interface RunScopedModelGateway extends ModelGateway {
    /**
     * 在当前线程对应的 Run 开始前冻结 route。
     *
     * @return 当前 Run 持有的 route 与 credential lease
     */
    RunScope openRun();

    /** Run 持有的 route/credential lease。 */
    interface RunScope extends AutoCloseable {
        /**
         * 注册 logout fence 可调用的同进程 Run 取消动作。
         *
         * @param cancellation 需要取消当前 Run 时调用的动作
         */
        void bindCancellation(Runnable cancellation);
        /** 释放 route，并且必须幂等地发布 lease terminal。 */
        @Override void close();
    }
}
