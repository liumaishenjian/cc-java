package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 用户提交给某次 Run 的自然语言消息。
 *
 * @param content 非空的用户消息正文
 * @since 0.1.0
 */
public record UserMessage(String content) implements AgentMessage {

    /**
     * 校验正文后创建用户消息。
     *
     * @param content 非空的用户消息正文
     * @throws NullPointerException 正文为空时
     * @throws IllegalArgumentException 正文为空白时
     */
    public UserMessage {
        Objects.requireNonNull(content, "content 不能为空");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白");
        }
    }
}
