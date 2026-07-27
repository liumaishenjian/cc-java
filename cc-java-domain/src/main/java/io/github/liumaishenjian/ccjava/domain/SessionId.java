package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 标识一次连续的 Agent 会话。
 *
 * <p>Session 可以包含多个 Run。该值由外层运行时生成，领域层只保证它非空且
 * 可稳定比较，不假设 UUID 或数据库主键等具体生成策略。</p>
 *
 * @param value 稳定且非空的会话标识
 * @since 0.1.0
 */
public record SessionId(String value) {

    /**
     * 校验标识文本后创建 Session ID。
     *
     * @param value 稳定且非空的会话标识
     * @throws NullPointerException 标识为空时
     * @throws IllegalArgumentException 标识为空白时
     */
    public SessionId {
        Objects.requireNonNull(value, "value 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value 不能为空白");
        }
    }
}
