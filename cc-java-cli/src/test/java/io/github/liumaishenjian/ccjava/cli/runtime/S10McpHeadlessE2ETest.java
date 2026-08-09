package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 从固定配置到真实 STDIO Server、Permission、Pipeline 和下一模型回合的完整 E2E。 */
class S10McpHeadlessE2ETest {

    @Test
    void configuredMcpToolTraversesApprovalAndUnifiedPipeline(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path home = Files.createDirectories(root.resolve("home").resolve(".cc-java"));
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        Map<String, Object> server = Map.of(
                "name", "fixture",
                "transport", "stdio",
                "command", java.toString(),
                "args", List.of("-cp", System.getProperty("java.class.path"), RuntimeMcpStdioFixture.class.getName()),
                "timeoutMs", 5_000);
        Files.write(home.resolve("extensions.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "version", 1,
                "mcpServers", List.of(server))));
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            if (requests.size() == 1) {
                return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall(
                        "call-mcp", "mcp__fixture__echo", new JsonObject(Map.of("value", "hello"))))),
                        ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        };
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace, "fake-model", Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(),
                SessionOpenRequest.create(), root.resolve("sessions"));

        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                model,
                AgentEventSink.noop(),
                options,
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce(),
                ContextPreparationService.noop(),
                null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> root.resolve("home")),
                null,
                true)) {
            runtime.open();
            assertThat(runtime.extensionStatus().diagnosticCode()).isEmpty();
            assertThat(runtime.mcpSnapshots()).containsExactly(
                    new io.github.liumaishenjian.ccjava.mcp.McpServerSnapshot(
                            "fixture", io.github.liumaishenjian.ccjava.mcp.McpConnectionStatus.CONNECTED, 1));
            assertThat(runtime.run("use mcp").stopReason()).isEqualTo(StopReason.COMPLETED);
        }

        assertThat(requests.getFirst().toolDefinitions())
                .extracting(definition -> definition.name())
                .contains("mcp__fixture__echo");
        assertThat(requests.getLast().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> {
                    var result = ((ToolResultMessage) message).result();
                    assertThat(result.status()).isEqualTo(
                            io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS);
                    assertThat(result.content()).isEqualTo("runtime:hello");
                });
    }
}
