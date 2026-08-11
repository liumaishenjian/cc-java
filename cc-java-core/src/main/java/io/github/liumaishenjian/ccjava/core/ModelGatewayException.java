package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

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
    /** 仅由固定枚举和受限数值组成的可选 Surface 摘要。 */
    private final Optional<ModelFailureSummary> summary;
    /** Provider 明确给出的有界 Retry-After；自由文本/Header 不保留。 */
    private final Optional<Duration> retryAfter;

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
        this(kind, message, Optional.empty(), Optional.empty(), null);
    }

    /**
     * 使用明确失败分类和脱敏摘要创建异常。
     *
     * @param kind Provider-neutral 控制分类
     * @param message 不包含敏感内容的固定诊断
     * @param summary 可进入 Run 终态的类型化摘要
     */
    public ModelGatewayException(
            FailureKind kind,
            String message,
            ModelFailureSummary summary) {
        this(kind, message, Optional.of(Objects.requireNonNull(summary, "summary 不能为空")), Optional.empty(), null);
    }

    /**
     * 使用明确失败分类和底层原因创建异常。
     *
     * @param kind Provider-neutral 失败分类
     * @param message 不包含敏感内容的固定诊断
     * @param cause Adapter 底层异常；不得依赖其消息对外展示
     */
    public ModelGatewayException(FailureKind kind, String message, Throwable cause) {
        this(kind, message, Optional.empty(), Optional.empty(), cause);
    }

    /**
     * 使用控制分类、脱敏摘要和底层原因创建异常。
     *
     * @param kind Provider-neutral 控制分类
     * @param message 不包含敏感内容的固定诊断
     * @param summary 可进入 Run 终态的类型化摘要
     * @param cause Adapter 底层异常；不得依赖其消息对外展示
     */
    public ModelGatewayException(
            FailureKind kind,
            String message,
            ModelFailureSummary summary,
            Throwable cause) {
        this(
                kind,
                message,
                Optional.of(Objects.requireNonNull(summary, "summary 不能为空")),
                Optional.empty(),
                cause);
    }

    /**
     * 创建带 Provider typed Retry-After 的瞬时失败。
     *
     * @param kind 稳定失败分类
     * @param message 供本地诊断使用的有界消息
     * @param summary 可安全投影到 Run 终态的结构化摘要
     * @param retryAfter 非负且不超过五分钟的服务端等待建议
     * @param cause 原始 Adapter 异常
     */
    public ModelGatewayException(
            FailureKind kind,
            String message,
            ModelFailureSummary summary,
            Duration retryAfter,
            Throwable cause) {
        this(kind, message,
                Optional.of(Objects.requireNonNull(summary, "summary 不能为空")),
                Optional.of(Objects.requireNonNull(retryAfter, "retryAfter 不能为空")), cause);
    }

    private ModelGatewayException(
            FailureKind kind,
            String message,
            Optional<ModelFailureSummary> summary,
            Optional<Duration> retryAfter,
            Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind 不能为空");
        this.summary = Objects.requireNonNull(summary, "summary 不能为空");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter 不能为空");
        retryAfter.ifPresent(value -> {
            if (value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("retryAfter 必须在 0 到 5 分钟之间");
            }
        });
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
     * 返回可安全投影到 Run 终态的失败摘要。
     *
     * @return 固定枚举和有界数值组成的可选摘要
     */
    public Optional<ModelFailureSummary> summary() {
        return summary;
    }

    /**
     * 返回 Adapter 已解析的有界 Retry-After；不存在时由 Runtime policy 决定。
     *
     * @return Provider 明确给出的可选等待建议
     */
    public Optional<Duration> retryAfter() {
        return retryAfter;
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
        /** Provider 明确拒绝超过输入 Context 容量的请求。 */
        CONTEXT_OVERFLOW,
        /** 用户取消或 Runtime Deadline 已传播到 Adapter。 */
        CANCELLED
    }
}
