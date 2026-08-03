package io.github.liumaishenjian.ccjava.model.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelRetryPolicy;
import io.github.liumaishenjian.ccjava.core.RetryingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过本机 OpenAI-compatible SSE Fixture 验证 Spring AI 的原始 Chunk 聚合契约。
 *
 * <p>该测试不会访问外网，也不使用维护者配置。Fixture 故意把两个 Tool Call 的参数
 * 拆到多个 SSE Chunk，用来区分“模型没有生成多个调用”和“Adapter 聚合丢失”。</p>
 */
class OpenAiStreamingContractTest {

    @Test
    void aggregatesTwoToolCallsAcrossSseChunks() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext("/v1/chat/completions", this::writeToolCallStream);
        server.start();
        try {
            OpenAiCompatibleSettings settings = new OpenAiCompatibleSettings(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-key",
                    "fixture-model");
            SpringAiModelGateway gateway = new SpringAiModelGateway(
                    new OpenAiCompatibleModelFactory().create(settings),
                    settings.model());

            ModelTurn turn = gateway.complete(
                    request(),
                    ignored -> {
                    },
                    CancellationToken.none());

            assertThat(turn.metadata().finishReason())
                    .isEqualTo(ModelFinishReason.TOOL_CALLS);
            assertThat(turn.assistantMessage().toolCalls())
                    .extracting(call -> call.id())
                    .containsExactly("call-1", "call-2");
            assertThat(turn.assistantMessage().toolCalls())
                    .extracting(call -> call.name())
                    .containsExactly("first_probe", "second_probe");
            assertThat(turn.assistantMessage().toolCalls())
                    .extracting(call -> call.arguments().values().get("value"))
                    .containsExactly("FIRST", "SECOND");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTwoHttpRateLimitsThenStreamsSuccessfulResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (requests.incrementAndGet() < 3) {
                writeRateLimit(exchange);
            } else {
                writeTextStream(exchange);
            }
        });
        server.start();
        try {
            SpringAiModelGateway provider = gateway(server);
            RetryingModelGateway gateway = new RetryingModelGateway(
                    provider,
                    new ModelRetryPolicy(
                            3,
                            List.of(java.time.Duration.ZERO, java.time.Duration.ZERO)));

            ModelTurn turn = gateway.complete(
                    textRequest(),
                    ignored -> {
                    },
                    CancellationToken.none());

            assertThat(requests).hasValue(3);
            assertThat(turn.assistantMessage().text()).isEqualTo("ok");
            assertThat(turn.metadata().finishReason()).isEqualTo(ModelFinishReason.STOP);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsSanitizedProviderUnavailableAfterThreeServerFailures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"SECRET_URL_AND_KEY\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RetryingModelGateway gateway = new RetryingModelGateway(
                    gateway(server),
                    new ModelRetryPolicy(
                            3,
                            List.of(java.time.Duration.ZERO, java.time.Duration.ZERO)));

            assertThatThrownBy(() -> gateway.complete(
                    textRequest(), ignored -> { }, CancellationToken.none()))
                    .isInstanceOf(ModelGatewayException.class)
                    .satisfies(failure -> {
                        ModelGatewayException modelFailure = (ModelGatewayException) failure;
                        assertThat(modelFailure.summary()).hasValueSatisfying(summary -> {
                            assertThat(summary.category())
                                    .isEqualTo(ModelFailureCategory.PROVIDER_UNAVAILABLE);
                            assertThat(summary.statusClass())
                                    .contains(ModelHttpStatusClass.SERVER_ERROR);
                            assertThat(summary.attempts()).isEqualTo(3);
                            assertThat(summary.receivedOutput()).isFalse();
                            assertThat(summary.toString()).doesNotContain("SECRET_URL_AND_KEY");
                        });
                    });
            assertThat(requests).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsSseEofWithoutFinishReasonAsIncomplete() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext("/v1/chat/completions", this::writeIncompleteTextStream);
        server.start();
        try {
            SpringAiModelGateway gateway = gateway(server);

            assertThatThrownBy(() -> gateway.complete(
                    textRequest(),
                    ignored -> {
                    },
                    CancellationToken.none()))
                    .isInstanceOf(ModelGatewayException.class)
                    .satisfies(failure -> {
                        ModelGatewayException modelFailure = (ModelGatewayException) failure;
                        assertThat(modelFailure.kind()).isEqualTo(INCOMPLETE_STREAM);
                        assertThat(modelFailure.summary()).hasValueSatisfying(summary -> {
                            assertThat(summary.category())
                                    .isEqualTo(ModelFailureCategory.INVALID_RESPONSE);
                            assertThat(summary.receivedOutput()).isTrue();
                        });
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesLengthFinishReasonForRuntimePolicy() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext("/v1/chat/completions", this::writeLengthStream);
        server.start();
        try {
            ModelTurn turn = gateway(server).complete(
                    textRequest(),
                    ignored -> {
                    },
                    CancellationToken.none());

            assertThat(turn.assistantMessage().text()).isEqualTo("partial");
            assertThat(turn.metadata().finishReason())
                    .isEqualTo(ModelFinishReason.LENGTH);
        } finally {
            server.stop(0);
        }
    }

    private void writeToolCallStream(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = sseBody().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void writeRateLimit(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = """
                {"error":{"message":"busy","type":"rate_limit_error","code":"rate_limit"}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(429, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void writeTextStream(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeIncompleteTextStream(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"partial"},"finish_reason":null}]}

                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeLengthStream(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"partial"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String sseBody() {
        return """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"first_probe","arguments":"{\\"value\\":"}}]},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"FIRST\\"}"}}]},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call-2","type":"function","function":{"name":"second_probe","arguments":"{\\"value\\":"}}]},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\\"SECOND\\"}"}}]},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}

                data: [DONE]

                """;
    }

    private static ModelRequest request() {
        ToolDefinition first = ToolDefinition.readOnlyText(
                "first_probe",
                "record first",
                """
                {"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}
                """);
        ToolDefinition second = ToolDefinition.readOnlyText(
                "second_probe",
                "record second",
                """
                {"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}
                """);
        return new ModelRequest(
                new SessionId("fixture-session"),
                new RunId("fixture-run"),
                1,
                List.of(new UserMessage("record both")),
                List.of(first, second));
    }

    private static ModelRequest textRequest() {
        return new ModelRequest(
                new SessionId("fixture-session"),
                new RunId("fixture-text-run"),
                1,
                List.of(new UserMessage("say ok")),
                List.of());
    }

    private static SpringAiModelGateway gateway(HttpServer server) {
        OpenAiCompatibleSettings settings = new OpenAiCompatibleSettings(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-key",
                "fixture-model");
        return new SpringAiModelGateway(
                new OpenAiCompatibleModelFactory().create(settings),
                settings.model());
    }
}
