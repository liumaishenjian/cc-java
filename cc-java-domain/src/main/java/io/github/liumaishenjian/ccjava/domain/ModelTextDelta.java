package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 模型在一个尚未聚合完成的回合中产生的 Assistant 文本增量。
 *
 * <p>Delta 只用于实时观察，不能触发 Tool 执行，也不能代替最终聚合的
 * {@link ModelTurn} 写入规范消息历史。空字符串没有可观察意义，因此被拒绝；
 * 仅包含空格或换行的增量仍然是有效终端输出。</p>
 *
 * @param turnNumber 从 1 开始的模型回合序号
 * @param text       本次按顺序追加的非空文本
 * @since 0.1.0
 */
public record ModelTextDelta(int turnNumber, String text) implements AgentEvent {

    /**
     * 校验回合序号和增量正文。
     *
     * @param turnNumber 从 1 开始的模型回合序号
     * @param text 本次文本增量
     * @throws NullPointerException 文本为空时
     * @throws IllegalArgumentException 回合序号小于 1 或文本为空字符串时
     */
    public ModelTextDelta {
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber 必须从 1 开始");
        }
        text = Objects.requireNonNull(text, "text 不能为空");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text 不能为空字符串");
        }
    }
}
