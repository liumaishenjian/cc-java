package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;

/**
 * 表示模型 Provider 或 Adapter 在一个回合内调用失败。
 *
 * <p>异常只携带经过 Adapter 脱敏的说明和 Provider-neutral 分类。
 * {@code retryable} 只是候选条件；Runtime 还必须确认没有发布可见 Delta、
 * 没有接收部分 Tool Call、未取消、未超时且仍有重试预算。</p>
 *
 * @since 0.1.0
 */
public final class ModelGatewayException extends Exception {

    /** 不依赖 Provider SDK 的稳定错误分类。 */
    private final ModelFailureKind kind;

    /** Adapter 判断当前失败是否具备安全重试前提。 */
    private final boolean retryable;

    /** 失败前是否已经接收任何文本或 Tool Call 响应片段。 */
    private final boolean partialResponse;

    /**
     * 使用错误说明创建异常。
     *
     * @param message 不包含密钥的诊断说明
     */
    public ModelGatewayException(String message) {
        this(ModelFailureKind.UNKNOWN, message, false, false, null);
    }

    /**
     * 使用错误说明和底层原因创建异常。
     *
     * @param message 不包含密钥的诊断说明
     * @param cause   Adapter 底层异常
     */
    public ModelGatewayException(String message, Throwable cause) {
        this(ModelFailureKind.UNKNOWN, message, false, false, cause);
    }

    /**
     * 使用结构化分类创建安全的模型失败。
     *
     * @param kind Provider-neutral 分类
     * @param message 不包含 Secret、Header 或完整响应的说明
     * @param retryable Provider 是否声明可安全重试
     * @param partialResponse 失败前是否已经接收任意文本或 Tool Call 内容
     */
    public ModelGatewayException(
            ModelFailureKind kind,
            String message,
            boolean retryable,
            boolean partialResponse) {
        this(kind, message, retryable, partialResponse, null);
    }

    /**
     * 使用结构化分类和底层原因创建安全的模型失败。
     *
     * <p>底层原因仅供 Adapter 边界内调试，普通事件和用户输出不得直接记录它。</p>
     *
     * @param kind Provider-neutral 分类
     * @param message 脱敏说明
     * @param retryable Provider 是否声明可安全重试
     * @param partialResponse 失败前是否已经接收部分响应
     * @param cause Adapter 底层异常
     */
    public ModelGatewayException(
            ModelFailureKind kind,
            String message,
            boolean retryable,
            boolean partialResponse,
            Throwable cause) {
        super(requireMessage(message), cause);
        this.kind = Objects.requireNonNull(kind, "kind 不能为空");
        this.retryable = retryable;
        this.partialResponse = partialResponse;
    }

    /**
     * 创建用户取消失败。
     *
     * @param message 脱敏说明
     * @return 不可重试的取消异常
     */
    public static ModelGatewayException cancelled(String message) {
        return new ModelGatewayException(
                ModelFailureKind.CANCELLED,
                message,
                false,
                false);
    }

    /**
     * 创建截止时间失败。
     *
     * @param message 脱敏说明
     * @return 不可重试的截止时间异常
     */
    public static ModelGatewayException deadlineExceeded(String message) {
        return new ModelGatewayException(
                ModelFailureKind.DEADLINE_EXCEEDED,
                message,
                false,
                false);
    }

    /**
     * 返回 Provider-neutral 失败分类。
     *
     * @return 稳定分类
     */
    public ModelFailureKind kind() {
        return kind;
    }

    /**
     * 判断 Provider 是否声明该失败可以重试。
     *
     * @return 候选可重试时为 {@code true}
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 判断失败前是否已经收到任何响应内容。
     *
     * @return 存在部分文本或 Tool Call Chunk 时为 {@code true}
     */
    public boolean partialResponse() {
        return partialResponse;
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message 不能为空");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空白");
        }
        return message;
    }
}
