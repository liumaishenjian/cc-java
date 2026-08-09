package io.github.liumaishenjian.ccjava.cli.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** 验证 HTTP Hook 的 loopback、协议、超时与 SSRF 边界。 */
class HttpHookHandlerTest {

    @Test
    void postsToRealLoopbackEndpointAndParsesDecision() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(input).contains("\"event\":\"PRE_COMPACT\"");
                byte[] body = "{\"disposition\":\"BLOCK\",\"reason\":\"guarded\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            HttpHookHandler handler = new HttpHookHandler(
                    "http",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                    Duration.ofSeconds(1));
            var result = handler.execute(new HookInvocation(
                    HookEventKind.PRE_COMPACT,
                    new SessionId("session-1"),
                    Optional.empty(),
                    "compact",
                    JsonObject.empty()), CancellationToken.none());

            assertThat(result.status()).isEqualTo(HookExecutionStatus.COMPLETED);
            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.reason()).contains("guarded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonLoopbackAndHttpsRemoteEndpoints() {
        assertThatThrownBy(() -> new HttpHookHandler(
                "http", URI.create("http://192.0.2.1/hook"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpHookHandler(
                "http", URI.create("https://example.com/hook"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
