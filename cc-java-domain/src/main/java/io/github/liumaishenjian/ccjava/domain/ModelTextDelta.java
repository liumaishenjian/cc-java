package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 表示模型流中已经按 Provider 顺序到达的一段 Assistant 文本。
 *
 * <p>Delta 只是观察事件，规范消息历史仍只在完整 Model Turn 聚合后追加一次。</p>
 *
 * @param turnNumber 当前 Run 中从 1 开始的模型回合
 * @param text 非空文本增量
 * @since 0.1.0
 */
public record ModelTextDelta(int turnNumber, String text) implements AgentEvent {

    /**
     * 校验回合序号和文本。
     */
    public ModelTextDelta {
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber 必须从 1 开始");
        }
        text = Objects.requireNonNull(text, "text 不能为空");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text 不能为空");
        }
    }
}
