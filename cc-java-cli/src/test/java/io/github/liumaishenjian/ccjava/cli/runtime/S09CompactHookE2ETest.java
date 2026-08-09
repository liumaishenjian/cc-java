package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.LatestContextUsageCollector;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 验证固定配置 HTTP PRE_COMPACT 在摘要器之前阻断且不污染 Canonical。 */
class S09CompactHookE2ETest {

    @Test
    void trustedUserPreCompactHookBlocksBeforeSummarizer(@TempDir Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"disposition\":\"BLOCK\",\"reason\":\"policy\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Path home = Files.createDirectories(root.resolve("home").resolve(".cc-java"));
            Files.write(home.resolve("extensions.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of(
                    "version", 1,
                    "hooks", List.of(Map.of(
                            "id", "compact-guard",
                            "event", "PRE_COMPACT",
                            "failurePolicy", "FAIL_CLOSED",
                            "timeoutMs", 1_000,
                            "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook")))));
            ContextPreparationConfig config = new ContextPreparationConfig(
                    new ContextCapacity("fake-model", 10_000, 1_000, 500),
                    1_000, 2, 4_096, 1_000);
            HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                    workspace, "fake-model", Duration.ofSeconds(5), PermissionMode.DEFAULT, List.of(),
                    SessionOpenRequest.create(), root.resolve("sessions"), Optional.of(config));
            AtomicInteger summaries = new AtomicInteger();
            LatestContextUsageCollector usage = new LatestContextUsageCollector();
            ContextPreparationService preparation = new ContextPreparationService(
                    config,
                    (request, token) -> {
                        summaries.incrementAndGet();
                        return Optional.empty();
                    },
                    usage);

            try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                    request -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unused"),
                    AgentEventSink.noop(), options,
                    (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                    preparation, usage,
                    HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                    HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                    null, true)) {
                runtime.open();
                assertThat(runtime.compactForNextRun(List.of(), io.github.liumaishenjian.ccjava.core.CancellationToken.none()))
                        .isEqualTo(HeadlessRuntimeSession.CompactResult.HOOK_BLOCKED);
            }
            assertThat(summaries).hasValue(0);
        } finally {
            server.stop(0);
        }
    }
}
