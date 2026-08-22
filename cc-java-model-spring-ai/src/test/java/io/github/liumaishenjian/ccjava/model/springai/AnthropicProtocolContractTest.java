package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelRetryPolicy;
import io.github.liumaishenjian.ccjava.core.ModelRetryRuntime;
import io.github.liumaishenjian.ccjava.core.RetryingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.model.springai.config.AnthropicSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Anthropic Messages API 的 protocol-level mock 契约。
 *
 * <p>Fixture 只模拟公开 wire 行为，不使用真实凭证，也不把 Provider 原始正文作为产品错误。
 * 覆盖 text/stream/tool/multi-tool/usage/cancel/429 Retry-After/5xx/context-limit。</p>
 */
class AnthropicProtocolContractTest {
    @Test void streamsTextAndUsage() throws Exception {
        try (Fixture fixture = Fixture.respond(200, textStream(), "text/event-stream")) {
            ModelTurn turn = gateway(fixture).complete(request(List.of()), ignored -> { }, CancellationToken.none());
            assertThat(turn.assistantMessage().text()).isEqualTo("ok");
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.STOP);
            assertThat(turn.metadata().usage()).isPresent();
        }
    }

    @Test void aggregatesSingleAndMultipleToolUseBlocks() throws Exception {
        try (Fixture fixture = Fixture.respond(200, toolStream(2), "text/event-stream")) {
            ModelTurn turn = gateway(fixture).complete(request(List.of(tool("first_probe"), tool("second_probe"))),
                    ignored -> { }, CancellationToken.none());
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
            assertThat(turn.assistantMessage().toolCalls()).extracting(c -> c.name())
                    .containsExactly("first_probe", "second_probe");
        }
        try (Fixture fixture = Fixture.respond(200, toolStream(1), "text/event-stream")) {
            assertThat(gateway(fixture).complete(request(List.of(tool("first_probe"))), ignored -> { },
                    CancellationToken.none()).assistantMessage().toolCalls()).hasSize(1);
        }
    }

    @Test void retries429WithRetryAfterAndClassifies5xxAndContextLimit() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Duration> waited = new java.util.concurrent.atomic.AtomicReference<>();
        try (Fixture fixture = Fixture.dynamic(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("retry-after", "2");
                json(exchange, 429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"busy\"}}");
            } else sse(exchange, textStream());
        })) {
            ModelRetryRuntime runtime = new ModelRetryRuntime() {
                @Override public double nextRandom() { return 0d; }
                @Override public void await(Duration delay, CancellationToken cancellation) { waited.set(delay); }
            };
            ModelTurn turn = new RetryingModelGateway(gateway(fixture),
                    new ModelRetryPolicy(2, List.of(Duration.ZERO)), runtime).complete(
                            request(List.of()), ignored -> { }, CancellationToken.none());
            assertThat(turn.assistantMessage().text()).isEqualTo("ok");
            assertThat(attempts).hasValue(2);
            assertThat(waited.get()).isEqualTo(Duration.ofSeconds(2));
        }
        try (Fixture fixture = Fixture.respond(503,
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"SECRET\"}}",
                "application/json")) {
            assertThatThrownBy(() -> gateway(fixture).complete(request(List.of()), ignored -> { }, CancellationToken.none()))
                    .isInstanceOf(ModelGatewayException.class).hasMessageNotContaining("SECRET");
        }
        try (Fixture fixture = Fixture.respond(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long\"}}",
                "application/json")) {
            assertThatThrownBy(() -> gateway(fixture).complete(request(List.of()), ignored -> { }, CancellationToken.none()))
                    .isInstanceOf(ModelGatewayException.class);
        }
    }

    @Test void cancellationStopsSlowStream() throws Exception {
        try (Fixture fixture = Fixture.dynamic(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(textStream().substring(0, textStream().indexOf("event: message_delta"))
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(2_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            CancellationSource source = new CancellationSource();
            Thread.startVirtualThread(() -> { try { Thread.sleep(50); } catch (InterruptedException ignored) { } source.cancel(); });
            assertThatThrownBy(() -> gateway(fixture).complete(request(List.of()), ignored -> { }, source.token()))
                    .isInstanceOf(ModelGatewayException.class);
        }
    }

    private static SpringAiModelGateway gateway(Fixture fixture) {
        AnthropicSettings settings = new AnthropicSettings(
                URI.create("http://127.0.0.1:" + fixture.port()), "fixture-key", "fixture-model");
        return new SpringAiModelGateway(new AnthropicModelFactory().create(settings), settings.model());
    }
    private static ModelRequest request(List<ToolDefinition> tools) {
        return new ModelRequest(new SessionId("anthropic-fixture"), new RunId("fixture-run"), 1,
                List.of(new UserMessage("probe")), tools);
    }
    private static ToolDefinition tool(String name) { return ToolDefinition.readOnlyText(name, "record probe",
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}"); }

    private static String textStream() { return """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg-1","type":"message","role":"assistant","content":[],"model":"fixture-model","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

            event: message_stop
            data: {"type":"message_stop"}

            """; }
    private static String toolStream(int count) {
        StringBuilder b = new StringBuilder("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-t\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"fixture-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n");
        String[] names = {"first_probe", "second_probe"};
        for (int i=0;i<count;i++) b.append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":").append(i).append(",\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-").append(i).append("\",\"name\":\"").append(names[i]).append("\",\"input\":{}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":").append(i).append(",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"value\\\":\\\"V\\\"}\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":").append(i).append("}\n\n");
        return b.append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":8}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n").toString();
    }
    private static void sse(HttpExchange e,String body)throws IOException{e.getRequestBody().readAllBytes();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);e.getResponseBody().write(bytes);e.close();}
    private static void json(HttpExchange e,int status,String body)throws IOException{e.getRequestBody().readAllBytes();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();}

    private static final class Fixture implements AutoCloseable {
        interface Handler { void handle(HttpExchange exchange) throws IOException; }
        private final HttpServer server;
        private Fixture(Handler handler)throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/messages",handler::handle);server.start();}
        static Fixture respond(int status,String body,String type)throws IOException{return dynamic(e->{if(status==200)sse(e,body);else json(e,status,body);});}
        static Fixture dynamic(Handler h)throws IOException{return new Fixture(h);}
        int port(){return server.getAddress().getPort();}
        public void close(){server.stop(0);}
    }
}
