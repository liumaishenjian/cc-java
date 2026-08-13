package io.github.liumaishenjian.ccjava.cli.provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 不含 credential 的 Provider endpoint 与本地模型 catalog。
 *
 * <p>官方 Provider 的 origin 由 {@link ProviderCatalog} 固定；custom compatible 生产地址必须 HTTPS。
 * Header 中严禁认证与 cookie。该对象可安全参与本地选择，但 list surface 仍不得输出 URI/Header。</p>
 *
 * @param providerId Provider 的稳定标识
 * @param kind Provider 使用的协议类别
 * @param displayName 用于本地界面展示的名称
 * @param baseUri 通过安全校验的服务根 URI
 * @param apiVariant 与协议类别对应的编译期 API 变体
 * @param models Provider 支持的精确模型标识目录
 * @param defaultModelId 模型目录内的默认模型标识
 * @param staticHeaders 请求携带的非认证静态 Header
 * @param connectTimeout 建立连接的最长等待时间
 * @param requestTimeout 单次请求的最长等待时间
 * @since 0.1.0
 */
public record ProviderDefinition(
        String providerId, Kind kind, String displayName, URI baseUri, ApiVariant apiVariant, List<String> models,
        String defaultModelId, Map<String, String> staticHeaders, Duration connectTimeout, Duration requestTimeout) {
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie");
    /** 支持的直连协议。 */
    public enum Kind {
        /** OpenAI-compatible 协议。 */
        OPENAI_COMPATIBLE,
        /** Anthropic 原生协议。 */
        ANTHROPIC,
        /** OpenRouter 协议。 */
        OPENROUTER
    }

    /** 受支持且不能由任意字符串扩展的 API 变体。 */
    public enum ApiVariant {
        /** OpenAI Chat Completions API。 */
        OPENAI_CHAT_COMPLETIONS,
        /** Anthropic Messages API。 */
        ANTHROPIC_MESSAGES,
        /** OpenRouter Chat Completions API。 */
        OPENROUTER_CHAT_COMPLETIONS
    }

    /** 执行全部 size、identity、URI、catalog 和 Header 不变量。 */
    public ProviderDefinition {
        providerId = id(providerId); kind = Objects.requireNonNull(kind, "kind 不能为空");
        displayName = text(displayName, 80, 256, "displayName"); baseUri = validateUri(baseUri, kind);
        apiVariant = Objects.requireNonNull(apiVariant, "apiVariant 不能为空");
        if ((kind == Kind.OPENAI_COMPATIBLE && apiVariant != ApiVariant.OPENAI_CHAT_COMPLETIONS)
                || (kind == Kind.ANTHROPIC && apiVariant != ApiVariant.ANTHROPIC_MESSAGES)
                || (kind == Kind.OPENROUTER && apiVariant != ApiVariant.OPENROUTER_CHAT_COMPLETIONS)) throw invalid();
        models = List.copyOf(Objects.requireNonNull(models, "models 不能为空"));
        if (models.isEmpty() || models.size() > 128) throw invalid();
        Set<String> uniqueModels = new HashSet<>();
        for (String model : models) if (!uniqueModels.add(model(model))) throw invalid();
        defaultModelId = model(defaultModelId);
        if (!models.contains(defaultModelId)) throw invalid();
        staticHeaders = Map.copyOf(Objects.requireNonNull(staticHeaders, "staticHeaders 不能为空"));
        validateHeaders(staticHeaders);
        connectTimeout = timeout(connectTimeout, 1, 30); requestTimeout = timeout(requestTimeout, 1, 300);
    }

    private static URI validateUri(URI uri, Kind kind) {
        Objects.requireNonNull(uri, "baseUri 不能为空");
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw invalid();
        if (kind == Kind.ANTHROPIC && !sameOrigin(uri, "https", "api.anthropic.com", 443)) throw invalid();
        if (kind == Kind.OPENROUTER && !sameOrigin(uri, "https", "openrouter.ai", 443)) throw invalid();
        return uri;
    }
    private static boolean sameOrigin(URI u,String s,String h,int p) {
        int port=u.getPort()<0?443:u.getPort(); return s.equalsIgnoreCase(u.getScheme())&&h.equalsIgnoreCase(u.getHost())&&port==p;
    }
    private static void validateHeaders(Map<String,String> headers) {
        if (headers.size()>16) throw invalid(); int total=0; Set<String> names=new HashSet<>();
        for (var e:headers.entrySet()) { String n=e.getKey(), v=e.getValue(), lower=n.toLowerCase(Locale.ROOT);
            if (!n.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}") || !names.add(lower) || FORBIDDEN_HEADERS.contains(lower)
                    || v==null || v.getBytes(StandardCharsets.UTF_8).length>1024 || v.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            total += n.getBytes(StandardCharsets.US_ASCII).length + v.getBytes(StandardCharsets.UTF_8).length;
        } if(total>8192) throw invalid();
    }
    private static String id(String v) { Objects.requireNonNull(v); if(!v.matches("[a-z0-9][a-z0-9-]{0,62}")) throw invalid(); return v; }
    private static String model(String v) { return text(v,256,1024,"modelId"); }
    private static String text(String v,int cp,int bytes,String field) { Objects.requireNonNull(v,field); if(v.isBlank()||!v.equals(v.strip())||v.codePointCount(0,v.length())>cp||v.getBytes(StandardCharsets.UTF_8).length>bytes||v.codePoints().anyMatch(Character::isISOControl)) throw invalid(); return v; }
    private static Duration timeout(Duration d,long min,long max) { Objects.requireNonNull(d); if(d.compareTo(Duration.ofSeconds(min))<0||d.compareTo(Duration.ofSeconds(max))>0) throw invalid(); return d; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PROVIDER_DEFINITION_INVALID"); }
}
