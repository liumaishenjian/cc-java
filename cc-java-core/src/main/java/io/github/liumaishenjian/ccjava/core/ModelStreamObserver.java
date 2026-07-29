package io.github.liumaishenjian.ccjava.core;

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
     * 返回忽略全部增量的 Observer。
     *
     * @return no-op Observer
     */
    static ModelStreamObserver noop() {
        return ignored -> {
        };
    }
}
