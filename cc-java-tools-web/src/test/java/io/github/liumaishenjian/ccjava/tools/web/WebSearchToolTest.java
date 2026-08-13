package io.github.liumaishenjian.ccjava.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {
    private static final String JSON_SUCCESS = """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"current answer with https://example.com/source"}]}}
            """;

    @Test
    void definitionOnlyExposesQueryAndLimitWithBuiltinNetworkEffect() {
        WebSearchTool tool = toolReturning("safe");
        assertThat(tool.definition().name()).isEqualTo("web_search");
        assertThat(tool.definition().effect()).isEqualTo(ToolEffect.NETWORK_OR_REMOTE);
        assertThat(tool.definition().source()).isEqualTo(ToolSource.BUILT_IN);
        assertThat(tool.definition().description()).contains("weather", "time-sensitive");
        assertThat(tool.definition().inputSchemaJson())
                .contains("query", "result_limit")
                .doesNotContain("endpoint", "header", "credential", "allowed_domains", "blocked_domains");
    }

    @Test
    void validatesUnknownTypesAndBounds() {
        WebSearchTool tool = toolReturning("safe");
        assertThat(tool.validate(json("query", "ok")).valid()).isTrue();
        assertThat(tool.validate(json("query", "ok", "unexpected", true)).valid()).isFalse();
        assertThat(tool.validate(json("query", "ok", "result_limit", 0)).valid()).isFalse();
        assertThat(tool.validate(json("query", "ok", "result_limit", 1.5)).valid()).isFalse();
        assertThat(tool.validate(json("query", "\0")).valid()).isFalse();
    }

    @Test
    void rendersOpaqueMcpTextWithUntrustedProvenanceWithoutQuery() {
        WebSearchTool tool = new WebSearchTool((request, cancellation) ->
                new WebSearchResponse("mcp.example", "answer with citation", 2, true));
        var outcome = tool.execute(invocation(json("query", "SECRET_QUERY")));
        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.content()).contains(
                "provenance: external-web-search", "untrusted: true", "contentFetched: false",
                "providerHost: mcp.example", "answer with citation")
                .doesNotContain("SECRET_QUERY", "rank:", "url:");
        assertThat(outcome.metadata().returnedItems()).isEqualTo(2);
        assertThat(outcome.metadata().truncated()).isTrue();
    }

    @Test
    void jsonRpcExaWireAndJsonResponseAreExactAndBounded() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<io.github.liumaishenjian.ccjava.core.network.NetworkAccessRequest> authorized =
                new AtomicReference<>();
        try (Server server = new Server(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "application/json; charset=UTF-8", JSON_SUCCESS);
        })) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.of("LOCAL TEST +/密钥"), Duration.ofSeconds(3));
            try (HostedMcpWebSearchClient client = new HostedMcpWebSearchClient(configuration, (networkRequest, cancellation) -> {
                authorized.set(networkRequest);
                return NetworkAccessDecision.allow();
            })) {
                WebSearchResponse response = client.search(new WebSearchRequest("bounded query", 7), CancellationToken.none());
                assertThat(response.providerHost()).isEqualTo("127.0.0.1");
                assertThat(response.content()).contains("current answer");
                assertThat(response.contentItems()).isEqualTo(1);
            }
            assertThat(authorization.get()).isNull();
            assertThat(rawQuery).hasValue("exaApiKey=LOCAL%20TEST%20%2B%2F%E5%AF%86%E9%92%A5");
            assertThat(authorized.get()).satisfies(networkRequest -> {
                assertThat(networkRequest.scheme()).isEqualTo("http");
                assertThat(networkRequest.host()).isEqualTo("127.0.0.1");
                assertThat(networkRequest.port()).isEqualTo(server.uri().getPort());
                assertThat(networkRequest.redirectsAllowed()).isFalse();
                assertThat(networkRequest.toString()).doesNotContain("LOCAL", "密钥", "exaApiKey");
            });
            assertThat(requestBody.get()).contains(
                    "\"jsonrpc\":\"2.0\"", "\"method\":\"tools/call\"",
                    "\"name\":\"web_search_exa\"", "\"query\":\"bounded query\"",
                    "\"numResults\":7", "\"type\":\"auto\"", "\"livecrawl\":\"fallback\"")
                    .doesNotContain("endpoint", "Authorization", "LOCAL TEST", "密钥");
        }
    }

    @Test
    void exaWithoutKeyUsesFixedPathWithoutQueryOrAuthorization() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        try (Server server = new Server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "application/json", JSON_SUCCESS);
        })) {
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.empty(), Duration.ofSeconds(3));
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                assertThat(client.search(new WebSearchRequest("q", 1), CancellationToken.none()).content())
                        .contains("current answer");
            }
            assertThat(authorization.get()).isNull();
            assertThat(rawQuery.get()).isNull();
        }
    }

    @Test
    void parallelWireAndSseResponseAreSupported() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        try (Server server = new Server(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "text/event-stream; charset=utf-8", "data: " + JSON_SUCCESS.strip() + "\n\n");
        })) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.PARALLEL, server.uri(), Optional.of("PARALLEL_LOCAL_KEY"), Duration.ofSeconds(3));
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                assertThat(client.search(new WebSearchRequest("q", 3), CancellationToken.none()).content())
                        .contains("current answer");
            }
            assertThat(authorization).hasValue("Bearer PARALLEL_LOCAL_KEY");
            assertThat(rawQuery.get()).isNull();
            assertThat(requestBody.get()).contains(
                    "\"name\":\"web_search\"", "\"objective\":\"q\"", "\"search_queries\":[\"q\"]")
                    .doesNotContain("numResults", "PARALLEL_LOCAL_KEY");
        }
    }

    @Test
    void disabledAndNetworkDenyPerformZeroHttpRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Server server = new Server(exchange -> { hits.incrementAndGet(); respond(exchange, 200, "application/json", JSON_SUCCESS); })) {
            try (HostedMcpWebSearchClient disabled = client(WebSearchConfiguration.disabled(), NetworkAccessDecision.allow())) {
                assertFailure(disabled, WebSearchFailure.DISABLED);
            }
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.empty(), Duration.ofSeconds(2));
            try (HostedMcpWebSearchClient denied = client(configuration,
                    NetworkAccessDecision.deny(NetworkAccessReason.POLICY_DENIED))) {
                assertFailure(denied, WebSearchFailure.NETWORK_DENIED);
            }
            assertThat(hits).hasValue(0);
        }
    }

    @Test
    void protocolHttpMalformedNoResultAndSizeFailuresAreTyped() throws Exception {
        assertStatusFailure(302, "application/json", "", WebSearchFailure.REDIRECT_REFUSED);
        assertStatusFailure(429, "application/json", "", WebSearchFailure.RATE_LIMITED);
        assertStatusFailure(400, "application/json", "", WebSearchFailure.REMOTE_CLIENT_ERROR);
        assertStatusFailure(503, "application/json", "", WebSearchFailure.REMOTE_SERVER_ERROR);
        assertStatusFailure(200, "text/plain", JSON_SUCCESS, WebSearchFailure.UNSUPPORTED_MEDIA_TYPE);
        assertStatusFailure(200, "", JSON_SUCCESS, WebSearchFailure.UNSUPPORTED_MEDIA_TYPE);
        assertStatusFailure(200, "application/json", "{bad", WebSearchFailure.MALFORMED_RESPONSE);
        assertStatusFailure(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1}}",
                WebSearchFailure.REMOTE_PROTOCOL_ERROR);
        assertStatusFailure(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}",
                WebSearchFailure.NO_RESULTS);
        assertStatusFailure(200, "application/json", "x".repeat(HostedMcpWebSearchClient.MAX_RESPONSE_BYTES + 1),
                WebSearchFailure.RESPONSE_TOO_LARGE);
        assertStatusFailure(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"wrong id\"}]}}",
                WebSearchFailure.MALFORMED_RESPONSE);
        assertStatusFailure(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\"}", WebSearchFailure.MALFORMED_RESPONSE);
    }

    @Test
    void externalControlsAreSanitized() throws Exception {
        String controlled = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"safe\\u001b[31m\\u0000\\tone\"}]}}";
        try (Server server = new Server(exchange -> respond(exchange, 200, "application/json", controlled))) {
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.empty(), Duration.ofSeconds(2));
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                String content = client.search(new WebSearchRequest("q", 1), CancellationToken.none()).content();
                assertThat(content).doesNotContain(Character.toString(0x1b), Character.toString(0), "\t");
            }
        }
    }

    @Test
    void totalWallDeadlineTimesOutAfterHeadersAndPartialBodyWithoutLeakingSensitiveText() throws Exception {
        try (StallingBodyServer server = new StallingBodyServer()) {
            String credential = "STALL_TIMEOUT_SECRET";
            String query = "STALL_TIMEOUT_QUERY";
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.of(credential), Duration.ofMillis(250));
            long started = System.nanoTime();
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                assertFailure(client, new WebSearchRequest(query, 1), CancellationToken.none(),
                        WebSearchFailure.TIMED_OUT, credential, query);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(server.headersAndPartialBody.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(elapsedMillis).isBetween(100L, 2_000L);
        }
    }

    @Test
    void cancellationAfterHeadersAndPartialBodyClosesRequestAndReturnsCancelled() throws Exception {
        try (StallingBodyServer server = new StallingBodyServer()) {
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.empty(), Duration.ofSeconds(3));
            CancellationSource source = new CancellationSource();
            AtomicReference<WebSearchFailure> failure = new AtomicReference<>();
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                Thread caller = Thread.ofPlatform().start(() -> {
                    try {
                        client.search(new WebSearchRequest("cancel slow body", 1), source.token());
                    } catch (WebSearchException expected) {
                        failure.set(expected.failure());
                    }
                });
                assertThat(server.headersAndPartialBody.await(1, TimeUnit.SECONDS)).isTrue();
                source.cancel();
                caller.join(2_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure).hasValue(WebSearchFailure.CANCELLED);
            }
        }
    }

    @Test
    void credentialAndQueryNeverAppearInFailureOrToolOutput() throws Exception {
        String credential = "LOCAL_FAILURE_SECRET";
        String query = "SECRET_FAILURE_QUERY";
        try (Server server = new Server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "text/plain", JSON_SUCCESS);
        })) {
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.of(credential), Duration.ofSeconds(2));
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                try {
                    client.search(new WebSearchRequest(query, 1), CancellationToken.none());
                    throw new AssertionError("expected unsupported media type");
                } catch (WebSearchException failure) {
                    assertThat(failure.failure()).isEqualTo(WebSearchFailure.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(failure).hasMessage("UNSUPPORTED_MEDIA_TYPE");
                    assertThat(failure.toString()).doesNotContain(credential, query);
                }
                var outcome = new WebSearchTool(client).execute(invocation(json("query", query)));
                assertThat(outcome.successful()).isFalse();
                assertThat(outcome.error().orElseThrow().message()).doesNotContain(credential, query);
                assertThat(outcome.toString()).doesNotContain(credential, query);
            }
            assertThat(configuration.toString()).doesNotContain(credential);
        }
    }

    @Test
    void mapsProtocolAndNoResultFailuresWithoutSensitiveText() {
        for (var entry : Map.of(
                WebSearchFailure.REMOTE_PROTOCOL_ERROR, ToolErrorCode.WEB_SEARCH_REMOTE_PROTOCOL_ERROR,
                WebSearchFailure.UNSUPPORTED_MEDIA_TYPE, ToolErrorCode.WEB_SEARCH_UNSUPPORTED_MEDIA_TYPE,
                WebSearchFailure.MALFORMED_RESPONSE, ToolErrorCode.WEB_SEARCH_MALFORMED_RESPONSE,
                WebSearchFailure.NO_RESULTS, ToolErrorCode.WEB_SEARCH_NO_RESULTS).entrySet()) {
            WebSearchTool tool = new WebSearchTool((request, cancellation) -> { throw new WebSearchException(entry.getKey()); });
            var outcome = tool.execute(invocation(json("query", "SECRET_QUERY")));
            assertThat(outcome.error().orElseThrow().code()).isEqualTo(entry.getValue());
            assertThat(outcome.error().orElseThrow().message()).doesNotContain("SECRET_QUERY", "http", "token");
        }
    }

    private static WebSearchTool toolReturning(String content) {
        return new WebSearchTool((request, cancellation) -> new WebSearchResponse("mcp.example", content, 1, false));
    }

    private static HostedMcpWebSearchClient client(
            WebSearchConfiguration configuration, NetworkAccessDecision decision) {
        return new HostedMcpWebSearchClient(configuration, (request, cancellation) -> decision);
    }

    private static void assertStatusFailure(
            int status, String contentType, String body, WebSearchFailure expected) throws Exception {
        try (Server server = new Server(exchange -> respond(exchange, status, contentType, body))) {
            var configuration = WebSearchConfiguration.loopbackDevelopment(
                    WebSearchProvider.EXA, server.uri(), Optional.empty(), Duration.ofSeconds(2));
            try (HostedMcpWebSearchClient client = client(configuration, NetworkAccessDecision.allow())) {
                assertFailure(client, expected);
            }
        }
    }

    private static void assertFailure(HostedMcpWebSearchClient client, WebSearchFailure expected) throws Exception {
        assertFailure(client, CancellationToken.none(), expected);
    }

    private static void assertFailure(
            HostedMcpWebSearchClient client, CancellationToken cancellation, WebSearchFailure expected) throws Exception {
        assertFailure(client, new WebSearchRequest("q", 1), cancellation, expected);
    }

    private static void assertFailure(HostedMcpWebSearchClient client, WebSearchRequest request,
            CancellationToken cancellation, WebSearchFailure expected, String... sensitive) throws Exception {
        try {
            client.search(request, cancellation);
        } catch (WebSearchException failure) {
            assertThat(failure.failure()).isEqualTo(expected);
            if (sensitive.length > 0) assertThat(failure.toString()).doesNotContain(sensitive);
            return;
        }
        throw new AssertionError("expected failure " + expected);
    }

    private static ToolInvocation invocation(JsonObject arguments) {
        return new ToolInvocation(new SessionId("session-web"), new RunId("run-web"), 1,
                new ToolCall("call-web", "web_search", arguments));
    }

    private static JsonObject json(Object... entries) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]);
        return new JsonObject(values);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }

    private static final class StallingBodyServer implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch headersAndPartialBody = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        StallingBodyServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("{\"jsonrpc\":\"2.0\",\"id\":1,".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    headersAndPartialBody.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                } catch (IOException ignored) {
                    // Client timeout/cancel closes the connection; this is the expected release path.
                }
            });
            server.start();
        }

        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"); }

        @Override public void close() {
            release.countDown();
            server.stop(0);
        }
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;

        Server(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> handler.handle(exchange));
            server.start();
        }

        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"); }
        @Override public void close() { server.stop(0); }
    }
}
