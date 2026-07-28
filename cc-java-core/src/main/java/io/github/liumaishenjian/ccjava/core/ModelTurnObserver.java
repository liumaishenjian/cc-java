package io.github.liumaishenjian.ccjava.core;

/**
 * 在完整 {@code ModelTurn} 聚合期间观察文本增量的框架无关端口。
 *
 * <p>Observer 只能发布可观察信息，不能执行 Tool、修改 Context 或决定
 * Agent 状态。Adapter 必须按 Provider 顺序串行调用，最终 Tool Call 仍只
 * 能通过聚合后的 Model Turn 返回。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModelTurnObserver {

    /**
     * 发布一个 Assistant 文本增量。
     *
     * @param text 非空增量；空格和换行是有效内容
     */
    void onTextDelta(String text);

    /**
     * 返回忽略全部文本增量的 Observer。
     *
     * @return 无副作用 Observer
     */
    static ModelTurnObserver noop() {
        return ignored -> {
        };
    }
}
