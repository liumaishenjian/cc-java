package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 可安全反馈给模型的结构化 Tool 错误。
 *
 * @param code    稳定错误分类
 * @param message 不包含敏感实现细节的可读说明
 * @param details 便于模型纠正调用的结构化细节
 * @since 0.1.0
 */
public record ToolError(ToolErrorCode code, String message, JsonObject details) {

    /**
     * 校验错误内容后创建结构化 Tool 错误。
     *
     * @param code 稳定错误分类
     * @param message 不包含敏感细节的可读说明
     * @param details 帮助模型纠正调用的结构化细节
     * @throws NullPointerException 分类、说明或细节为空时
     * @throws IllegalArgumentException 说明为空白时
     */
    public ToolError {
        code = Objects.requireNonNull(code, "code 不能为空");
        message = Objects.requireNonNull(message, "message 不能为空");
        details = Objects.requireNonNull(details, "details 不能为空");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空白");
        }
    }

    /**
     * 创建不包含额外细节的错误。
     *
     * @param code    错误分类
     * @param message 可读说明
     * @return 空 details 的错误
     */
    public static ToolError of(ToolErrorCode code, String message) {
        return new ToolError(code, message, JsonObject.empty());
    }
}
