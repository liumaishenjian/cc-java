package io.github.liumaishenjian.ccjava.tools.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 固定一次 Headless Session 使用的托管 MCP Web Search 可信配置。
 *
 * <p>该值只能由 Composition Root 从本地外部配置构造，不能来自模型 Tool 参数。生产目标由
 * Provider gate 固定为已审核的 hosted MCP URI；API key 不参与 {@link #toString()}，也不得进入
 * Session、事件或错误。测试 seam 可以显式提供仍受目标绑定约束的 loopback HTTP URI。</p>
 *
 * @param enabled 是否发布并允许执行搜索能力
 * @param provider 固定 hosted MCP Provider
 * @param endpoint 固定 JSON-RPC 2.0 MCP endpoint
 * @param apiKey 可选 credential，仅由 HTTP Adapter 使用
 * @param timeout 单次 HTTP 总期限
 * @param allowLoopbackHttp 是否显式允许 loopback HTTP 测试 seam
 * @since 0.1.0
 */
public record WebSearchConfiguration(
        boolean enabled,
        WebSearchProvider provider,
        Optional<URI> endpoint,
        Optional<String> apiKey,
        Duration timeout,
        boolean allowLoopbackHttp) {

    /** Exa 无密钥 hosted MCP 的公开固定目标。 */
    public static final URI EXA_HOSTED_MCP = URI.create("https://mcp.exa.ai/mcp");
    /** Parallel hosted MCP 的公开固定目标。 */
    public static final URI PARALLEL_HOSTED_MCP = URI.create("https://search.parallel.ai/mcp");

    /** 规范化并验证 Provider、固定目标与 Secret 边界。 */
    public WebSearchConfiguration {
        provider = Objects.requireNonNull(provider, "provider 不能为空");
        endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为空");
        apiKey = Objects.requireNonNull(apiKey, "apiKey 不能为空")
                .map(String::trim).filter(value -> !value.isEmpty());
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("web search timeout 必须在 0 到 30 秒之间");
        }
        if (apiKey.filter(value -> value.length() > 8_192 || containsControl(value)).isPresent()) {
            throw new IllegalArgumentException("web search credential 非法");
        }
        if (!enabled) {
            endpoint = Optional.empty();
            apiKey = Optional.empty();
        } else {
            URI uri = endpoint.orElseThrow(() -> new IllegalArgumentException("启用 web search 必须配置 endpoint"));
            validateEndpoint(uri, allowLoopbackHttp);
            if (!allowLoopbackHttp && !hostedEndpoint(provider).equals(uri.normalize())) {
                throw new IllegalArgumentException("web search endpoint 与 provider gate 不匹配");
            }
            endpoint = Optional.of(uri.normalize());
        }
    }

    /**
     * 创建默认关闭配置。
     *
     * @return 不持有 endpoint/credential 的配置
     */
    public static WebSearchConfiguration disabled() {
        return new WebSearchConfiguration(false, WebSearchProvider.EXA, Optional.empty(), Optional.empty(),
                Duration.ofSeconds(10), false);
    }

    /**
     * 创建生产 hosted MCP 配置。
     *
     * @param provider 本地显式选择的 Provider
     * @param apiKey 可选本地 Secret；Exa 无密钥可工作
     * @param timeout 请求期限
     * @return 固定目标配置
     */
    public static WebSearchConfiguration hosted(
            WebSearchProvider provider, Optional<String> apiKey, Duration timeout) {
        return new WebSearchConfiguration(true, provider, Optional.of(hostedEndpoint(provider)), apiKey, timeout, false);
    }

    /**
     * 创建仅供显式 loopback 测试 seam 使用的 HTTP 配置。
     *
     * @param provider 要模拟的 Provider wire contract
     * @param endpoint loopback HTTP endpoint
     * @param apiKey 可选测试 credential
     * @param timeout 请求期限
     * @return 仍执行全部目标、权限和响应限制的配置
     */
    public static WebSearchConfiguration loopbackDevelopment(
            WebSearchProvider provider, URI endpoint, Optional<String> apiKey, Duration timeout) {
        return new WebSearchConfiguration(true, provider, Optional.of(endpoint), apiKey, timeout, true);
    }

    /**
     * 返回 Provider 的固定生产目标。
     *
     * @param provider 本地可信配置选择的 Provider
     * @return 不能由模型覆盖的生产 URI
     */
    public static URI hostedEndpoint(WebSearchProvider provider) {
        return switch (Objects.requireNonNull(provider, "provider 不能为空")) {
            case EXA -> EXA_HOSTED_MCP;
            case PARALLEL -> PARALLEL_HOSTED_MCP;
        };
    }

    private static void validateEndpoint(URI uri, boolean allowLoopbackHttp) {
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (!uri.isAbsolute() || host == null || host.isBlank() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawQuery() != null
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IllegalArgumentException("web search endpoint 非法");
        }
        if ("https".equals(scheme)) return;
        if (!"http".equals(scheme) || !allowLoopbackHttp || !isLoopback(host)) {
            throw new IllegalArgumentException("web search endpoint 必须使用 HTTPS");
        }
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) return true;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && java.util.Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
        } catch (UnknownHostException failure) {
            return false;
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** 不显示 Provider endpoint 或 credential，避免调试输出成为泄漏出口。 */
    @Override
    public String toString() {
        return "WebSearchConfiguration[enabled=" + enabled + ", provider=" + provider
                + ", timeout=" + timeout + ", allowLoopbackHttp=" + allowLoopbackHttp + "]";
    }
}
