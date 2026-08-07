package io.github.liumaishenjian.ccjava.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 用户提交给某次 Run 的自然语言消息及显式文件快照。
 *
 * <p>附件在进入 Runtime 前由 CLI 安全边界解析并形成不可变副本；Runtime 与 Session
 * 只把它们视为规范用户输入，不重新访问文件系统。</p>
 *
 * @param content 非空的用户消息正文
 * @param attachments 稳定顺序的显式文件快照，最多 8 项
 * @since 0.1.0
 */
public record UserMessage(String content, List<UserFileAttachment> attachments) implements AgentMessage {

    /** 校验正文与附件后创建用户消息。 */
    public UserMessage {
        Objects.requireNonNull(content, "content 不能为空");
        attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments 不能为空"));
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白");
        }
        if (attachments.size() > 8) {
            throw new IllegalArgumentException("attachments 最多 8 项");
        }
        int totalBytes = 0;
        for (UserFileAttachment attachment : attachments) {
            Objects.requireNonNull(attachment, "attachment 元素不能为空");
            totalBytes = Math.addExact(totalBytes,
                    attachment.textSnapshot().getBytes(StandardCharsets.UTF_8).length);
        }
        if (totalBytes > 196_608) {
            throw new IllegalArgumentException("attachments 总 UTF-8 字节超过限制");
        }
    }

    /**
     * 创建不带附件的兼容消息。
     *
     * @param content 非空正文
     */
    public UserMessage(String content) {
        this(content, List.of());
    }
}
