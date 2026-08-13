package io.github.liumaishenjian.ccjava.tools.web;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessPort;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessRequest;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过 JSON-RPC 2.0 {@code tools/call} 调用固定 hosted MCP Search 的边缘适配器。
 *
 * <p>每次 HTTP attempt 先经过 {@link NetworkAccessPort} 并与固定 Provider 目标逐字段对账；
 * redirect 永不跟随。Adapter 从 {@link #search(WebSearchRequest, CancellationToken)} 入口建立单一
 * wall-clock deadline，并由可关闭的虚拟线程任务覆盖网络授权、响应头、完整有界正文与解析；期限耗尽或
 * 取消都会取消 HTTP future、关闭 active body 并中断任务。它只接受严格有界的
 * {@code application/json} 或 {@code text/event-stream}，只投影 MCP textual content，不抓取搜索结果
 * URL；该应用层网络控制也不等于 OS Sandbox。</p>
 *
 * @since 0.1.0
 */
public final class HostedMcpWebSearchClient implements WebSearchClient, AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    static final int MAX_SSE_LINES = 2_048;
    private static final int MAX_TEXT_ITEMS = 32;
    private static final int MAX_CONTEXT_CODE_POINTS = 48_000;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final WebSearchConfiguration configuration;
    private final NetworkAccessPort networkAccess;
    private final HttpClient client;
    private final ExecutorService operations;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建固定 hosted MCP 目标的生产 Client。
     *
     * @param configuration 固定 hosted MCP 配置
     * @param networkAccess 每次出站的应用层授权端口
     */
    public HostedMcpWebSearchClient(WebSearchConfiguration configuration, NetworkAccessPort networkAccess) {
        this(configuration, networkAccess, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** 注入真实 JDK client，供 loopback wire-contract 测试。 */
    HostedMcpWebSearchClient(WebSearchConfiguration configuration, NetworkAccessPort networkAccess,
            HttpClient client) {
        this.configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
        this.networkAccess = Objects.requireNonNull(networkAccess, "networkAccess 不能为空");
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.operations = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("cc-java-web-search-", 0).factory());
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, CancellationToken cancellation)
            throws WebSearchException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (!configuration.enabled()) throw new WebSearchException(WebSearchFailure.DISABLED);
        if (cancellation.isCancellationRequested()) throw new WebSearchException(WebSearchFailure.CANCELLED);
        if (closed.get()) throw new WebSearchException(WebSearchFailure.EXECUTION_FAILED);

        long deadlineNanos = deadlineAfter(configuration.timeout());
        AttemptControl control = new AttemptControl();
        final Future<WebSearchResponse> operation;
        try {
            operation = operations.submit(() -> execute(request, cancellation, deadlineNanos, control));
        } catch (RejectedExecutionException failure) {
            throw new WebSearchException(WebSearchFailure.EXECUTION_FAILED);
        }
        control.operation.set(operation);
        try (CancellationToken.Registration registration = cancellation.onCancellation(control::cancel)) {
            return awaitOperation(operation, deadlineNanos, cancellation, control);
        } finally {
            if (!operation.isDone()) operation.cancel(true);
            control.closeBody();
        }
    }

    private WebSearchResponse execute(WebSearchRequest request, CancellationToken cancellation,
            long deadlineNanos, AttemptControl control) throws WebSearchException {
        checkActive(deadlineNanos, cancellation, control);
        URI authorizedTarget = configuration.endpoint().orElseThrow();
        Duration remaining = remaining(deadlineNanos, cancellation, control);
        int port = effectivePort(authorizedTarget);
        NetworkAccessDecision decision = networkAccess.authorize(new NetworkAccessRequest(
                NetworkPurpose.WEB_SEARCH, lower(authorizedTarget.getScheme()), lower(authorizedTarget.getHost()),
                port, remaining, false), cancellation);
        checkActive(deadlineNanos, cancellation, control);
        if (!decision.allowed() || !decision.controlled()) {
            throw new WebSearchException(decision.reason() == NetworkAccessReason.UNSUPPORTED_CONTROL
                    ? WebSearchFailure.NETWORK_UNCONTROLLED : WebSearchFailure.NETWORK_DENIED);
        }
        verifyTarget(authorizedTarget, configuration.provider());
        URI requestTarget = requestTarget(authorizedTarget, configuration.provider(), configuration.apiKey());
        verifyRequestTarget(authorizedTarget, requestTarget, configuration.provider());
        byte[] body = requestBody(configuration.provider(), request);
        remaining = remaining(deadlineNanos, cancellation, control);
        HttpRequest.Builder builder = HttpRequest.newBuilder(requestTarget)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(remaining)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (configuration.provider() == WebSearchProvider.PARALLEL) {
            configuration.apiKey().ifPresent(key -> builder.header("Authorization", "Bearer " + key));
        }
        CompletableFuture<HttpResponse<InputStream>> http = client.sendAsync(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        control.http.set(http);
        try {
            HttpResponse<InputStream> response = awaitHeaders(http, deadlineNanos, cancellation, control);
            InputStream responseBody = response.body();
            control.body.set(responseBody);
            try (InputStream stream = responseBody) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) throw new WebSearchException(WebSearchFailure.REDIRECT_REFUSED);
                if (status == 429) throw new WebSearchException(WebSearchFailure.RATE_LIMITED);
                if (status >= 500) throw new WebSearchException(WebSearchFailure.REMOTE_SERVER_ERROR);
                if (status < 200 || status >= 300) throw new WebSearchException(WebSearchFailure.REMOTE_CLIENT_ERROR);
                byte[] bytes = readBounded(stream, deadlineNanos, cancellation, control);
                checkActive(deadlineNanos, cancellation, control);
                String mediaType = mediaType(response.headers().firstValue("Content-Type").orElse(""));
                ParsedContent parsed = switch (mediaType) {
                    case "application/json" -> parseJson(bytes);
                    case "text/event-stream" -> parseSse(bytes);
                    default -> throw new WebSearchException(WebSearchFailure.UNSUPPORTED_MEDIA_TYPE);
                };
                checkActive(deadlineNanos, cancellation, control);
                String text = parsed.text();
                if (text == null || text.isBlank()) throw new WebSearchException(WebSearchFailure.NO_RESULTS);
                String bounded = sanitize(text, MAX_CONTEXT_CODE_POINTS);
                return new WebSearchResponse(
                        authorizedTarget.getHost().toLowerCase(Locale.ROOT), bounded, parsed.items(),
                        text.codePointCount(0, text.length()) > MAX_CONTEXT_CODE_POINTS);
            } catch (IOException failure) {
                throw terminalFailure(deadlineNanos, cancellation, control);
            } finally {
                control.body.compareAndSet(responseBody, null);
            }
        } finally {
            control.http.compareAndSet(http, null);
            if (!http.isDone()) http.cancel(true);
        }
    }

    private static WebSearchResponse awaitOperation(Future<WebSearchResponse> operation, long deadlineNanos,
            CancellationToken cancellation, AttemptControl control) throws WebSearchException {
        try {
            return operation.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            control.timeout();
            throw terminalFailure(deadlineNanos, cancellation, control);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            control.cancel();
            throw new WebSearchException(cancellation.isCancellationRequested()
                    ? WebSearchFailure.CANCELLED : WebSearchFailure.EXECUTION_FAILED);
        } catch (CancellationException failure) {
            throw terminalFailure(deadlineNanos, cancellation, control);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof WebSearchException searchFailure) throw searchFailure;
            throw terminalFailure(deadlineNanos, cancellation, control);
        }
    }

    private static HttpResponse<InputStream> awaitHeaders(CompletableFuture<HttpResponse<InputStream>> future,
            long deadlineNanos, CancellationToken cancellation, AttemptControl control) throws WebSearchException {
        try {
            return future.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            control.timeout();
            throw terminalFailure(deadlineNanos, cancellation, control);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw terminalFailure(deadlineNanos, cancellation, control);
        } catch (CancellationException failure) {
            throw terminalFailure(deadlineNanos, cancellation, control);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof TimeoutException) {
                control.timeout();
                throw new WebSearchException(WebSearchFailure.TIMED_OUT);
            }
            if (cause instanceof CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            throw terminalFailure(deadlineNanos, cancellation, control);
        } finally {
            if (control.terminal.get() != Terminal.NONE && !future.isDone()) future.cancel(true);
        }
    }

    private static byte[] requestBody(WebSearchProvider provider, WebSearchRequest request)
            throws WebSearchException {
        try {
            var args = JSON.createObjectNode();
            if (provider == WebSearchProvider.EXA) {
                args.put("query", request.query());
                args.put("type", "auto");
                args.put("numResults", request.resultLimit());
                args.put("livecrawl", "fallback");
                args.put("contextMaxCharacters", MAX_CONTEXT_CODE_POINTS);
            } else {
                args.put("objective", request.query());
                args.putArray("search_queries").add(request.query());
            }
            var root = JSON.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", 1);
            root.put("method", "tools/call");
            var params = root.putObject("params");
            params.put("name", provider.remoteToolName());
            params.set("arguments", args);
            return JSON.writeValueAsBytes(root);
        } catch (Exception failure) {
            throw new WebSearchException(WebSearchFailure.EXECUTION_FAILED);
        }
    }

    private static ParsedContent parseJson(byte[] bytes) throws WebSearchException {
        return parsePayload(bytes);
    }

    private static ParsedContent parseSse(byte[] bytes) throws WebSearchException {
        String body = strictUtf8(bytes);
        int lines = 0;
        for (String line : body.split("\\r?\\n", -1)) {
            if (++lines > MAX_SSE_LINES) throw new WebSearchException(WebSearchFailure.RESPONSE_TOO_LARGE);
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).stripLeading();
            if (payload.isBlank() || "[DONE]".equals(payload)) continue;
            ParsedContent parsed = parsePayload(payload.getBytes(StandardCharsets.UTF_8));
            if (parsed.text() != null && !parsed.text().isBlank()) return parsed;
        }
        throw new WebSearchException(WebSearchFailure.NO_RESULTS);
    }

    private static ParsedContent parsePayload(byte[] bytes) throws WebSearchException {
        final JsonNode root;
        try {
            root = JSON.readTree(new ByteArrayInputStream(bytes));
        } catch (Exception failure) {
            throw new WebSearchException(WebSearchFailure.MALFORMED_RESPONSE);
        }
        JsonNode responseId = root == null ? null : root.get("id");
        if (root == null || !root.isObject() || !"2.0".equals(text(root, "jsonrpc"))
                || responseId == null || !responseId.isIntegralNumber() || responseId.longValue() != 1L) {
            throw new WebSearchException(WebSearchFailure.MALFORMED_RESPONSE);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) throw new WebSearchException(WebSearchFailure.REMOTE_PROTOCOL_ERROR);
        JsonNode result = root.get("result");
        JsonNode content = result == null ? null : result.get("content");
        if (result == null || !result.isObject() || content == null || !content.isArray()) {
            throw new WebSearchException(WebSearchFailure.MALFORMED_RESPONSE);
        }
        ArrayList<String> texts = new ArrayList<>();
        int count = 0;
        for (JsonNode item : content) {
            if (++count > MAX_TEXT_ITEMS) throw new WebSearchException(WebSearchFailure.RESPONSE_TOO_LARGE);
            if (!item.isObject() || !"text".equals(text(item, "type"))) continue;
            String value = text(item, "text");
            if (value != null && !value.isBlank()) texts.add(value);
        }
        return new ParsedContent(texts.isEmpty() ? null : String.join("\n", texts), texts.size());
    }

    private record ParsedContent(String text, int items) { }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String strictUtf8(byte[] bytes) throws WebSearchException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new WebSearchException(WebSearchFailure.MALFORMED_RESPONSE);
        }
    }

    private static byte[] readBounded(InputStream input, long deadlineNanos,
            CancellationToken cancellation, AttemptControl control) throws IOException, WebSearchException {
        byte[] buffer = new byte[8_192];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            checkActive(deadlineNanos, cancellation, control);
            int read = input.read(buffer);
            checkActive(deadlineNanos, cancellation, control);
            if (read < 0) return output.toByteArray();
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new WebSearchException(WebSearchFailure.RESPONSE_TOO_LARGE);
            }
            output.write(buffer, 0, read);
        }
    }

    private static Duration remaining(long deadlineNanos, CancellationToken cancellation, AttemptControl control)
            throws WebSearchException {
        checkActive(deadlineNanos, cancellation, control);
        return Duration.ofNanos(remainingNanos(deadlineNanos));
    }

    private static long remainingNanos(long deadlineNanos) throws WebSearchException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new WebSearchException(WebSearchFailure.TIMED_OUT);
        return remaining;
    }

    private static void checkActive(long deadlineNanos, CancellationToken cancellation, AttemptControl control)
            throws WebSearchException {
        Terminal terminal = control.terminal.get();
        if (terminal == Terminal.CANCELLED || cancellation.isCancellationRequested()) {
            throw new WebSearchException(WebSearchFailure.CANCELLED);
        }
        if (terminal == Terminal.TIMED_OUT || System.nanoTime() >= deadlineNanos) {
            control.timeout();
            throw terminalFailure(deadlineNanos, cancellation, control);
        }
        if (Thread.currentThread().isInterrupted()) throw terminalFailure(deadlineNanos, cancellation, control);
    }

    private static WebSearchException terminalFailure(long deadlineNanos,
            CancellationToken cancellation, AttemptControl control) {
        if (control.terminal.get() == Terminal.CANCELLED || cancellation.isCancellationRequested()) {
            return new WebSearchException(WebSearchFailure.CANCELLED);
        }
        if (control.terminal.get() == Terminal.TIMED_OUT || System.nanoTime() >= deadlineNanos) {
            return new WebSearchException(WebSearchFailure.TIMED_OUT);
        }
        return new WebSearchException(WebSearchFailure.EXECUTION_FAILED);
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        long deadline = now + nanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private static String sanitize(String value, int maxCodePoints) {
        StringBuilder clean = new StringBuilder(Math.min(value.length(), maxCodePoints));
        value.codePoints().forEach(point -> clean.appendCodePoint(
                Character.isISOControl(point) && point != '\n' ? ' ' : point));
        String normalized = clean.toString().replace("", " ").strip();
        int points = normalized.codePointCount(0, normalized.length());
        return points <= maxCodePoints ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maxCodePoints - 1)) + "…";
    }

    private static void verifyTarget(URI endpoint, WebSearchProvider provider) throws WebSearchException {
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null || endpoint.getRawQuery() != null) {
            throw new WebSearchException(WebSearchFailure.INVALID_TARGET);
        }
        if (!endpoint.getScheme().equalsIgnoreCase("http")
                && !WebSearchConfiguration.hostedEndpoint(provider).equals(endpoint.normalize())) {
            throw new WebSearchException(WebSearchFailure.INVALID_TARGET);
        }
    }

    private static URI requestTarget(URI authorizedTarget, WebSearchProvider provider, java.util.Optional<String> apiKey)
            throws WebSearchException {
        if (provider != WebSearchProvider.EXA || apiKey.isEmpty()) return authorizedTarget;
        try {
            return URI.create(authorizedTarget.toASCIIString() + "?exaApiKey=" + encodeQueryValue(apiKey.orElseThrow()));
        } catch (IllegalArgumentException failure) {
            throw new WebSearchException(WebSearchFailure.INVALID_TARGET);
        }
    }

    private static void verifyRequestTarget(URI authorizedTarget, URI requestTarget, WebSearchProvider provider)
            throws WebSearchException {
        if (!sameAuthorityAndPath(authorizedTarget, requestTarget) || requestTarget.getRawUserInfo() != null
                || requestTarget.getRawFragment() != null) {
            throw new WebSearchException(WebSearchFailure.INVALID_TARGET);
        }
        String query = requestTarget.getRawQuery();
        boolean validQuery = provider == WebSearchProvider.EXA
                ? query == null || query.matches("exaApiKey=[A-Za-z0-9._~-]*(?:%[0-9A-F]{2}[A-Za-z0-9._~-]*)*")
                : query == null;
        if (!validQuery) throw new WebSearchException(WebSearchFailure.INVALID_TARGET);
    }

    private static boolean sameAuthorityAndPath(URI first, URI second) {
        return lower(first.getScheme()).equals(lower(second.getScheme()))
                && lower(first.getHost()).equals(lower(second.getHost()))
                && effectivePort(first) == effectivePort(second)
                && Objects.equals(first.getRawPath(), second.getRawPath());
    }

    private static String encodeQueryValue(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(octet);
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }

    private static String mediaType(String contentType) {
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    private enum Terminal { NONE, CANCELLED, TIMED_OUT }

    private static final class AttemptControl {
        private final AtomicReference<Terminal> terminal = new AtomicReference<>(Terminal.NONE);
        private final AtomicReference<Future<?>> operation = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<?>> http = new AtomicReference<>();
        private final AtomicReference<InputStream> body = new AtomicReference<>();

        void cancel() { abort(Terminal.CANCELLED); }
        void timeout() { abort(Terminal.TIMED_OUT); }

        void abort(Terminal reason) {
            if (!terminal.compareAndSet(Terminal.NONE, reason)) return;
            CompletableFuture<?> activeHttp = http.get();
            if (activeHttp != null) activeHttp.cancel(true);
            closeBody();
            Future<?> activeOperation = operation.get();
            if (activeOperation != null) activeOperation.cancel(true);
        }

        void closeBody() { closeQuietly(body.getAndSet(null)); }
    }

    /** 关闭 Client 并中断尚未收敛的虚拟线程操作；JDK HttpClient 自身无独立关闭契约。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) operations.shutdownNow();
    }
}
