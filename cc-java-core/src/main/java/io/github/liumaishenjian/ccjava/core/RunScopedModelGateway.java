package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;

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

    /**
     * 在当前线程对应的 Run 开始前冻结 route，并把 Provider request timeout 收窄到 Run 预算。
     *
     * <p>兼容实现可忽略该提示并走旧入口；会在 Run 边界创建 HTTP client 的 Provider 实现必须覆盖，
     * 使底层单请求上限不超过 Run 总预算。</p>
     *
     * @param maxDuration 当前 Run 的正墙钟预算
     * @return 当前 Run 持有的 route 与 credential lease
     */
    default RunScope openRun(Duration maxDuration) {
        java.util.Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        return openRun();
    }

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
