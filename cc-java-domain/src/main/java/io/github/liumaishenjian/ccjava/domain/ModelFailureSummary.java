package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 可进入 Run 终态和用户 Surface 的脱敏模型失败摘要。
 *
 * <p>该协议只允许固定枚举、受限计数和布尔值，从类型上排除错误正文、URL、Prompt、
 * Header、Request ID 与凭证。{@code attempts} 表示本次模型回合实际发起的尝试数，
 * 而不是整个 Run 的模型回合数。</p>
 *
 * @param category Provider-neutral 失败类别
 * @param statusClass 可选 HTTP 粗粒度状态组
 * @param attempts 本次模型回合实际尝试次数
 * @param receivedOutput 失败前是否收到过 Provider 响应或发布过可见输出
 * @since 0.1.0
 */
public record ModelFailureSummary(
        ModelFailureCategory category,
        Optional<ModelHttpStatusClass> statusClass,
        int attempts,
        boolean receivedOutput) {

    /** 防止异常输入把任意大计数带入协议。 */
    public static final int MAX_ATTEMPTS = 100;

    /**
     * 校验脱敏摘要的不变量。
     */
    public ModelFailureSummary {
        category = Objects.requireNonNull(category, "category 不能为空");
        statusClass = Objects.requireNonNull(statusClass, "statusClass 不能为空");
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempts 必须在 1 到 " + MAX_ATTEMPTS + " 之间");
        }
        if (requiresClientStatus(category)
                && statusClass.orElse(null) != ModelHttpStatusClass.CLIENT_ERROR) {
            throw new IllegalArgumentException("该失败类别必须携带 4xx 状态组");
        }
        if (category == ModelFailureCategory.PROVIDER_UNAVAILABLE
                && statusClass.orElse(null) != ModelHttpStatusClass.SERVER_ERROR) {
            throw new IllegalArgumentException("Provider 不可用必须携带 5xx 状态组");
        }
        if ((category == ModelFailureCategory.NETWORK_ERROR
                || category == ModelFailureCategory.INCOMPLETE_STREAM
                || category == ModelFailureCategory.INVALID_RESPONSE
                || category == ModelFailureCategory.PROVIDER_ERROR
                || category == ModelFailureCategory.CONFIGURATION_REQUIRED)
                && statusClass.isPresent()) {
            throw new IllegalArgumentException("非 HTTP 分类不能携带状态组");
        }
        if (category == ModelFailureCategory.INCOMPLETE_STREAM && !receivedOutput) {
            throw new IllegalArgumentException("不完整流必须标记已经收到输出");
        }
    }

    /**
     * 创建单次尝试摘要。
     *
     * @param category 失败类别
     * @param statusClass 可选状态组
     * @param receivedOutput 是否已经收到输出
     * @return attempts 为 1 的摘要
     */
    public static ModelFailureSummary firstAttempt(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass,
            boolean receivedOutput) {
        return new ModelFailureSummary(category, statusClass, 1, receivedOutput);
    }

    /**
     * 复制摘要并替换实际尝试次数。
     *
     * @param actualAttempts 实际尝试数
     * @return 新摘要
     */
    public ModelFailureSummary withAttempts(int actualAttempts) {
        return new ModelFailureSummary(
                category,
                statusClass,
                actualAttempts,
                receivedOutput);
    }

    /**
     * 复制摘要并标记已收到可见输出。
     *
     * @return 新摘要
     */
    public ModelFailureSummary withReceivedOutput() {
        return new ModelFailureSummary(
                category,
                statusClass,
                attempts,
                true);
    }

    private static boolean requiresClientStatus(ModelFailureCategory value) {
        return value == ModelFailureCategory.RATE_LIMITED
                || value == ModelFailureCategory.REQUEST_CONFLICT
                || value == ModelFailureCategory.AUTHENTICATION_FAILED
                || value == ModelFailureCategory.INVALID_REQUEST;
    }
}
