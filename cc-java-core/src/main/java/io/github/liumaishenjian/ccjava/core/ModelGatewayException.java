package io.github.liumaishenjian.ccjava.core;

/**
 * 表示模型 Provider 或 Adapter 在一个回合内调用失败。
 *
 * <p>S01 不重试该异常，而是以 {@code MODEL_ERROR} 安全终止。后续 Stage
 * 会按可重试类型、总预算和 Provider 能力增加有界策略。</p>
 *
 * @since 0.1.0
 */
public final class ModelGatewayException extends Exception {

    /**
     * 使用错误说明创建异常。
     *
     * @param message 不包含密钥的诊断说明
     */
    public ModelGatewayException(String message) {
        super(message);
    }

    /**
     * 使用错误说明和底层原因创建异常。
     *
     * @param message 不包含密钥的诊断说明
     * @param cause   Adapter 底层异常
     */
    public ModelGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
