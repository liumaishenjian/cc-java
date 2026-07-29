package io.github.liumaishenjian.ccjava.core;

/**
 * 表示模型 Provider 或 Adapter 在一个回合内调用失败。
 *
 * <p>异常只携带 Provider-neutral 的失败分类和安全诊断。是否重试由
 * {@link RetryingModelGateway} 决定；Adapter 不得把响应体、Prompt、端点或
 * Secret 放入异常消息。</p>
 *
 * @since 0.1.0
 */
public final class ModelGatewayException extends Exception {

    /** 不依赖 Provider 类型的稳定失败分类。 */
    private final FailureKind kind;

    /**
     * 使用错误说明创建异常。
     *
     * @param message 不包含密钥的诊断说明
     */
    public ModelGatewayException(String message) {
        this(FailureKind.PERMANENT, message);
    }

    /**
     * 使用错误说明和底层原因创建异常。
     *
     * @param message 不包含密钥的诊断说明
     * @param cause   Adapter 底层异常
     */
    public ModelGatewayException(String message, Throwable cause) {
        this(FailureKind.PERMANENT, message, cause);
    }

    /**
     * 使用明确失败分类创建异常。
     *
     * @param kind Provider-neutral 失败分类
     * @param message 不包含敏感内容的固定诊断
     */
    public ModelGatewayException(FailureKind kind, String message) {
        super(message);
        this.kind = java.util.Objects.requireNonNull(kind, "kind 不能为空");
    }

    /**
     * 使用明确失败分类和底层原因创建异常。
     *
     * @param kind Provider-neutral 失败分类
     * @param message 不包含敏感内容的固定诊断
     * @param cause Adapter 底层异常；不得依赖其消息对外展示
     */
    public ModelGatewayException(FailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind 不能为空");
    }

    /**
     * 返回稳定失败分类。
     *
     * @return 重试与 Runtime 终态映射使用的分类
     */
    public FailureKind kind() {
        return kind;
    }

    /**
     * 模型回合失败的 Provider-neutral 分类。
     */
    public enum FailureKind {
        /** 确定性请求、配置或响应错误。 */
        PERMANENT,
        /** 尚未产生可见输出的瞬时错误。 */
        RETRYABLE,
        /** 有界重试次数已经耗尽。 */
        RETRY_EXHAUSTED,
        /** 流在完整终态前结束，禁止自动重试。 */
        INCOMPLETE_STREAM,
        /** 用户取消或 Runtime Deadline 已传播到 Adapter。 */
        CANCELLED
    }
}
