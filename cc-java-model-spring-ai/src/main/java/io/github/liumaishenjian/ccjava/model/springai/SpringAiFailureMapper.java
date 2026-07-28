package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.core.ModelFailureKind;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 把 Spring/WebClient 异常归一化为可安全展示的 Provider-neutral 分类。
 *
 * <p>映射只输出稳定分类，不复制响应正文、Header、URL 查询参数或底层异常
 * message，避免把 API Key 和完整 Provider 内容带入事件/终端。</p>
 *
 * @since 0.1.0
 */
final class SpringAiFailureMapper {

    private SpringAiFailureMapper() {
    }

    /**
     * 转换 Spring AI 调用错误。
     *
     * @param failure Adapter 捕获的底层异常
     * @param partialResponse 失败前是否已经接收响应内容
     * @return 脱敏后的模型异常
     */
    static ModelGatewayException map(Throwable failure, boolean partialResponse) {
        Objects.requireNonNull(failure, "failure 不能为空");
        Throwable root = unwrap(failure);
        if (root instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 401 || status == 403) {
                return failure(
                        ModelFailureKind.AUTHENTICATION_FAILED,
                        "Provider 认证失败",
                        false,
                        partialResponse,
                        failure);
            }
            if (status == 429) {
                return failure(
                        ModelFailureKind.RATE_LIMITED,
                        "Provider 请求受到限流",
                        true,
                        partialResponse,
                        failure);
            }
            if (status == 408 || status == 425 || status == 502
                    || status == 503 || status == 504) {
                return failure(
                        ModelFailureKind.TEMPORARILY_UNAVAILABLE,
                        "Provider 暂时不可用",
                        true,
                        partialResponse,
                        failure);
            }
            if (status >= 400 && status < 500) {
                return failure(
                        ModelFailureKind.INVALID_REQUEST,
                        "Provider 拒绝了模型请求",
                        false,
                        partialResponse,
                        failure);
            }
            if (status >= 500) {
                return failure(
                        ModelFailureKind.TEMPORARILY_UNAVAILABLE,
                        "Provider 服务暂时失败",
                        true,
                        partialResponse,
                        failure);
            }
        }
        if (root instanceof TimeoutException || root instanceof SocketTimeoutException) {
            return failure(
                    ModelFailureKind.DEADLINE_EXCEEDED,
                    "Provider 请求超过截止时间",
                    false,
                    partialResponse,
                    failure);
        }
        if (root instanceof ConnectException || root instanceof WebClientRequestException) {
            return failure(
                    ModelFailureKind.TEMPORARILY_UNAVAILABLE,
                    "无法连接 Provider",
                    true,
                    partialResponse,
                    failure);
        }
        return failure(
                ModelFailureKind.UNKNOWN,
                "Provider 调用失败",
                false,
                partialResponse,
                failure);
    }

    private static ModelGatewayException failure(
            ModelFailureKind kind,
            String message,
            boolean retryable,
            boolean partial,
            Throwable cause) {
        return new ModelGatewayException(kind, message, retryable, partial, cause);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
