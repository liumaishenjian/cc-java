package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 由 Runtime 编译生成的稳定系统上下文。
 *
 * @param content 非空的系统指令正文
 * @since 0.1.0
 */
public record SystemMessage(String content) implements AgentMessage {

    /**
     * 校验正文后创建系统消息。
     *
     * @param content 非空的系统指令正文
     * @throws NullPointerException 正文为空时
     * @throws IllegalArgumentException 正文为空白时
     */
    public SystemMessage {
        Objects.requireNonNull(content, "content 不能为空");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白");
        }
    }
}
