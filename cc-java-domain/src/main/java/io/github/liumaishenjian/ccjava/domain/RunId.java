package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 标识由一条用户消息触发的单次 Agent 执行。
 *
 * <p>同一 Session 内的不同 Run 必须具有不同标识，用于关联事件、模型回合和
 * 最终停止原因。</p>
 *
 * @param value 稳定且非空的运行标识
 * @since 0.1.0
 */
public record RunId(String value) {

    /**
     * 校验标识文本后创建 Run ID。
     *
     * @param value 稳定且非空的运行标识
     * @throws NullPointerException 标识为空时
     * @throws IllegalArgumentException 标识为空白时
     */
    public RunId {
        Objects.requireNonNull(value, "value 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value 不能为空白");
        }
    }
}
