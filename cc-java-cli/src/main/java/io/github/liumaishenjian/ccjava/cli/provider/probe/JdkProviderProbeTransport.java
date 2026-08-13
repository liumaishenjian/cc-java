package io.github.liumaishenjian.ccjava.cli.provider.probe;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessPort;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessRequest;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用 JDK HttpClient 的固定目标 Provider auth probe transport。
 *
 * <p>请求目标只能由 definition 追加编译期 models 路径得到；redirect 永不跟随，响应正文限制 64 KiB，
 * 单调 deadline 同时覆盖授权、连接、headers、body 与严格 JSON 解析。认证字符仅在构造 Header 的 SDK
 * 边界短暂转为 String；该不可清零副本是 ADR-070 明示 gap，绝不进入异常、日志或结果。</p>
 */
public final class JdkProviderProbeTransport implements ProviderProbePort, AutoCloseable {
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final NetworkAccessPort networkAccess;
    private final HttpClient client;
    private final java.util.function.Function<ProviderDefinition, URI> endpointDeriver;
    private final boolean requireDefinitionOrigin;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
    private final AtomicReference<InputStream> body = new AtomicReference<>();

    /**
     * 创建 redirect NEVER 且 connect timeout 不超过 5 秒的生产 transport。
     *
     * @param networkAccess 每次请求前执行固定目标网络授权的端口
     */
    public JdkProviderProbeTransport(NetworkAccessPort networkAccess) {
        this(networkAccess, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), JdkProviderProbeTransport::endpoint, true);
    }

    /**
     * 注入 JDK client 的测试 seam；目标仍只能从 definition 派生。
     *
     * @param networkAccess 每次请求前执行固定目标网络授权的端口
     * @param client 不自动跟随重定向的受控 JDK HTTP 客户端
     */
    public JdkProviderProbeTransport(NetworkAccessPort networkAccess, HttpClient client) {
        this(networkAccess, client, JdkProviderProbeTransport::endpoint, true);
    }

    /** 包级 wire-test seam：仅替换派生结果，生产构造始终校验 definition origin。 */
    JdkProviderProbeTransport(NetworkAccessPort networkAccess, HttpClient client,
                              java.util.function.Function<ProviderDefinition, URI> endpointDeriver) {
        this(networkAccess,client,endpointDeriver,false);
    }
    private JdkProviderProbeTransport(NetworkAccessPort networkAccess, HttpClient client,
                                     java.util.function.Function<ProviderDefinition, URI> endpointDeriver,
                                     boolean requireDefinitionOrigin) {
        this.networkAccess=Objects.requireNonNull(networkAccess, "networkAccess 不能为空");
        this.client=Objects.requireNonNull(client, "client 不能为空");
        this.endpointDeriver=Objects.requireNonNull(endpointDeriver, "endpointDeriver 不能为空");
        this.requireDefinitionOrigin=requireDefinitionOrigin;
    }

    @Override
    public ProbeOutcome probe(ProviderDefinition definition, String modelId, char[] apiKey,
                              Duration requestedTimeout, CancellationToken cancellation) {
        Objects.requireNonNull(definition); Objects.requireNonNull(modelId); Objects.requireNonNull(apiKey);
        Objects.requireNonNull(cancellation);
        Duration timeout = boundedTimeout(requestedTimeout);
        if (closed.get()) return ProbeOutcome.CANCELLED;
        if (cancellation.isCancellationRequested()) return ProbeOutcome.CANCELLED;
        long deadline = deadlineAfter(timeout);
        URI target;
        try { target = endpointDeriver.apply(definition); } catch (RuntimeException unsupported) { return ProbeOutcome.UNSUPPORTED; }
        NetworkAccessDecision decision = networkAccess.authorize(new NetworkAccessRequest(
                NetworkPurpose.PROVIDER_AUTH_PROBE, lower(target.getScheme()), lower(target.getHost()),
                effectivePort(target), remaining(deadline), false), cancellation);
        if (cancellation.isCancellationRequested()) return ProbeOutcome.CANCELLED;
        if (!decision.allowed() || !decision.controlled()) {
            return decision.reason() == NetworkAccessReason.CANCELLED
                    ? ProbeOutcome.CANCELLED : ProbeOutcome.UNREACHABLE;
        }
        if (requireDefinitionOrigin && !sameOrigin(definition.baseUri(), target)) return ProbeOutcome.UNSUPPORTED;
        String secret = new String(apiKey);
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target).GET().timeout(remaining(deadline))
                    .header("Accept", "application/json");
            definition.staticHeaders().forEach(builder::header);
            switch (definition.kind()) {
                case ANTHROPIC -> builder.header("x-api-key", secret).header("anthropic-version", "2023-06-01");
                case OPENAI_COMPATIBLE, OPENROUTER -> builder.header("Authorization", "Bearer " + secret);
            }
            request = builder.build();
        } finally {
            secret = null;
        }
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        active.set(future);
        try (CancellationToken.Registration registration = cancellation.onCancellation(() -> {
            future.cancel(true); closeBody();
        })) {
            HttpResponse<InputStream> response = future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            InputStream stream=response.body(); body.set(stream);
            try (stream) {
                int status=response.statusCode();
                if (status >= 300 && status < 400) return ProbeOutcome.UNREACHABLE;
                if (status == 401 || status == 403) return ProbeOutcome.REJECTED;
                if (status == 429) return ProbeOutcome.RATE_LIMITED;
                if (status < 200 || status >= 300 || !strictJson(response)) return ProbeOutcome.UNREACHABLE;
                byte[] bytes=readBounded(stream, deadline, cancellation);
                try {
                    JsonNode root=JSON.readTree(bytes);
                    if (root == null || !root.isObject() || root.get("data") == null || !root.get("data").isArray()) {
                        return ProbeOutcome.UNREACHABLE;
                    }
                    return ProbeOutcome.SUCCESS;
                } catch (RuntimeException invalidJson) {
                    return ProbeOutcome.UNREACHABLE;
                } finally { Arrays.fill(bytes,(byte)0); }
            } finally { body.compareAndSet(stream,null); }
        } catch (TimeoutException failure) {
            future.cancel(true); return ProbeOutcome.TIMED_OUT;
        } catch (CancellationException failure) {
            return cancellation.isCancellationRequested() || closed.get()
                    ? ProbeOutcome.CANCELLED : ProbeOutcome.UNREACHABLE;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); future.cancel(true); return ProbeOutcome.CANCELLED;
        } catch (ExecutionException failure) {
            Throwable cause=unwrap(failure.getCause());
            return cause instanceof java.net.http.HttpTimeoutException
                    ? ProbeOutcome.TIMED_OUT : ProbeOutcome.UNREACHABLE;
        } catch (IOException failure) {
            return expired(deadline) ? ProbeOutcome.TIMED_OUT
                    : cancellation.isCancellationRequested() ? ProbeOutcome.CANCELLED : ProbeOutcome.UNREACHABLE;
        } finally {
            active.compareAndSet(future,null); closeBody(); if (!future.isDone()) future.cancel(true);
        }
    }

    private static URI endpoint(ProviderDefinition definition) {
        String base=definition.baseUri().toString().replaceAll("/+$", "");
        String suffix=switch(definition.kind()) {
            case ANTHROPIC -> "/v1/models?limit=1";
            case OPENAI_COMPATIBLE -> base.endsWith("/v1") ? "/models?limit=1" : "/v1/models?limit=1";
            case OPENROUTER -> base.endsWith("/api") ? "/v1/models?limit=1" : "/models?limit=1";
        };
        URI target=URI.create(base+suffix);
        if (target.getUserInfo()!=null || target.getFragment()!=null) throw new IllegalArgumentException();
        return target;
    }
    private static byte[] readBounded(InputStream input,long deadline,CancellationToken cancellation) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] buffer=new byte[4096];
        for (;;) {
            if (cancellation.isCancellationRequested()) throw new CancellationException();
            if (expired(deadline)) throw new java.net.http.HttpTimeoutException("timeout");
            int read=input.read(buffer); if(read<0)break;
            if(output.size()+read>MAX_BODY_BYTES) throw new IOException();
            output.write(buffer,0,read);
        }
        return output.toByteArray();
    }
    private static Duration boundedTimeout(Duration timeout) {
        Objects.requireNonNull(timeout); if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("probe timeout 非法"); return timeout;
    }
    private static long deadlineAfter(Duration timeout) {
        long now=System.nanoTime(); long nanos=timeout.toNanos(); return Long.MAX_VALUE-now<nanos?Long.MAX_VALUE:now+nanos;
    }
    private static long remainingNanos(long deadline) throws java.net.http.HttpTimeoutException {
        long value=deadline-System.nanoTime(); if(value<=0)throw new java.net.http.HttpTimeoutException("timeout"); return value;
    }
    private static Duration remaining(long deadline) {
        try{return Duration.ofNanos(remainingNanos(deadline));}catch(java.net.http.HttpTimeoutException failure){return Duration.ofNanos(1);}
    }
    private static boolean expired(long deadline){return deadline-System.nanoTime()<=0;}
    private static int effectivePort(URI uri){return uri.getPort()>0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;}
    private static boolean strictJson(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").map(value -> {
            String mediaType=value.split(";",2)[0].strip().toLowerCase(Locale.ROOT);
            return mediaType.equals("application/json") || mediaType.endsWith("+json");
        }).orElse(false);
    }
    private static boolean sameOrigin(URI left,URI right){return lower(left.getScheme()).equals(lower(right.getScheme()))
            && lower(left.getHost()).equals(lower(right.getHost()))&&effectivePort(left)==effectivePort(right);}
    private static String lower(String value){return value.toLowerCase(Locale.ROOT);}
    private static Throwable unwrap(Throwable value){while(value instanceof CompletionException && value.getCause()!=null)value=value.getCause();return value;}
    private void closeBody(){InputStream stream=body.getAndSet(null);if(stream!=null)try{stream.close();}catch(IOException ignored){}}
    /** 取消当前请求并关闭响应流；不关闭共享 JDK HttpClient。 */
    @Override public void close(){if(closed.compareAndSet(false,true)){CompletableFuture<?> value=active.getAndSet(null);if(value!=null)value.cancel(true);closeBody();}}
}