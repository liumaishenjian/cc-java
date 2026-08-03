package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;

import java.util.Objects;

/**
 * 把类型化模型失败摘要格式化为不含 Provider 原文的固定中文诊断。
 *
 * <p>该类型只消费 Domain 枚举和有界计数，不接收任意错误文本，因此不会把 API Key、
 * Endpoint、Prompt 或响应正文带入 stderr。</p>
 *
 * @since 0.1.0
 */
final class ModelFailureFormatter {

    private ModelFailureFormatter() {
    }

    /**
     * 格式化用户可操作的安全说明。
     *
     * @param summary 类型化失败摘要
     * @return 固定中文说明
     */
    static String format(ModelFailureSummary summary) {
        Objects.requireNonNull(summary, "summary 不能为空");
        String base = switch (summary.category()) {
            case PROVIDER_UNAVAILABLE -> "模型服务暂时不可用";
            case RATE_LIMITED -> "模型服务请求过于频繁";
            case REQUEST_TIMEOUT -> "模型请求超时";
            case REQUEST_CONFLICT -> "模型服务暂时无法处理该请求";
            case AUTHENTICATION_FAILED -> "模型服务鉴权失败";
            case INVALID_REQUEST -> "模型服务拒绝了请求";
            case NETWORK_ERROR -> "无法连接模型服务";
            case INCOMPLETE_STREAM -> "模型输出流未完整结束";
            case INVALID_RESPONSE -> "模型服务返回了无效响应";
            case PROVIDER_ERROR -> "模型服务调用失败";
        };
        String status = summary.statusClass()
                .map(ModelFailureFormatter::statusText)
                .map(value -> "（" + value + "）")
                .orElse("");
        String attempts = summary.attempts() > 1
                ? "，已尝试 " + summary.attempts() + " 次"
                : "";
        String action = switch (summary.category()) {
            case PROVIDER_UNAVAILABLE, RATE_LIMITED, REQUEST_TIMEOUT,
                    REQUEST_CONFLICT, NETWORK_ERROR, INCOMPLETE_STREAM -> "；请稍后重试";
            case AUTHENTICATION_FAILED -> "；请检查 Provider 凭证或权限";
            case INVALID_REQUEST -> "；请检查模型与请求配置";
            case INVALID_RESPONSE, PROVIDER_ERROR -> "；请检查 Provider 状态";
        };
        return base + status + attempts + action;
    }

    private static String statusText(ModelHttpStatusClass value) {
        return value == ModelHttpStatusClass.CLIENT_ERROR ? "4xx" : "5xx";
    }
}
