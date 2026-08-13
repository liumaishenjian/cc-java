package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TOOL-18 外部配置 Gate 与 Headless 生产扩展装配的专项 E2E。 */
class S15WebSearchHeadlessE2ETest {

    @Test
    void defaultDisabledCompositionDoesNotRegisterWebSearch(@TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        var resources = WebSearchRuntimeResources.fromConfiguration(WebSearchConfiguration.disabled());
        try (HeadlessRuntimeSession runtime = runtime(workspace, root, request -> ModelTurn.text("done"),
                resources, (invocation, definition, outcome) -> ApprovalResponse.allowOnce())) {
            runtime.open();
            assertThat(runtime.builtinToolRegistry().definitions())
                    .extracting(definition -> definition.name())
                    .doesNotContain("web_search");
        }
    }

    @Test
    void externallyEnabledCompositionRegistersAndExecutesRealLoopbackSearch(@TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        AtomicInteger hits = new AtomicInteger();
        try (Loopback fixture = new Loopback(hits)) {
            WebSearchConfiguration configuration = WebSearchConfiguration.loopbackDevelopment(
                    io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider.EXA,
                    fixture.uri(), Optional.empty(), Duration.ofSeconds(3));
            CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
            ModelGateway model = request -> {
                requests.add(request);
                if (requests.size() == 1) {
                    return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall(
                            "call-headless-web", "web_search", new JsonObject(Map.of(
                                    "query", "bounded", "result_limit", 1))))), ModelTurnMetadata.unknown());
                }
                return ModelTurn.text("done");
            };
            try (HeadlessRuntimeSession runtime = runtime(workspace, root, model,
                    WebSearchRuntimeResources.fromConfiguration(configuration),
                    (invocation, definition, outcome) -> ApprovalResponse.allowOnce())) {
                runtime.open();
                assertThat(runtime.builtinToolRegistry().definitions())
                        .extracting(definition -> definition.name())
                        .contains("web_search");
                assertThat(runtime.run("search").stopReason()).isEqualTo(StopReason.COMPLETED);
            }

            assertThat(hits).hasValue(1);
            assertThat(requests.getLast().messages())
                    .filteredOn(ToolResultMessage.class::isInstance)
                    .singleElement()
                    .satisfies(message -> {
                        var result = ((ToolResultMessage) message).result();
                        assertThat(result.callId()).isEqualTo("call-headless-web");
                        assertThat(result.content()).contains(
                                "provenance: external-web-search", "providerHost: 127.0.0.1", "Headless");
                    });
        }
    }

    private static HeadlessRuntimeSession runtime(java.nio.file.Path workspace, java.nio.file.Path root,
            ModelGateway model, WebSearchRuntimeResources webResources,
            io.github.liumaishenjian.ccjava.core.ApprovalHandler approvals) {
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(),
                SessionOpenRequest.create(), root.resolve("sessions"));
        return new HeadlessRuntimeSession(model, AgentEventSink.noop(), options, approvals,
                ContextPreparationService.noop(), null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                null, true, webResources);
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;

        Loopback(AtomicInteger hits) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/search", exchange -> {
                hits.incrementAndGet();
                byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Headless with https://example.com/source\"}]}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        java.net.URI uri() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        }

        @Override public void close() { server.stop(0); }
    }
}
