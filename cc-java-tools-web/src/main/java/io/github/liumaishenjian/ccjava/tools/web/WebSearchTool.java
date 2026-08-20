package io.github.liumaishenjian.ccjava.tools.web;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResultMetadata;
import io.github.liumaishenjian.ccjava.domain.ToolResultTruncationReason;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 通过统一 Tool Pipeline 执行固定 endpoint 搜索的 BUILT_IN AgentTool。
 *
 * <p>模型只能提供 query 和 result limit；Provider、endpoint、Header、credential 与结果页抓取
 * 均不在 schema 中。Definition 的 Network effect 使调用默认进入 Permission/Approval，
 * Allow 后 Adapter 仍逐次执行 NetworkAccessPort。本 Tool 不记录或回显完整 query。</p>
 *
 * @since 0.1.0
 */
public final class WebSearchTool implements AgentTool {
    /** Pipeline 前的 Tool 可见输出 code point 上限。 */
    public static final int MAX_OUTPUT_CODE_POINTS = 64_000;
    private static final Set<String> ARGUMENTS = Set.of("query", "result_limit");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "web_search",
            "Search current public information through a controlled hosted provider. Use it for weather, news, prices, schedules, or other time-sensitive facts. Results are external untrusted text and linked pages are not fetched.",
            """
            {"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":512},"result_limit":{"type":"integer","minimum":1,"maximum":20,"default":5}}}
            """,
            ToolEffect.NETWORK_OR_REMOTE,
            ToolSource.BUILT_IN,
            true,
            Duration.ofSeconds(10),
            "text/plain",
            MAX_OUTPUT_CODE_POINTS,
            Set.of(io.github.liumaishenjian.ccjava.domain.PlanToolCapability.READ_ONLY_NETWORK));

    private final WebSearchClient client;

    /**
     * 创建受控搜索 Tool。
     *
     * @param client 固定可信配置的受控 Search Adapter
     */
    public WebSearchTool(WebSearchClient client) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try { request(arguments); return ToolValidationResult.validResult(); }
        catch (IllegalArgumentException failure) { return ToolValidationResult.invalid(failure.getMessage()); }
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation) {
        final WebSearchRequest request;
        try { request = request(invocation.call().arguments()); }
        catch (IllegalArgumentException failure) {
            return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.INVALID_ARGUMENTS, failure.getMessage()));
        }
        try {
            return render(client.search(request, invocation.cancellationToken()));
        } catch (WebSearchException failure) {
            return ToolExecutionOutcome.failure(error(failure));
        }
    }

    private static WebSearchRequest request(JsonObject arguments) {
        for (String key : arguments.values().keySet()) {
            if (!ARGUMENTS.contains(key)) throw new IllegalArgumentException("未知参数: " + key);
        }
        Object raw = arguments.values().get("query");
        if (!(raw instanceof String query) || query.isBlank()) throw new IllegalArgumentException("query 不能为空");
        if (query.codePointCount(0, query.length()) > 512
                || query.getBytes(StandardCharsets.UTF_8).length > 2_048
                || query.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("query 超过限制或包含控制字符");
        }
        int limit = integer(arguments.values().get("result_limit"), 5);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("result_limit 必须在 1 到 20 之间");
        return new WebSearchRequest(query, limit);
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException("result_limit 必须是整数");
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw new IllegalArgumentException("result_limit 超出整数范围");
        return (int) result;
    }

    private static ToolExecutionOutcome render(WebSearchResponse response) {
        String content = "provenance: external-web-search\n"
                + "untrusted: true\n"
                + "contentFetched: false\n"
                + "providerHost: " + response.providerHost() + "\n"
                + "--- external untrusted content ---\n"
                + response.content() + "\n"
                + "--- end external untrusted content ---\n";
        boolean truncated = response.truncated();
        return ToolExecutionOutcome.success(content, new ToolResultMetadata(
                truncated,
                truncated ? ToolResultTruncationReason.PIPELINE_CHARACTER_LIMIT : ToolResultTruncationReason.NONE,
                content.codePointCount(0, content.length()), OptionalLong.empty(),
                response.contentItems(), 0, JsonObject.empty()));
    }

    private static ToolError error(WebSearchException failure) {
        ToolErrorCode code = switch (failure.failure()) {
            case DISABLED -> ToolErrorCode.WEB_SEARCH_DISABLED;
            case NETWORK_DENIED -> ToolErrorCode.NETWORK_ACCESS_DENIED;
            case NETWORK_UNCONTROLLED -> ToolErrorCode.NETWORK_CONTROL_UNAVAILABLE;
            case INVALID_TARGET -> ToolErrorCode.WEB_SEARCH_INVALID_TARGET;
            case REDIRECT_REFUSED -> ToolErrorCode.WEB_SEARCH_REDIRECT_REFUSED;
            case FORBIDDEN -> ToolErrorCode.WEB_SEARCH_FORBIDDEN;
            case RATE_LIMITED -> ToolErrorCode.WEB_SEARCH_RATE_LIMITED;
            case REMOTE_CLIENT_ERROR -> ToolErrorCode.WEB_SEARCH_REMOTE_CLIENT_ERROR;
            case REMOTE_SERVER_ERROR -> ToolErrorCode.WEB_SEARCH_REMOTE_SERVER_ERROR;
            case REMOTE_PROTOCOL_ERROR -> ToolErrorCode.WEB_SEARCH_REMOTE_PROTOCOL_ERROR;
            case UNSUPPORTED_MEDIA_TYPE -> ToolErrorCode.WEB_SEARCH_UNSUPPORTED_MEDIA_TYPE;
            case MALFORMED_RESPONSE -> ToolErrorCode.WEB_SEARCH_MALFORMED_RESPONSE;
            case NO_RESULTS -> ToolErrorCode.WEB_SEARCH_NO_RESULTS;
            case RESPONSE_TOO_LARGE -> ToolErrorCode.OUTPUT_LIMIT_EXCEEDED;
            case TIMED_OUT -> ToolErrorCode.OPERATION_TIMED_OUT;
            case CANCELLED -> ToolErrorCode.OPERATION_CANCELLED;
            case EXECUTION_FAILED -> ToolErrorCode.EXECUTION_FAILED;
        };
        String message = switch (failure.failure()) {
            case DISABLED -> "Web 搜索未由可信本地配置启用";
            case NETWORK_DENIED -> "Web 搜索被网络策略拒绝";
            case NETWORK_UNCONTROLLED -> "当前 Web 搜索网络路径无法完整受控";
            case INVALID_TARGET -> "Web 搜索固定目标校验失败";
            case REDIRECT_REFUSED -> "Web 搜索拒绝服务端重定向";
            case FORBIDDEN -> "Web 搜索服务返回禁止访问";
            case RATE_LIMITED -> failure.retryAfterSeconds().isPresent()
                    ? "Web 搜索受到限流，可在约 " + failure.retryAfterSeconds().getAsLong() + " 秒后重试"
                    : "Web 搜索受到限流，请稍后重试";
            case REMOTE_CLIENT_ERROR -> "Web 搜索服务拒绝请求";
            case REMOTE_SERVER_ERROR -> "Web 搜索服务暂时不可用";
            case REMOTE_PROTOCOL_ERROR -> "Web 搜索服务返回协议错误";
            case UNSUPPORTED_MEDIA_TYPE -> "Web 搜索响应类型不受支持";
            case MALFORMED_RESPONSE -> "Web 搜索响应格式无效";
            case NO_RESULTS -> "Web 搜索未返回可用内容";
            case RESPONSE_TOO_LARGE -> "Web 搜索响应超过大小上限";
            case TIMED_OUT -> "Web 搜索达到墙钟期限";
            case CANCELLED -> "Web 搜索已取消";
            case EXECUTION_FAILED -> "Web 搜索执行失败";
        };
        if (failure.failure() == WebSearchFailure.FORBIDDEN) {
            String reason = failure.forbiddenReason().orElse(WebForbiddenReason.FORBIDDEN).name();
            return ToolError.classified(code,
                    io.github.liumaishenjian.ccjava.domain.ToolFailureCategory.HTTP_FORBIDDEN,
                    false, message, new JsonObject(java.util.Map.of("reason", reason)));
        }
        return ToolError.of(code, message);
    }
}
